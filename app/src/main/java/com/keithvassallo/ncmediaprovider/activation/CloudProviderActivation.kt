package com.keithvassallo.ncmediaprovider.activation

data class DeviceConfigSetting(
    val namespace: String,
    val key: String,
    val value: String,
)

/**
 * The DeviceConfig flags that let this app act as the picker's cloud provider, written as local
 * overrides so that `clear_override` restores the phone exactly (see docs/device-notes.md).
 *
 * The allow-list is replaced wholesale, so it must name every provider the user wants to keep.
 * The flag's current value can't be appended to: on stock Pixels it holds an authority that
 * MediaProvider ignores. PLAN 4.6 refines this (keep Google Photos only when installed, enable
 * cloud media only where it is off, verify through MediaProvider).
 */
object CloudProviderActivation {
    const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"

    // `mediaprovider` applies immediately; `storage_native_boot` is read at boot.
    private val NAMESPACES = listOf("mediaprovider", "storage_native_boot")

    fun allowList(packageName: String): String = "$GOOGLE_PHOTOS_PACKAGE,$packageName"

    fun settings(packageName: String): List<DeviceConfigSetting> = NAMESPACES.flatMap { namespace ->
        listOf(
            DeviceConfigSetting(namespace, "allowed_cloud_providers", allowList(packageName)),
            DeviceConfigSetting(namespace, "cloud_media_feature_enabled", "true"),
        )
    }

    fun enableCommands(packageName: String): String = settings(packageName).joinToString("\n") {
        "adb shell device_config override ${it.namespace} ${it.key} ${it.value}"
    }

    fun restoreCommands(packageName: String): String = settings(packageName).joinToString("\n") {
        "adb shell device_config clear_override ${it.namespace} ${it.key}"
    }
}
