package com.keithvassallo.ncmediaprovider.activation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.ui.Notifications
import java.util.concurrent.TimeUnit

/**
 * Android deselects a cloud provider whenever its app is updated (Phase 1.5). After an update this
 * selects the app again through Shizuku if it can, since the shell may make that call, and
 * otherwise posts a notification that opens the picker's cloud settings (PLAN 4.6).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProviderReselectWorker>()
                // MediaProvider handles the same package change; let it deselect first.
                .setInitialDelay(RESELECT_DELAY_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    private companion object {
        const val WORK = "reselect-provider"
        const val RESELECT_DELAY_SECONDS = 10L
    }
}

class ProviderReselectWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val repository = LibraryRepository.get(applicationContext)
        if (!repository.hasAccount || !repository.wasSelectedProvider || repository.isSelectedProvider()) return Result.success()
        val reselected = ShizukuSession.isUsable(waitMillis = SHIZUKU_WAIT_MS) &&
            runCatching { ShizukuSession.withService { it.selectProvider() } == true }.getOrDefault(false)
        if (reselected) {
            Log.i(TAG, "Selected again as the picker's cloud provider after the update, through Shizuku")
        } else {
            Log.i(TAG, "Deselected by the update; asking the user to select the provider again")
            Notifications.showProviderDeselected(applicationContext)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "ProviderReselect"
        const val SHIZUKU_WAIT_MS = 5_000L
    }
}
