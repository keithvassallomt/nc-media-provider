package com.keithvassallo.ncmediaprovider.activation

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a command in the activation service (PLAN 4.6). The output is read while the command runs:
 * reading it only after it exited deadlocked on MediaProvider's `dumpsys`, 108 KB on a stock Pixel,
 * which filled the pipe and blocked until the timeout killed it (stock Pixel test, 2026-10-03).
 */
internal object ShellCommand {
    data class Result(val exitCode: Int, val output: String, val successful: Boolean)

    fun run(command: List<String>, timeoutSeconds: Long): Result {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return Result(-1, error.message.orEmpty(), false)
        }
        val output = StringBuilder()
        val reader = thread(isDaemon = true, name = "shell-output") {
            runCatching { process.inputStream.bufferedReader().use { text -> synchronized(output) { output.append(text.readText()) } } }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(READER_GRACE_MS)
            return Result(-1, "command timed out", false)
        }
        reader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
        val text = synchronized(output) { output.toString() }.trim()
        val failedInOutput = listOf("error", "unknown command", "invalid command").any { text.contains(it, ignoreCase = true) }
        return Result(process.exitValue(), text, process.exitValue() == 0 && !failedInOutput)
    }

    private const val READER_GRACE_MS = 1_000L
}
