package com.keithvassallo.ncmediaprovider.ui

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.edit
import com.keithvassallo.ncmediaprovider.BuildConfig
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.activation.CloudProviderActivation

/** The setup guide and the privacy policy on GitHub, linked from onboarding and the home screen. */
object Links {
    const val GUIDE = "https://github.com/keithvassallomt/nc-media-provider/blob/main/docs/setup-guide.md"
    const val PRIVACY = "https://github.com/keithvassallomt/nc-media-provider/blob/main/docs/privacy.md"
    const val GUIDE_SHIZUKU = "$GUIDE#shizuku"
    const val GUIDE_COMPUTER = "$GUIDE#computer"
}

/** Whether Android lets this app act as the picker's cloud source, and whether it is the one selected. */
data class PickerActivation(val allowed: Boolean, val selected: Boolean) {
    companion object {
        fun read(context: Context): PickerActivation {
            val authority = CloudProviderActivation.authority(BuildConfig.APPLICATION_ID)
            val allowed = runCatching { MediaStore.isSupportedCloudMediaProviderAuthority(context.contentResolver, authority) }.getOrDefault(false)
            val selected = allowed && runCatching { MediaStore.isCurrentCloudMediaProviderAuthority(context.contentResolver, authority) }.getOrDefault(false)
            return PickerActivation(allowed, selected)
        }
    }
}

/** Small settings that belong to the screens rather than the library. */
class UiPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    val googlePhotosInstalled: Boolean
        get() = runCatching { appContext.packageManager.getPackageInfo(CloudProviderActivation.GOOGLE_PHOTOS_PACKAGE, 0) }.isSuccess

    /** Keep Google Photos on the allow-list: on unless turned off, and only when it is installed (PLAN 4.6). */
    var keepGooglePhotos: Boolean
        get() = googlePhotosInstalled && preferences.getBoolean(KEY_KEEP_GOOGLE_PHOTOS, true)
        set(value) = preferences.edit { putBoolean(KEY_KEEP_GOOGLE_PHOTOS, value) }

    /** Set when the user taps Finish in onboarding; a phone set up before onboarding existed counts as done. */
    var onboardingDone: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = preferences.edit { putBoolean(KEY_ONBOARDING_DONE, value) }

    private companion object {
        const val KEY_KEEP_GOOGLE_PHOTOS = "keep_google_photos"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}

/** Whether the photo keyboard (PLAN 4.8) is turned on in Android's keyboard settings. */
fun isPhotoKeyboardEnabled(context: Context): Boolean =
    context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName }

/** Opens the system picker; the result isn't needed. */
fun Context.openSystemPicker() {
    runCatching { startActivity(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")) }
        .onFailure { Toast.makeText(this, R.string.picker_unavailable, Toast.LENGTH_LONG).show() }
}

/** The picker's cloud settings, where the user chooses between cloud sources. */
fun Context.openPickerSettings() {
    runCatching { startActivity(Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS)) }
        .onFailure { Toast.makeText(this, R.string.picker_settings_unavailable, Toast.LENGTH_LONG).show() }
}

fun Context.openLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
}
