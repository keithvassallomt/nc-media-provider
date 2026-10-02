package com.keithvassallo.ncmediaprovider.activation

import android.content.Context
import android.os.RemoteException
import androidx.annotation.Keep
import com.keithvassallo.ncmediaprovider.BuildConfig
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class DeviceConfigUserService() : IActivationService.Stub() {
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    /**
     * Writes the allow-list and feature flag as local overrides, starting from MediaProvider's
     * effective list so other providers stay allowed, then checks the result through MediaProvider
     * itself rather than `device_config get` (PLAN 4.6). There is deliberately no fallback to
     * `put`: a `put` value can be overwritten by the server sync, and `clear_override` would not
     * undo it.
     */
    override fun activate(keepGooglePhotos: Boolean): String {
        val packageName = BuildConfig.APPLICATION_ID
        val before = readPickerState()
        val allowList = CloudProviderActivation.allowList(packageName, keepGooglePhotos, before?.allowedPackages.orEmpty())
        apply(CloudProviderActivation.settings(allowList), "override")?.let { failure ->
            throw RemoteException("DeviceConfig activation failed: $failure")
        }
        // MediaProvider picks up DeviceConfig changes through a listener, so give it a moment.
        repeat(VERIFY_ATTEMPTS) {
            val state = readPickerState()
            if (state != null && packageName in state.allowedPackages) {
                return if (state.cloudMediaEnabled) RESULT_ACTIVE else RESULT_RESTART
            }
            Thread.sleep(VERIFY_INTERVAL_MS)
        }
        throw RemoteException("MediaProvider doesn't list this app as allowed after the change")
    }

    /** `content call ... set_cloud_provider`, which the shell may make, then a check that it took. */
    override fun selectProvider(): Boolean {
        val packageName = BuildConfig.APPLICATION_ID
        val set = run(CONTENT_BINARY, *CloudProviderActivation.selectArguments(packageName).toTypedArray())
        if (!set.successful || !set.output.contains("set_cloud_provider_result=true")) return false
        val check = run(CONTENT_BINARY, "call", "--uri", "content://media", "--method", "get_cloud_provider")
        return check.output.contains("=${CloudProviderActivation.authority(packageName)}")
    }

    private fun readPickerState(): PickerState? = CloudProviderActivation.MEDIA_PROVIDER_COMPONENTS.firstNotNullOfOrNull { component ->
        CloudProviderActivation.parsePickerState(run(DUMPSYS_BINARY, "activity", "provider", component).output)
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun apply(settings: List<DeviceConfigSetting>, operation: String): String? {
        settings.forEach { setting ->
            val result = runDeviceConfig(
                operation,
                setting.namespace,
                setting.key,
                setting.value,
            )
            if (!result.successful) {
                val detail = result.output.take(180).ifBlank { "exit code ${result.exitCode}" }
                return "${setting.namespace}/${setting.key}: $detail"
            }
        }
        return null
    }

    private fun runDeviceConfig(vararg arguments: String): CommandResult = run(DEVICE_CONFIG_BINARY, *arguments)

    private fun run(binary: String, vararg arguments: String): CommandResult {
        val process = try {
            ProcessBuilder(listOf(binary, *arguments))
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            return CommandResult(-1, error.message.orEmpty(), false)
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return CommandResult(-1, "command timed out", false)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val failedInOutput = listOf("error", "unknown command", "invalid command")
            .any { output.contains(it, ignoreCase = true) }
        return CommandResult(process.exitValue(), output, process.exitValue() == 0 && !failedInOutput)
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val successful: Boolean,
    )

    companion object {
        private const val DEVICE_CONFIG_BINARY = "/system/bin/device_config"
        private const val CONTENT_BINARY = "/system/bin/content"
        private const val DUMPSYS_BINARY = "/system/bin/dumpsys"
        private const val VERIFY_ATTEMPTS = 10
        private const val VERIFY_INTERVAL_MS = 300L

        const val RESULT_ACTIVE = "active"
        const val RESULT_RESTART = "restart"
        private const val COMMAND_TIMEOUT_SECONDS = 10L
    }
}
