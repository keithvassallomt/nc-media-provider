package com.keithvassallo.ncmediaprovider

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.share.SendFromNextcloudActivity

class NcMediaProviderApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
            .setIntent(Intent(this, SendFromNextcloudActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(shortcut)) }
    }

    private companion object {
        const val SEND_SHORTCUT = "send_from_nextcloud"
    }
}
