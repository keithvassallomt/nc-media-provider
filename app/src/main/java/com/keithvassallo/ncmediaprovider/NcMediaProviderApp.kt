package com.keithvassallo.ncmediaprovider

import android.app.Application
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
        addSendShortcut()
    }

    /**
     * "Send from Nextcloud" on a long press of the app icon (PLAN 4.7). Dynamic rather than static,
     * because a static shortcut has to spell out the package, which differs in debug builds.
     */
    private fun addSendShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, SEND_SHORTCUT)
            .setShortLabel(getString(R.string.send_shortcut_short))
            .setLongLabel(getString(R.string.send_shortcut_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_send))
            // A clean task each time, whatever an earlier send left behind.
            .setIntent(
                Intent(this, SendFromNextcloudActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(shortcut)) }
    }

    private companion object {
        const val SEND_SHORTCUT = "send_from_nextcloud"
    }
}
