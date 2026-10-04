package com.keithvassallo.ncmediaprovider.ui

import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import com.keithvassallo.ncmediaprovider.BuildConfig
import com.keithvassallo.ncmediaprovider.activation.ShellCommand
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.PickerKind
import java.text.DateFormat
import java.util.Date

/**
 * The diagnostics a bug report needs (PLAN 9.5): the app, the phone, the picker and the library,
 * with the app's own recent log lines. Nothing secret goes in: the app password is never read, and
 * the server's address and the user name are masked wherever they appear.
 */
internal object BugReport {
    private val MEDIA_PROVIDER_PACKAGES = listOf("com.google.android.providers.media.module", "com.android.providers.media.module")
    private val LOG_TAGS = listOf(
        "NcCloudMediaProvider", "LibraryRepository", "LibrarySyncWorker", "NextcloudClient",
        "VideoPreview", "PhotoKeyboard", "ProviderReselect", "KeepAlive", "AndroidRuntime",
    )
    private const val LOG_LINES = 400

    /** Builds the report. Call off the main thread: it reads the library and the log. */
    fun build(context: Context, repository: LibraryRepository): String {
        val account = repository.account()
        val activation = PickerActivation.read(context)
        val diagnostics = runCatching { repository.diagnostics() }.getOrNull()
        val memories = runCatching { repository.memoriesStatus() }.getOrNull()
        val mediaProvider = MEDIA_PROVIDER_PACKAGES.firstNotNullOfOrNull { name ->
            runCatching { context.packageManager.getPackageInfo(name, 0) }.getOrNull()?.let { "$name ${it.versionName} (${it.longVersionCode})" }
        } ?: "not found"
        val lines = buildList {
            add("NC Media Provider ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
            add("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            add("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}, patch ${Build.VERSION.SECURITY_PATCH}")
            add("MediaProvider: $mediaProvider")
            add("Picker: ${if (PickerKind.isNewer(context)) "newer (photo picker app)" else "older (inside MediaProvider)"}")
            add("Activation: allowed=${activation.allowed}, selected=${activation.selected}")
            add("Signed in: ${account != null}, ready: ${repository.isReady}")
            if (diagnostics != null) {
                add("Library: ${diagnostics.items} items, generation ${diagnostics.generation}, ${diagnostics.matched} matched to the phone's copies")
                add("Last check: ${if (diagnostics.lastCheckMillis == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(diagnostics.lastCheckMillis))}")
                add("Last error: ${diagnostics.lastError ?: "none"}")
                val cache = diagnostics.cache
                add("Caches: previews ${cache.previews.usedBytes} B, originals ${cache.originals.usedBytes} of ${cache.originals.maximumBytes} B, pre-cache ${cache.precached.usedBytes} B")
            }
            if (memories != null) {
                add("Memories: on=${repository.useMemories}, version=${memories.version ?: "not checked"}, supported=${memories.supported}, dated=${memories.enriched}, live videos=${memories.liveVideos}")
            }
            add("People available: ${repository.peopleAvailable}")
            add("Pre-cache: on=${repository.precacheEnabled}, months=${repository.precacheMonths}, bytes=${repository.precacheBytes}")
            add("Phone photos: full access=${repository.hasFullLocalMediaAccess}")
            add("Photo keyboard enabled: ${isPhotoKeyboardEnabled(context)}")
            add("")
            add("Recent log lines:")
            add(recentLog())
        }
        val secrets = buildMap {
            account?.baseUrl?.let { url ->
                put(url, "<server>")
                url.toUri().host?.let { put(it, "<server>") }
            }
            account?.userId?.let { put(it, "<user>") }
        }
        return redact(lines.joinToString("\n"), secrets)
    }

    /** The app's own log lines under its tags; Android lets an app read only its own. */
    private fun recentLog(): String {
        val filters = LOG_TAGS.map { "$it:V" } + "*:S"
        val result = ShellCommand.run(listOf("logcat", "-d", "-v", "time", "-t", LOG_LINES.toString()) + filters, LOG_TIMEOUT_SECONDS)
        return result.output.ifBlank { "(none)" }
    }

    /**
     * [text] with each key of [secrets] replaced by its value, longest first so a URL goes before the
     * host inside it. A short secret, such as a user name, is matched only as a whole word, so it
     * doesn't break up the app's own package name.
     */
    fun redact(text: String, secrets: Map<String, String>): String =
        secrets.entries.filter { it.key.isNotBlank() }.sortedByDescending { it.key.length }.fold(text) { masked, (secret, label) ->
            masked.replace(Regex("(?<![A-Za-z0-9_])" + Regex.escape(secret) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE), label)
        }

    private const val LOG_TIMEOUT_SECONDS = 5L
}
