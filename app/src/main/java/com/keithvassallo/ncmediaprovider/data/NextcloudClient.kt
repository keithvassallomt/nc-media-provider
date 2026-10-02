package com.keithvassallo.ncmediaprovider.data

import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import com.keithvassallo.ncmediaprovider.BuildConfig
import okhttp3.Credentials
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

    /** Lists every file under [folder] whose MIME type starts with [mimePrefix], newest first. */
    fun listFolder(
        account: NextcloudAccount,
        folder: String,
        mimePrefix: String,
        pageSize: Int = LIST_PAGE_SIZE,
        onBatch: (List<RemoteFile>) -> Unit = {},
    ): List<RemoteFile> = listByModifiedWindows(pageSize, onBatch) { filter, limit ->
        search(account, folder, mimePrefix, filter, limit)
    }

    fun search(
        account: NextcloudAccount,
        folder: String,
        mimePrefix: String,
        modified: ModifiedFilter?,
        limit: Int,
    ): List<RemoteFile> {
        val body = SearchRequest.body(account.userId, folder, mimePrefix, modified, limit)
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

    /** The library root's own entry, whose etag changes whenever anything below it changes. */
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

    /** Every folder below [folder], with its etag, in one request. */
    fun listFolders(account: NextcloudAccount, folder: String): List<DavEntry> =
        davSearch(account, SearchRequest.folders(account.userId, folder)) { MultistatusParser.parseEntries(it) }
            .filter(DavEntry::isFolder)

    /** File IDs of the favourites below [folder]. */
    fun favoriteIds(account: NextcloudAccount, folder: String, mimePrefix: String): Set<String> =
        davSearch(account, SearchRequest.favorites(account.userId, folder, mimePrefix)) { MultistatusParser.parseEntries(it) }
            .mapNotNullTo(HashSet(), DavEntry::fileId)

    /** The files directly inside one folder (a depth-1 PROPFIND), whose MIME type starts with [mimePrefix]. */
    fun listDirectFiles(account: NextcloudAccount, folderHref: String, mimePrefix: String): List<RemoteFile> {
        val url = account.server().resolve(folderHref) ?: throw IOException("Unusable folder path from the server")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PropfindRequest.FILES.toRequestBody(XML))
            .header("Depth", "1")
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            MultistatusParser.parse(response.body.byteStream()).filter { it.mimeType.startsWith(mimePrefix) }
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

    /** Server-rendered preview, already rotated, cropped to cover a [sizePx] square. */
    fun downloadPreview(
        account: NextcloudAccount,
        fileId: String,
        sizePx: Int,
        destination: File,
        cancellationSignal: CancellationSignal?,
    ) {
        val url = account.server().newBuilder()
            .addPathSegments("index.php/core/preview")
            .addQueryParameter("fileId", fileId)
            .addQueryParameter("x", sizePx.toString())
            .addQueryParameter("y", sizePx.toString())
            .addQueryParameter("a", "1")
            .addQueryParameter("mode", "cover")
            .addQueryParameter("forceIcon", "0")
            .build()
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
        .header("User-Agent", "NcMediaProvider/${BuildConfig.VERSION_NAME} Android")
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
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val LIST_PAGE_SIZE = 1000

        // Listing runs on a background thread, never on a binder thread the picker waits on.
        private const val SEARCH_TIMEOUT_SECONDS = 60L

        // Thumbnails are requested a screenful at a time and each request occupies a binder
        // thread, so they must give up long before an original would.
        private const val PREVIEW_TIMEOUT_SECONDS = 15L
        private const val ORIGINAL_TIMEOUT_SECONDS = 120L
    }
}
