package com.keithvassallo.ncmediaprovider.activation

data class DeviceConfigSetting(
    val namespace: String,
    val key: String,
    val value: String,
)

/** What MediaProvider reports about the cloud picker in `dumpsys` (PLAN 4.6). */
data class PickerState(
    val allowedPackages: List<String>,
    val cloudMediaEnabled: Boolean,
)

/**
 * The DeviceConfig flags that let this app act as the picker's cloud provider, written as local
 * overrides so that `clear_override` restores the phone exactly (see docs/device-notes.md), and the
 * MediaProvider call that selects it.
 *
 * The allow-list is replaced wholesale, so it must name every provider the user wants to keep. The
 * flag itself can't be appended to (on stock Pixels it holds an authority that MediaProvider maps
 * to a package), so the list starts from MediaProvider's effective list instead.
 */
object CloudProviderActivation {
    const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"
    const val GOOGLE_PHOTOS_AUTHORITY = "com.google.android.apps.photos.cloudpicker"

    // `mediaprovider` applies immediately; `storage_native_boot` is read at boot.
    private val NAMESPACES = listOf("mediaprovider", "storage_native_boot")

    /** MediaProvider's module is named differently on Google-signed and AOSP builds. */
    val MEDIA_PROVIDER_COMPONENTS = listOf(
        "com.google.android.providers.media.module/com.android.providers.media.MediaProvider",
        "com.android.providers.media.module/com.android.providers.media.MediaProvider",
    )

    fun authority(packageName: String) = "$packageName.cloudmedia"

    /**
     * Every provider allowed now, Google Photos only if [keepGooglePhotos], and this app last.
     * MediaProvider ignores packages that aren't installed, so naming Google Photos is harmless.
     */
    fun allowList(packageName: String, keepGooglePhotos: Boolean, current: List<String> = emptyList()): String {
        val others = current.filter { it != packageName && it != GOOGLE_PHOTOS_PACKAGE && it.isNotBlank() }
        return (listOfNotNull(GOOGLE_PHOTOS_PACKAGE.takeIf { keepGooglePhotos }) + others + packageName).distinct().joinToString(",")
    }

    fun settings(allowList: String): List<DeviceConfigSetting> = NAMESPACES.flatMap { namespace ->
        listOf(
            DeviceConfigSetting(namespace, "allowed_cloud_providers", allowList),
            DeviceConfigSetting(namespace, "cloud_media_feature_enabled", "true"),
        )
    }

    /** Selects this app as the picker's cloud source; the shell may do this (checked in Phase 4). */
    fun selectArguments(packageName: String) = selectAuthorityArguments(authority(packageName))

    fun selectAuthorityArguments(authority: String) =
        listOf("call", "--uri", "content://media", "--method", "set_cloud_provider", "--extra", "cloud_provider:s:$authority")

    fun enableCommands(packageName: String, keepGooglePhotos: Boolean): String =
        (settings(allowList(packageName, keepGooglePhotos)).map { "adb shell device_config override ${it.namespace} ${it.key} ${it.value}" } +
            "adb shell content ${selectArguments(packageName).joinToString(" ")}").joinToString("\n")

    fun restoreCommands(packageName: String): String = settings(allowList(packageName, true)).joinToString("\n") {
        "adb shell device_config clear_override ${it.namespace} ${it.key}"
    }

    /** Reads [PickerState] from MediaProvider's `dumpsys` output; null if it has no picker section. */
    fun parsePickerState(dumpsys: String): PickerState? {
        val lines = dumpsys.lineSequence().map(String::trim)
        val allowed = lines.firstOrNull { it.startsWith("allowedCloudProviderPackages=") } ?: return null
        val enabled = lines.firstOrNull { it.startsWith("isCloudMediaInPhotoPickerEnabled=") }
        return PickerState(
            allowedPackages = allowed.substringAfter('=').trim('[', ']').split(',').map(String::trim).filter(String::isNotEmpty),
            cloudMediaEnabled = enabled?.substringAfter('=') == "true",
        )
    }
}
