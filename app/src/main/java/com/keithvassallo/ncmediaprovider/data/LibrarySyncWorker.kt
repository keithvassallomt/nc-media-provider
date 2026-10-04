package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException

/** What a running sync is doing, shown in the app (#16). */
sealed interface SyncProgress {
    /** Comparing folder etags with the last check. */
    data object Checking : SyncProgress

    /** Listing everything; [files] so far. */
    data class Listing(val files: Int) : SyncProgress
}

/**
 * Runs [LibraryRepository.syncNow] as a WorkManager job. Jobs with a network constraint keep network
 * access while they run, which this app's own threads lose once MediaProvider's call returns
 * (#16).
 */
class LibrarySyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        val repository = LibraryRepository.get(applicationContext)
        if (inputData.getBoolean(KEY_WIPE_CHECK, false)) {
            repository.checkRemoteWipe()
        } else {
            repository.syncNow(inputData.getBoolean(KEY_FULL, false), onProgress = { setProgressAsync(it.toData()) }, isStopped = { isStopped })
            repository.noteSelectedProvider(repository.isSelectedProvider())
        }
        Result.success()
    } catch (error: SignInRequiredException) {
        // Never retried: each refused request counts towards Nextcloud's brute-force throttling.
        Log.w(TAG, "Sync stopped: ${error.message}")
        Result.failure()
    } catch (error: IOException) {
        Log.w(TAG, "Sync failed, will retry: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        Result.retry()
    } catch (error: Exception) {
        Log.e(TAG, "Sync failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}", error)
        Result.failure()
    }

    companion object {
        private const val TAG = "LibrarySyncWorker"

        /** Input: true for a full listing rather than a change check. */
        const val KEY_FULL = "full"

        /** Input: true to only check whether the server asked to wipe this device (#29). */
        const val KEY_WIPE_CHECK = "wipe_check"

        private const val KEY_PHASE = "phase"
        private const val KEY_FILES = "files"
        private const val PHASE_CHECKING = "checking"
        private const val PHASE_LISTING = "listing"

        private fun SyncProgress.toData(): Data = when (this) {
            SyncProgress.Checking -> workDataOf(KEY_PHASE to PHASE_CHECKING)
            is SyncProgress.Listing -> workDataOf(KEY_PHASE to PHASE_LISTING, KEY_FILES to files)
        }

        /** The progress a running job last reported, or null before its first report. */
        fun progressOf(job: WorkInfo): SyncProgress? = when (job.progress.getString(KEY_PHASE)) {
            PHASE_CHECKING -> SyncProgress.Checking
            PHASE_LISTING -> SyncProgress.Listing(job.progress.getInt(KEY_FILES, 0))
            else -> null
        }
    }
}
