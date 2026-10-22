package com.keithvassallo.ncmediaprovider

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.color.DynamicColors
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.share.SendFromNextcloudActivity

class NcMediaProviderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The phone's Material You colours, from its wallpaper; the theme's own are the fallback.
        DynamicColors.applyToActivitiesIfAvailable(this)
        LibraryRepository.get(this).schedulePeriodicSync()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(sendShortcut(this))) }
    }
}

private const val SEND_SHORTCUT = "send_from_nextcloud"

/**
 * "Send from Nextcloud" on a long press of the app icon (#58), or pinned to the home screen.
 * Dynamic rather than static, because a static shortcut has to spell out the package, which
 * differs in debug builds.
 */
fun sendShortcut(context: Context): ShortcutInfoCompat = ShortcutInfoCompat.Builder(context, SEND_SHORTCUT)
    .setShortLabel(context.getString(R.string.send_shortcut_short))
    .setLongLabel(context.getString(R.string.send_shortcut_long))
    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_send))
    // A clean task each time, whatever an earlier send left behind.
    .setIntent(
        Intent(context, SendFromNextcloudActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    .build()
