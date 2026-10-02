package com.keithvassallo.ncmediaprovider.data

import android.app.NotificationChannel
import android.app.NotificationManager
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
 * Keeps the network open while another app reads a streamed video (PLAN 5.2). Android 17 allows
 * this app's network only while it is in the foreground or MediaProvider is calling it; a stream is
 * read after that call returns, and the phone test saw every read fail 5 s later with "Unable to
 * resolve host" (Phase 5). A foreground service lifts that for as long as any stream is open.
 *
 * It is started from inside MediaProvider's call to open the file, while the app is bound by
 * MediaProvider, which is one of the states in which Android lets an app start a foreground
 * service. It lingers briefly after the last stream closes, because apps reopen a video several
 * times while preparing it.
 */
class StreamingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.streaming_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.streaming_title))
            .setContentText(getString(R.string.streaming_text))
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StreamingService"
        private const val CHANNEL = "streaming"
        private const val NOTIFICATION_ID = 10
        private const val LINGER_MS = 20_000L

        private val openStreams = AtomicInteger()
        private val main = Handler(Looper.getMainLooper())
        private var pendingStop: Runnable? = null

        /** Call when a stream opens, from inside MediaProvider's call. */
        fun streamOpened(context: Context) {
            openStreams.incrementAndGet()
            main.post { pendingStop?.let(main::removeCallbacks) }
            runCatching { context.startForegroundService(Intent(context, StreamingService::class.java)) }
                .onFailure { Log.w(TAG, "Couldn't start the streaming service: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
        }

        /** Call when a stream closes; the service stops once none has been open for a while. */
        fun streamClosed(context: Context) {
            if (openStreams.decrementAndGet() > 0) return
            main.post {
                pendingStop?.let(main::removeCallbacks)
                pendingStop = Runnable {
                    if (openStreams.get() == 0) context.stopService(Intent(context, StreamingService::class.java))
                }.also { main.postDelayed(it, LINGER_MS) }
            }
        }
    }
}
