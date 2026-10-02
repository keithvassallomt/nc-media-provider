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
     * Writes the flags as local overrides. There is deliberately no fallback to `put`: a `put`
     * value can be overwritten by the server sync, and `clear_override` would not undo it.
     */
    override fun activate(): String {
        val settings = CloudProviderActivation.settings(BuildConfig.APPLICATION_ID)
        applyAndVerify(settings, "override")?.let { failure ->
            throw RemoteException("DeviceConfig activation failed: $failure")
        }
        return "override"
    }

    private fun applyAndVerify(
        settings: List<DeviceConfigSetting>,
        operation: String,
    ): String? {
        apply(settings, operation)?.let { return it }
        settings.forEach { setting ->
            val result = runDeviceConfig("get", setting.namespace, setting.key)
            val actual = result.output.lineSequence().lastOrNull()?.trim().orEmpty()
            if (!result.successful || actual != setting.value) {
                return "could not verify ${setting.namespace}/${setting.key}"
            }
        }
        return null
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

    private fun runDeviceConfig(vararg arguments: String): CommandResult {
        val process = try {
            ProcessBuilder(listOf(DEVICE_CONFIG_BINARY, *arguments))
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
        private const val COMMAND_TIMEOUT_SECONDS = 10L
    }
}
