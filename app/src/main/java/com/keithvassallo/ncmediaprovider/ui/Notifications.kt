package com.keithvassallo.ncmediaprovider.ui

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.keithvassallo.ncmediaprovider.R

/** The few things worth interrupting someone for (PLAN 4.4 and 4.6). */
object Notifications {
    private const val CHANNEL = "account"
    private const val SIGN_IN = 1
    private const val WIPED = 2
    private const val PROVIDER_DESELECTED = 3

    fun showSignInRequired(context: Context) = show(
        context, SIGN_IN, R.string.notify_sign_in_title, R.string.notify_sign_in_text,
        Intent(context, SignInActivity::class.java),
    )

    fun showWiped(context: Context) = show(
        context, WIPED, R.string.notify_wiped_title, R.string.notify_wiped_text,
        Intent(context, HomeActivity::class.java),
    )

    fun showProviderDeselected(context: Context) = show(
        context, PROVIDER_DESELECTED, R.string.notify_deselected_title, R.string.notify_deselected_text,
        Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS),
    )

    fun cancelSignInRequired(context: Context) = NotificationManagerCompat.from(context).cancel(SIGN_IN)

    @SuppressLint("MissingPermission") // Checked through areNotificationsEnabled().
    private fun show(context: Context, id: Int, title: Int, text: Int, target: Intent) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val intent = PendingIntent.getActivity(
            context, id, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = context.getString(text)
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build(),
        )
    }
}
