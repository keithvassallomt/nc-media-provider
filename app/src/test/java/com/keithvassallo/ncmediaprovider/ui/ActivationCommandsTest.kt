package com.keithvassallo.ncmediaprovider.ui

import com.keithvassallo.ncmediaprovider.activation.CloudProviderActivation
import com.keithvassallo.ncmediaprovider.activation.PickerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationCommandsTest {
    private val packageName = "com.keithvassallo.ncmediaprovider.debug"

    @Test
    fun `activation writes the allow-list to both namespaces, then selects the app`() {
        val commands = ActivationCommands.enable(packageName).lines()

        assertEquals(
            listOf(
                "adb shell device_config override mediaprovider allowed_cloud_providers com.google.android.apps.photos,$packageName",
                "adb shell device_config override mediaprovider cloud_media_feature_enabled true",
                "adb shell device_config override storage_native_boot allowed_cloud_providers com.google.android.apps.photos,$packageName",
                "adb shell device_config override storage_native_boot cloud_media_feature_enabled true",
                "adb shell content call --uri content://media --method set_cloud_provider --extra cloud_provider:s:$packageName.cloudmedia",
            ),
            commands,
        )
    }

    @Test
    fun `Google Photos stays only if the user wants it, and other providers are kept`() {
        assertEquals(packageName, CloudProviderActivation.allowList(packageName, keepGooglePhotos = false))
        assertEquals(
            "com.google.android.apps.photos,com.example.other,$packageName",
            CloudProviderActivation.allowList(packageName, true, listOf("com.example.other", packageName)),
        )
        assertEquals(
            "com.example.other,$packageName",
            CloudProviderActivation.allowList(packageName, false, listOf("com.google.android.apps.photos", "com.example.other")),
        )
    }

    @Test
    fun `activation never reboots or falls back to put`() {
        val commands = ActivationCommands.enable(packageName)

        assertFalse(commands.contains("reboot"))
        assertFalse(commands.contains(" put "))
    }

    @Test
    fun `restore clears every flag activation writes`() {
        val written = ActivationCommands.enable(packageName).lines().filter { "device_config" in it }
            .map { it.split(" ").let { parts -> parts[4] to parts[5] } }
        val cleared = ActivationCommands.restore(packageName).lines()
            .map { it.split(" ").let { parts -> parts[4] to parts[5] } }

        assertEquals(written, cleared)
        assertEquals(4, ActivationCommands.restore(packageName).lines().count { it.contains("clear_override") })
    }

    @Test
    fun `picker state comes from MediaProvider's dumpsys`() {
        val dumpsys = """
            |      Picker config:
            |        isCloudMediaInPhotoPickerEnabled=true
            |        defaultCloudProviderPackage=
            |        allowedCloudProviderPackages=[com.google.android.apps.photos, $packageName]
            |        shouldEnforceCloudProviderAllowlist=true
        """.trimMargin()
        assertEquals(
            PickerState(listOf("com.google.android.apps.photos", packageName), cloudMediaEnabled = true),
            CloudProviderActivation.parsePickerState(dumpsys),
        )
        assertEquals(
            PickerState(emptyList(), cloudMediaEnabled = false),
            CloudProviderActivation.parsePickerState("isCloudMediaInPhotoPickerEnabled=false\nallowedCloudProviderPackages=[]"),
        )
        assertNull(CloudProviderActivation.parsePickerState("no picker here"))
    }
}
