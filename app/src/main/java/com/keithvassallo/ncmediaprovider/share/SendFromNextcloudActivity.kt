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
 */
class SendFromNextcloudActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) share(uris)
        finish()
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
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, getString(R.string.send_chooser_title)))
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
