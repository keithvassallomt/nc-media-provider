package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Downloads grid thumbnails ahead of time (#59), so the picker and Send from Nextcloud show the
 * library at once instead of tile by tile. WorkManager runs it again when it stopped it early or the
 * network failed; already cached thumbnails are skipped, so a run carries on where the last stopped.
 */
class ThumbnailPrecacheWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val repository = LibraryRepository.get(applicationContext)
        val networkHeld = repository.precacheThumbnails(isStopped = { isStopped }) { ready, total ->
            setProgressAsync(workDataOf(KEY_READY to ready, KEY_TOTAL to total))
        }
        return if (networkHeld) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_READY = "ready"
        const val KEY_TOTAL = "total"
    }
}
