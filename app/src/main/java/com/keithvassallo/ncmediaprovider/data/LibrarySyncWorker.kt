package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException

/** What a running sync is doing, shown in the app (PLAN 2.5). */
sealed interface SyncProgress {
    /** Comparing folder etags with the last check. */
    data object Checking : SyncProgress

    /** Listing everything; [files] so far. */
    data class Listing(val files: Int) : SyncProgress
}

/**
 * Runs [LibraryRepository.syncNow] as a WorkManager job. Jobs with a network constraint keep network
 * access while they run, which this app's own threads lose once MediaProvider's call returns
 * (PLAN 2.5a).
 */
class LibrarySyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        val full = inputData.getBoolean(KEY_FULL, false)
        LibraryRepository.get(applicationContext).syncNow(full) { setProgressAsync(it.toData()) }
        Result.success()
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
