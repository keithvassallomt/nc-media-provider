package com.keithvassallo.ncmediaprovider.data

import android.os.CancellationSignal
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
        return execute(request, account, SEARCH_TIMEOUT_SECONDS).use { response ->
            if (response.code != 207) throw response.toException()
            MultistatusParser.parse(response.body.byteStream())
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

    private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private data class ServerAuth(val origin: HttpUrl, val credentials: String)

    companion object {
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
