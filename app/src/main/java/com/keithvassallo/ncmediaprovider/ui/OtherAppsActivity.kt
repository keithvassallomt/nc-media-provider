package com.keithvassallo.ncmediaprovider.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.isVisible
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.databinding.ActivityOtherAppsBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemGuideStepBinding
import com.keithvassallo.ncmediaprovider.sendShortcut
import com.keithvassallo.ncmediaprovider.share.SendFromNextcloudTileService
import java.text.NumberFormat

/**
 * Apps with their own photo grid, such as Messenger, never open the system picker. This shows
 * the three ways around that, step by step, with what each needs to set up: the Quick Settings
 * tile and the app icon's shortcut for Send from Nextcloud (#58), and the photo keyboard (#60).
 */
class OtherAppsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOtherAppsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtherAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        steps(binding.tileSteps, R.array.other_apps_tile_steps)
        steps(binding.shortcutSteps, R.array.other_apps_shortcut_steps)
        steps(binding.keyboardSteps, R.array.other_apps_keyboard_steps)

        binding.addTileButton.setOnClickListener { addTile() }
        binding.pinShortcutButton.isVisible = ShortcutManagerCompat.isRequestPinShortcutSupported(this)
        binding.pinShortcutButton.setOnClickListener { ShortcutManagerCompat.requestPinShortcut(this, sendShortcut(this), null) }
        binding.keyboardSettingsButton.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's keyboard settings, the keyboard may have been switched on or off.
        binding.keyboardStatus.setText(if (isPhotoKeyboardEnabled(this)) R.string.other_apps_keyboard_on else R.string.other_apps_keyboard_off)
    }

    private fun steps(container: LinearLayout, steps: Int) {
        val numbers = NumberFormat.getIntegerInstance()
        resources.getTextArray(steps).forEachIndexed { i, step ->
            val row = ItemGuideStepBinding.inflate(layoutInflater, container, true)
            row.number.text = numbers.format(i + 1)
            row.text.text = step
        }
    }

    /** Android's own prompt to add the tile, which also says when it's there already. */
    private fun addTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, SendFromNextcloudTileService::class.java),
            getString(R.string.send_shortcut_short),
            Icon.createWithResource(this, R.drawable.ic_send),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.other_apps_tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.other_apps_tile_already_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null // They said no.
                else -> R.string.other_apps_tile_failed
            }
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }
}
