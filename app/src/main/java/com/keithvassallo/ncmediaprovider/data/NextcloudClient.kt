package com.keithvassallo.ncmediaprovider.data

import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import com.keithvassallo.ncmediaprovider.BuildConfig
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NextcloudHttpException(val statusCode: Int, message: String) : IOException(message)

class NextcloudClient {
    private val client = OkHttpClient.Builder()
        // Credentials go only to the configured server's origin, never to a redirect elsewhere.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            // The network security config permits cleartext so that LAN servers work; this keeps
            // it to them (PLAN 4.2). The check runs once connected, before anything is sent.
            if (!request.url.isHttps) {
                val address = chain.connection()?.route()?.socketAddress?.address
                if (address == null || !isLocalNetworkAddress(address)) throw PlainHttpNotAllowedException()
            }
            val auth = request.tag(ServerAuth::class.java)
            val authenticated = request.newBuilder().removeHeader("Authorization").apply {
                if (auth != null && request.url.hasSameOrigin(auth.origin)) {
                    header("Authorization", auth.credentials)
                }
            }.build()
            chain.proceed(authenticated)
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Lists every file under [folders] whose MIME type starts with one of [mimePrefixes], newest first. */
    internal fun listMedia(
        account: NextcloudAccount,
        folders: List<String>,
        mimePrefixes: List<String>,
        pageSize: Int = LIST_PAGE_SIZE,
        onBatch: (List<RemoteFile>) -> Unit = {},
    ): Listing = listByModifiedWindows(pageSize, onBatch) { filter, limit ->
        search(account, folders, mimePrefixes, filter, limit)
    }

    fun search(
        account: NextcloudAccount,
        folders: List<String>,
        mimePrefixes: List<String>,
        modified: ModifiedFilter?,
        limit: Int,
    ): List<RemoteFile> {
        val body = SearchRequest.body(account.userId, folders, mimePrefixes, modified, limit)
        val request = Request.Builder()
            .url(account.server().newBuilder().addPathSegments("remote.php/dav/").build())
            .method("SEARCH", body.toRequestBody(XML))
            .header("Accept", "application/xml")
        val started = SystemClock.elapsedRealtime()
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            // Read fully first, so the log separates network time from parsing time.
            val bytes = response.body.bytes()
            val received = SystemClock.elapsedRealtime()
            MultistatusParser.parse(bytes.inputStream()).also { files ->
                Log.d(
                    TAG,
                    "SEARCH ${modified?.operator ?: "all"} ${modified?.seconds ?: ""} -> ${files.size} files, " +
                        "${bytes.size / 1024} KiB: network ${received - started} ms, parse ${SystemClock.elapsedRealtime() - received} ms",
                )
            }
        }
    }

    /** A library folder's own entry, whose etag changes whenever anything below it changes. */
    fun folderEntry(account: NextcloudAccount, folder: String): DavEntry {
        val request = Request.Builder()
            .url(account.userFolderUrl(folder))
            .method("PROPFIND", PropfindRequest.ETAG.toRequestBody(XML))
            .header("Depth", "0")
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            MultistatusParser.parseEntries(response.body.byteStream()).firstOrNull()
                ?: throw IOException("No entry for the library folder")
        }
    }

    /** Every folder below [folders], with its etag, in one request. */
    fun listSubfolders(account: NextcloudAccount, folders: List<String>): List<DavEntry> =
        davSearch(account, SearchRequest.folders(account.userId, folders)) { MultistatusParser.parseEntries(it) }
            .filter(DavEntry::isFolder)

    /** File IDs of the favourites below [folders]. */
    fun favoriteIds(account: NextcloudAccount, folders: List<String>, mimePrefixes: List<String>): Set<String> =
        davSearch(account, SearchRequest.favorites(account.userId, folders, mimePrefixes)) { MultistatusParser.parseEntries(it) }
            .mapNotNullTo(HashSet(), DavEntry::fileId)

    /** The [SearchRequest.HIDING_MARKERS] files below [folders]. */
    fun hidingMarkers(account: NextcloudAccount, folders: List<String>): List<DavEntry> =
        davSearch(account, SearchRequest.markers(account.userId, folders)) { MultistatusParser.parseEntries(it) }
            .filterNot(DavEntry::isFolder)

    /**
     * The user's own Nextcloud Photos albums and those shared with them (PLAN 7.1), or null when
     * the Photos app isn't there for this user. An album's files come from [listDirectFiles] on its href.
     */
    fun listAlbums(account: NextcloudAccount): List<PhotosAlbumEntry>? {
        val albums = ArrayList<PhotosAlbumEntry>()
        for ((collection, shared) in listOf("albums" to false, "sharedalbums" to true)) {
            val url = account.server().newBuilder()
                .addPathSegments("remote.php/dav/photos")
                .addPathSegment(account.userId)
                .addPathSegment(collection)
                .addPathSegment("")
                .build()
            val request = Request.Builder().url(url).method("PROPFIND", PropfindRequest.ALBUMS.toRequestBody(XML)).header("Depth", "1")
            execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
                when {
                    // No Photos app, or an older one without shared albums: what was found stands.
                    response.code == 404 || response.code == 405 || response.code == 501 -> return if (shared) albums else null
                    response.code != 207 -> throw response.toException()
                    else -> albums += MultistatusParser.parseAlbums(response.body.byteStream(), shared)
                }
            }
        }
        return albums
    }

    /** The files directly inside one folder (a depth-1 PROPFIND), whose MIME type starts with one of [mimePrefixes]. */
    fun listDirectFiles(account: NextcloudAccount, folderHref: String, mimePrefixes: List<String>): List<RemoteFile> {
        val url = account.server().resolve(folderHref) ?: throw IOException("Unusable folder path from the server")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PropfindRequest.FILES.toRequestBody(XML))
            .header("Depth", "1")
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            MultistatusParser.parse(response.body.byteStream()).filter { file -> mimePrefixes.any(file.mimeType::startsWith) }
        }
    }

    private fun <T> davSearch(account: NextcloudAccount, body: String, parse: (java.io.InputStream) -> T): T {
        val request = Request.Builder()
            .url(account.server().newBuilder().addPathSegments("remote.php/dav/").build())
            .method("SEARCH", body.toRequestBody(XML))
            .header("Accept", "application/xml")
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            parse(response.body.byteStream())
        }
    }

    /**
     * [length] bytes of a file from [offset], for streaming (PLAN 5.2). [etag] pins the version: a
     * file that changes while it is being read fails with 412 instead of mixing two versions.
     */
    fun fetchRange(account: NextcloudAccount, href: String, etag: String, offset: Long, length: Int): ByteArray {
        val url = account.server().resolve(href) ?: throw IOException("Unusable file path from the server")
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            // Photos' album files give their etag without quotes, and the server compares the
            // header with it as it is (PLAN 7.1): quoted, every read through an album failed with 412.
            .apply { if (etag.isNotEmpty()) header("If-Match", if (Albums.isAlbumPath(href)) etag else "\"$etag\"") }
        return execute(request, account, RANGE_TIMEOUT_SECONDS).use { response ->
            when (response.code) {
                206 -> response.body.bytes()
                // A server that ignores Range sends the whole file; only the start of it is usable.
                200 -> if (offset == 0L) response.body.source().readByteArray(length.toLong()) else throw IOException("The server ignored the Range request")
                else -> throw response.toException()
            }
        }
    }

    /**
     * Makes calls signed in as [account], for a player that streams through this client (PLAN
     * 5.4); credentials still go only to the account's server.
     */
    fun callFactory(account: NextcloudAccount): Call.Factory = Call.Factory { request ->
        client.newCall(authenticated(request.newBuilder(), account).build())
    }

    /** The full URL of a file the server listed by [href]. */
    fun fileUrl(account: NextcloudAccount, href: String): HttpUrl? = account.server().resolve(href)

    /** The folders directly inside [folder], for the folder picker (PLAN 4.3). */
    fun listChildFolders(account: NextcloudAccount, folder: String): List<DavEntry> {
        val request = Request.Builder()
            .url(account.userFolderUrl(folder))
            .method("PROPFIND", PropfindRequest.FOLDERS.toRequestBody(XML))
            .header("Depth", "1")
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            val own = folderKey(account.userFolderUrl(folder).encodedPath)
            MultistatusParser.parseEntries(response.body.byteStream()).filter { it.isFolder && folderKey(it.href) != own }
        }
    }

    /** `status.php`: whether a Nextcloud answers at [baseUrl], and its version. Needs no sign-in. */
    fun serverStatus(baseUrl: String): ServerStatus {
        val request = Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegment("status.php").build())
        return client.newCall(request.header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            ServerApi.parseStatus(response.body.string())
        }
    }

    /**
     * Starts Login Flow v2 (PLAN 4.1). Nextcloud names the new app password after the User-Agent,
     * which is what the user later sees in their security settings.
     */
    fun startLogin(baseUrl: String): LoginFlow {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("index.php/login/v2").build())
            .post(FormBody.Builder().build())
            .header("User-Agent", LOGIN_USER_AGENT)
        return client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            ServerApi.parseLoginFlow(response.body.string())
        }
    }

    /** The grant once the user has approved this app, or null while they haven't yet. */
    fun pollLogin(baseUrl: String, flow: LoginFlow): LoginGrant? {
        val url = ServerApi.pollUrl(baseUrl, flow.pollEndpoint) ?: throw IOException("Unusable poll address from the server")
        val request = Request.Builder().url(url).post(FormBody.Builder().add("token", flow.token).build())
        return client.newCall(request.header("User-Agent", LOGIN_USER_AGENT).build()).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw response.toException()
                else -> ServerApi.parseLoginGrant(response.body.string())
            }
        }
    }

    /** The user ID WebDAV paths use; the login name may be an email address (PLAN 4.1). */
    fun userId(account: NextcloudAccount): String {
        val url = account.server().newBuilder().addPathSegments("ocs/v2.php/cloud/user").addQueryParameter("format", "json").build()
        return execute(Request.Builder().url(url).header("Accept", "application/json"), account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (!response.isSuccessful) throw response.toException()
            ServerApi.parseUserId(response.body.string())
        }
    }

    /** Memories' timeline folders, or null when Memories isn't installed (PLAN 4.3). */
    fun memoriesTimelinePaths(account: NextcloudAccount): List<String>? {
        val url = account.server().newBuilder().addPathSegments("index.php/apps/memories/api/config").build()
        return execute(Request.Builder().url(url).header("Accept", "application/json"), account, SEARCH_TIMEOUT_SECONDS).use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw response.toException()
                else -> ServerApi.parseTimelinePaths(response.body.string())
            }
        }
    }

    /**
     * Memories' version from its `api/describe`, or null when Memories isn't installed or this user
     * can't use it (PLAN 6.1). Memories adds nothing to the OCS capabilities (Phase 0.2).
     */
    fun memoriesVersion(account: NextcloudAccount): String? = memoriesGet(account, "describe") { response ->
        when {
            response.code == 404 || response.code == 403 -> null
            !response.isSuccessful -> throw response.toException()
            else -> MemoriesApi.parseVersion(response.body.string())
        }
    }

    /** The days of the user's Memories timeline and how many files each holds (PLAN 6.2). */
    fun memoriesDays(account: NextcloudAccount): Map<Int, Int> = memoriesGet(account, "days") { response ->
        if (!response.isSuccessful) throw response.toException()
        MemoriesApi.parseDays(response.body.string())
    }

    /** Every file Memories shows on [days], read in one request. */
    fun memoriesFiles(account: NextcloudAccount, days: List<Int>): List<MemoriesFile> =
        memoriesGet(account, "days/${days.joinToString(",")}") { response ->
            if (!response.isSuccessful) throw response.toException()
            MemoriesApi.parseFiles(response.body.string())
        }

    private fun <T> memoriesGet(account: NextcloudAccount, path: String, read: (Response) -> T): T {
        val url = account.server().newBuilder().addPathSegments("index.php/apps/memories/api/$path").build()
        return execute(Request.Builder().url(url).header("Accept", "application/json"), account, SEARCH_TIMEOUT_SECONDS).use(read)
    }

    /**
     * Whether the user asked the server to wipe this device (PLAN 4.4). The app password is the
     * token; the call needs no other sign-in, so it still works once the password is refused.
     */
    fun wipeRequested(baseUrl: String, appPassword: String): Boolean {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("index.php/core/wipe/check").build())
            .post(FormBody.Builder().add("token", appPassword).build())
        return client.newCall(request.header("User-Agent", USER_AGENT).build()).execute().use { response ->
            when {
                response.code == 404 -> false
                !response.isSuccessful -> throw response.toException()
                else -> ServerApi.parseWipe(response.body.string())
            }
        }
    }

    /** Tells the server the requested wipe is done, which also deletes the app password. */
    fun confirmWipe(baseUrl: String, appPassword: String) {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("index.php/core/wipe/success").build())
            .post(FormBody.Builder().add("token", appPassword).build())
        client.newCall(request.header("User-Agent", USER_AGENT).build()).execute().close()
    }

    /** Deletes this app's app password on the server, on sign-out (PLAN 4.5). */
    fun revokeAppPassword(account: NextcloudAccount) {
        val url = account.server().newBuilder().addPathSegments("ocs/v2.php/core/apppassword").build()
        execute(Request.Builder().url(url).delete(), account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (!response.isSuccessful) throw response.toException()
        }
    }

    /** Server-rendered preview, already rotated, cropped to cover a [sizePx] square. */
    /**
     * A preview of the file with [fileId]. [viaPhotos] uses the Photos app's endpoint, the one that
     * can see files shared only through an album (PLAN 7.1; core's answers 404). It fits the image
     * inside the box, where core's covers it.
     */
    fun downloadPreview(
        account: NextcloudAccount,
        fileId: String,
        sizePx: Int,
        destination: File,
        cancellationSignal: CancellationSignal?,
        viaPhotos: Boolean = false,
    ) {
        val url = if (viaPhotos) {
            account.server().newBuilder()
                .addPathSegments("index.php/apps/photos/api/v1/preview")
                .addPathSegment(fileId)
                .addQueryParameter("x", sizePx.toString())
                .addQueryParameter("y", sizePx.toString())
                .build()
        } else {
            account.server().newBuilder()
                .addPathSegments("index.php/core/preview")
                .addQueryParameter("fileId", fileId)
                .addQueryParameter("x", sizePx.toString())
                .addQueryParameter("y", sizePx.toString())
                .addQueryParameter("a", "1")
                .addQueryParameter("mode", "cover")
                .addQueryParameter("forceIcon", "0")
                .build()
        }
        download(Request.Builder().url(url).header("Accept", "image/*"), account, destination, cancellationSignal, PREVIEW_TIMEOUT_SECONDS)
    }

    fun downloadFile(
        account: NextcloudAccount,
        href: String,
        destination: File,
        cancellationSignal: CancellationSignal?,
    ) {
        val url = account.server().resolve(href) ?: throw IOException("Unusable file path from the server")
        download(Request.Builder().url(url).header("Accept", "*/*"), account, destination, cancellationSignal, ORIGINAL_TIMEOUT_SECONDS)
    }

    private fun download(
        request: Request.Builder,
        account: NextcloudAccount,
        destination: File,
        cancellationSignal: CancellationSignal?,
        timeoutSeconds: Long,
    ) {
        cancellationSignal?.throwIfCanceled()
        val call = client.newCall(authenticated(request, account).build())
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        cancellationSignal?.setOnCancelListener(call::cancel)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw response.toException()
                destination.parentFile?.mkdirs()
                response.body.byteStream().use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            cancellationSignal?.throwIfCanceled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        } finally {
            cancellationSignal?.setOnCancelListener(null)
        }
    }

    private fun execute(request: Request.Builder, account: NextcloudAccount, timeoutSeconds: Long): Response {
        val call = client.newCall(authenticated(request, account).build())
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        return call.execute()
    }

    private fun authenticated(request: Request.Builder, account: NextcloudAccount): Request.Builder = request
        .tag(ServerAuth::class.java, ServerAuth(account.server(), Credentials.basic(account.loginName, account.appPassword)))
        .header("User-Agent", USER_AGENT)
        .header("OCS-APIRequest", "true")

    private fun Response.toException() = NextcloudHttpException(code, "Server returned HTTP $code")

    private fun NextcloudAccount.server(): HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** `remote.php/dav/files/<user>/<folder>/`, each segment percent-encoded by OkHttp. */
    private fun NextcloudAccount.userFolderUrl(folder: String): HttpUrl = server().newBuilder()
        .addPathSegments("remote.php/dav/files")
        .addPathSegment(userId)
        .apply { folder.split('/').filter(String::isNotEmpty).forEach(::addPathSegment) }
        .addPathSegment("")
        .build()

    private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private data class ServerAuth(val origin: HttpUrl, val credentials: String)

    companion object {
        private const val TAG = "NextcloudClient"
        private val USER_AGENT = "NcMediaProvider/${BuildConfig.VERSION_NAME} Android"

        /** The app password's name in the user's Nextcloud security settings. */
        private const val LOGIN_USER_AGENT = "NC Media Provider (Android)"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val LIST_PAGE_SIZE = 1000

        // Listing runs on a background thread, never on a binder thread the picker waits on.
        private const val SEARCH_TIMEOUT_SECONDS = 60L

        // Thumbnails are requested a screenful at a time and each request occupies a binder
        // thread, so they must give up long before an original would.
        private const val PREVIEW_TIMEOUT_SECONDS = 15L
        private const val ORIGINAL_TIMEOUT_SECONDS = 120L

        // One 2 MiB chunk of a stream; a reader waits for it.
        private const val RANGE_TIMEOUT_SECONDS = 30L
    }
}
