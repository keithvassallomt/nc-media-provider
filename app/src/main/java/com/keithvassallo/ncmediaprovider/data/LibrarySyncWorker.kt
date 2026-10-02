package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

/**
 * Runs [LibraryRepository.syncNow] as a WorkManager job. Jobs with a network constraint keep network
 * access while they run, which this app's own threads lose once MediaProvider's call returns
 * (PLAN 2.5a).
 */
class LibrarySyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        LibraryRepository.get(applicationContext).syncNow()
        Result.success()
    } catch (error: IOException) {
        Log.w(TAG, "Sync failed, will retry: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        Result.retry()
    } catch (error: Exception) {
        Log.e(TAG, "Sync failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}", error)
        Result.failure()
    }

    private companion object {
        const val TAG = "LibrarySyncWorker"
    }
}
