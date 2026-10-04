package com.keithvassallo.ncmediaprovider.ui

import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turning the pre-cache off deletes every thumbnail it downloaded (#59), which can take hours
 * of Wi-Fi to fetch again, so it asks first; with nothing downloaded it goes ahead. [onDecided]
 * gets whether to turn it off.
 */
internal fun AppCompatActivity.confirmPrecacheOff(repository: LibraryRepository, onDecided: (turnOff: Boolean) -> Unit) {
    lifecycleScope.launch {
        val used = withContext(Dispatchers.IO) { runCatching { repository.cacheStats().precached.usedBytes }.getOrDefault(0L) }
        if (used <= 0L) {
            onDecided(true)
            return@launch
        }
        var decided = false
        val decide = { turnOff: Boolean ->
            if (!decided) {
                decided = true
                onDecided(turnOff)
            }
        }
        MaterialAlertDialogBuilder(this@confirmPrecacheOff)
            .setTitle(R.string.precache_off_title)
            .setMessage(getString(R.string.precache_off_message, Formatter.formatShortFileSize(this@confirmPrecacheOff, used)))
            .setNegativeButton(android.R.string.cancel) { _, _ -> decide(false) }
            .setPositiveButton(R.string.precache_off_confirm) { _, _ -> decide(true) }
            .setOnDismissListener { decide(false) }
            .show()
    }
}
