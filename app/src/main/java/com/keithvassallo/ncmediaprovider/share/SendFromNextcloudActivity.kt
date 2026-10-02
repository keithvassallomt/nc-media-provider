package com.keithvassallo.ncmediaprovider.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.keithvassallo.ncmediaprovider.R

/**
 * "Send from Nextcloud" (PLAN 4.7). Apps with their own photo grid, such as Messenger, never open
 * the system picker, so cloud photos can't appear in them. This opens the picker itself, then hands
 * the picked items to the share sheet, from where they reach any app that accepts shares. The
 * picker's read grant travels with the share; the receiving app reads the original through the
 * picker, which downloads it from Nextcloud.
 *
 * The picker grants read access to this activity, and Android revokes it when the activity
 * finishes. The share sheet starts the chosen app on this app's behalf, so this activity stays
 * (invisibly) until the share sheet closes; finishing straight away made every share fail with
 * "does not have permission" (Phase 4 exit test). The chosen app opens in its own task: in this
 * one it stayed behind after the share, and the next "Send from Nextcloud" brought it back.
 */
class SendFromNextcloudActivity : ComponentActivity() {
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) finish() else share(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change the picker is already open and its result still arrives.
        if (savedInstanceState == null) {
            runCatching { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                .onFailure {
                    Toast.makeText(this, R.string.picker_unavailable, Toast.LENGTH_LONG).show()
                    finish()
                }
        }
    }

    private fun share(uris: List<Uri>) {
        val types = uris.map { contentResolver.getType(it) ?: "application/octet-stream" }
        val share = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        share.type = commonType(types)
        // The grant covers what the ClipData names; EXTRA_STREAM alone isn't enough for every target.
        share.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { chooser.launch(Intent.createChooser(share, getString(R.string.send_chooser_title))) }.onFailure { finish() }
    }

    private companion object {
        /** "image/jpeg" when all match, "image/\*" for mixed images, otherwise any type. */
        fun commonType(types: List<String>): String = when {
            types.distinct().size == 1 -> types.first()
            types.map { it.substringBefore('/') }.distinct().size == 1 -> types.first().substringBefore('/') + "/*"
            else -> "*/*"
        }
    }
}
