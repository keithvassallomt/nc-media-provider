package com.keithvassallo.ncmediaprovider.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ActivationCommandsTest {
    private val packageName = "com.keithvassallo.ncmediaprovider.debug"

    @Test
    fun `activation keeps Google Photos on the allow-list in both namespaces`() {
        val commands = ActivationCommands.enable(packageName).lines()

        assertEquals(
            listOf(
                "adb shell device_config override mediaprovider allowed_cloud_providers com.google.android.apps.photos,$packageName",
                "adb shell device_config override mediaprovider cloud_media_feature_enabled true",
                "adb shell device_config override storage_native_boot allowed_cloud_providers com.google.android.apps.photos,$packageName",
                "adb shell device_config override storage_native_boot cloud_media_feature_enabled true",
            ),
            commands,
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
        val written = ActivationCommands.enable(packageName).lines()
            .map { it.split(" ").let { parts -> parts[4] to parts[5] } }
        val cleared = ActivationCommands.restore(packageName).lines()
            .map { it.split(" ").let { parts -> parts[4] to parts[5] } }

        assertEquals(written, cleared)
        assertEquals(4, ActivationCommands.restore(packageName).lines().count { it.contains("clear_override") })
    }
}
