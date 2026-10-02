package com.keithvassallo.ncmediaprovider.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** What `status.php` says, which needs no sign-in. */
data class ServerStatus(
    val installed: Boolean,
    val maintenance: Boolean,
    /** "35.0.1"; empty when the server hides it. */
    val version: String,
) {
    val majorVersion: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
}

/** A started Login Flow v2 (PLAN 4.1): the page to open, and where to poll for the result. */
data class LoginFlow(val loginUrl: String, val pollEndpoint: String, val token: String)

/** What Login Flow v2 hands back once the user approves this app in the browser. */
data class LoginGrant(val server: String, val loginName: String, val appPassword: String)

/** Parsing for the account-level endpoints, kept apart from HTTP so it can be unit-tested. */
internal object ServerApi {
    /**
     * Turns what someone typed into a server base URL: https unless they wrote http, no trailing
     * slash, and none of the page paths people paste from the address bar. Null if it isn't a URL.
     */
    fun normalizeServerUrl(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "https://$text"
        val url = text.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter(String::isNotEmpty).toMutableList()
        val pageStart = segments.indexOfFirst { it == "index.php" || it == "apps" || it == "login" || it == "remote.php" }
        if (pageStart >= 0) segments.subList(pageStart, segments.size).clear()
        val base = url.newBuilder().query(null).fragment(null).encodedPath("/").apply { segments.forEach(::addPathSegment) }.build()
        return base.toString().trimEnd('/')
    }

    /**
     * The base URL to keep after signing in. Nextcloud reports its own address in the grant, which
     * may add a subfolder; behind a proxy that hides https from it, it may also report plain http
     * for the address the user reached over https, which is kept as https.
     */
    fun chooseBaseUrl(typed: String, granted: String): String {
        val typedUrl = typed.toHttpUrlOrNull() ?: return granted.trimEnd('/')
        val grantedUrl = granted.trimEnd('/').toHttpUrlOrNull() ?: return typed
        if (grantedUrl.host != typedUrl.host) return grantedUrl.toString().trimEnd('/')
        val upgraded = if (typedUrl.isHttps && !grantedUrl.isHttps) grantedUrl.newBuilder().scheme("https").port(typedUrl.port).build() else grantedUrl
        return upgraded.toString().trimEnd('/')
    }

    /** The poll endpoint, on https when the server was reached over https (see [chooseBaseUrl]). */
    fun pollUrl(baseUrl: String, endpoint: String): HttpUrl? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val url = endpoint.toHttpUrlOrNull() ?: return null
        return if (base.isHttps && !url.isHttps && url.host == base.host) url.newBuilder().scheme("https").port(base.port).build() else url
    }

    fun parseStatus(json: String): ServerStatus {
        val status = JSONObject(json)
        return ServerStatus(
            installed = status.optBoolean("installed", false),
            maintenance = status.optBoolean("maintenance", false),
            version = status.optString("versionstring", ""),
        )
    }

    fun parseLoginFlow(json: String): LoginFlow {
        val flow = JSONObject(json)
        val poll = flow.getJSONObject("poll")
        return LoginFlow(flow.getString("login"), poll.getString("endpoint"), poll.getString("token"))
    }

    fun parseLoginGrant(json: String): LoginGrant {
        val grant = JSONObject(json)
        return LoginGrant(grant.getString("server"), grant.getString("loginName"), grant.getString("appPassword"))
    }

    /** The user ID from OCS `cloud/user`: WebDAV paths use it, and it may differ from the login name. */
    fun parseUserId(json: String): String = JSONObject(json).getJSONObject("ocs").getJSONObject("data").getString("id")

    /**
     * Memories' timeline folders, from its `api/config`. Memories separates several with ';'; an
     * empty value means the whole account, which is no help as a starting selection.
     */
    fun parseTimelinePaths(json: String): List<String> =
        JSONObject(json).optString("timeline_path", "").split(';').map(String::trim).filter { it.isNotEmpty() && it != "/" }

    fun parseWipe(json: String): Boolean = JSONObject(json).optBoolean("wipe", false)
}
