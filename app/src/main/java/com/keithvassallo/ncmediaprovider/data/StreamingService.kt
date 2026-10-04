package com.keithvassallo.ncmediaprovider.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.keithvassallo.ncmediaprovider.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * A foreground service that only exists to keep this app's network open: Android 17 allows it
 * only while the app is in the foreground or MediaProvider is calling it (Phase 5 phone tests). It
 * is started from inside MediaProvider's call, while Android counts the app as bound by it, which
 * is one of the states in which a foreground service may start.
 */
abstract class KeepAliveService : Service() {
    protected abstract val notificationId: Int
    protected abstract val serviceType: Int
    protected abstract val title: Int
    protected abstract val text: Int

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.streaming_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // Play wants a foreground service the user can stop (docs/play-notes.md).
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.streaming_stop), stop)
            .build()
        startForeground(notificationId, notification, serviceType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "${javaClass.simpleName} stopped from its notification")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 and later give a dataSync service 6 hours a day; past that it must stop at once,
     * or the app is crashed. The stream it kept open fails, as it would offline.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "${javaClass.simpleName} ran out of foreground time")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "KeepAlive"
        const val CHANNEL = "streaming"
        const val ACTION_STOP = "com.keithvassallo.ncmediaprovider.action.STOP_STREAMING"
    }
}

/**
 * Starts [service] with its first user and stops it once none has been active for [lingerMillis]:
 * apps reopen a video several times while preparing it, and the picker recreates its player.
 */
class KeepAlive(private val service: Class<out KeepAliveService>, private val lingerMillis: Long = 20_000L) {
    private val users = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null

    fun acquire(context: Context) {
        users.incrementAndGet()
        main.post { pendingStop?.let(main::removeCallbacks) }
        runCatching { context.startForegroundService(Intent(context, service)) }
            .onFailure { Log.w(TAG, "Couldn't start ${service.simpleName}: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
    }

    fun release(context: Context) {
        if (users.decrementAndGet() > 0) return
        main.post {
            pendingStop?.let(main::removeCallbacks)
            pendingStop = Runnable { if (users.get() == 0) context.stopService(Intent(context, service)) }
                .also { main.postDelayed(it, lingerMillis) }
        }
    }

    private companion object {
        const val TAG = "KeepAlive"
    }
}

/** Keeps the network open while another app reads a streamed video (#34). */
class StreamingService : KeepAliveService() {
    override val notificationId = 10
    override val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    override val title = R.string.streaming_title
    override val text = R.string.streaming_text

    companion object {
        private val keepAlive = KeepAlive(StreamingService::class.java)

        /** Call when a stream opens, from inside MediaProvider's call. */
        fun streamOpened(context: Context) = keepAlive.acquire(context)

        fun streamClosed(context: Context) = keepAlive.release(context)
    }
}
