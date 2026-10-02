package com.keithvassallo.ncmediaprovider.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory

/** One entry of a WebDAV multistatus response, with the properties [SearchRequest] asks for. */
data class RemoteFile(
    /** Percent-encoded path from the server root, e.g. `/remote.php/dav/files/alice/Photos/a.jpg`. */
    val href: String,
    val fileId: String,
    val etag: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    /** Nextcloud's "date taken": EXIF for JPEG, otherwise a date in the file name, otherwise null. */
    val originalDateTimeMillis: Long?,
    /** In display orientation; 0 when the server has no metadata for the file. */
    val width: Int,
    val height: Int,
    val isFavorite: Boolean,
    val isHidden: Boolean,
) {
    val fileName: String get() = percentDecode(href.trimEnd('/').substringAfterLast('/'))
}

/** A filter on modification time, in whole seconds (Nextcloud's precision). */
sealed class ModifiedFilter(val operator: String, val seconds: Long) {
    class AtOrBefore(seconds: Long) : ModifiedFilter("lte", seconds)
    class Exactly(seconds: Long) : ModifiedFilter("eq", seconds)
}

/**
 * Builds a WebDAV SEARCH for one folder tree, newest first, optionally filtered on modification
 * time. Listing walks backwards through those windows (see [listByModifiedWindows]): offset paging
 * is unreliable because results can't be ordered by file ID.
 */
object SearchRequest {
    private val PROPS = listOf(
        "oc:fileid", "d:getetag", "d:getcontenttype", "d:getcontentlength", "d:getlastmodified",
        "oc:favorite", "nc:hidden", "nc:metadata-photos-original_date_time", "nc:metadata-photos-size",
    ).joinToString("") { "<$it/>" }

    fun body(
        userId: String,
        folder: String,
        mimePrefix: String,
        modified: ModifiedFilter?,
        limit: Int,
    ): String {
        // The scope is a raw path: Nextcloud answers 404 for a percent-encoded one.
        val scope = "/files/$userId" + if (folder == "/") "" else folder
        val mimeFilter = "<d:like><d:prop><d:getcontenttype/></d:prop><d:literal>${xmlEscape(mimePrefix)}%</d:literal></d:like>"
        val where = if (modified == null) {
            mimeFilter
        } else {
            "<d:and>$mimeFilter<d:${modified.operator}><d:prop><d:getlastmodified/></d:prop>" +
                "<d:literal>${modified.seconds}</d:literal></d:${modified.operator}></d:and>"
        }
        // Without nresults Nextcloud caps the answer at 100. It imposes no upper limit of its own.
        return """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
<d:basicsearch>
<d:select><d:prop>$PROPS</d:prop></d:select>
<d:from><d:scope><d:href>${xmlEscape(scope)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
<d:where>$where</d:where>
<d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
<d:limit><d:nresults>$limit</d:nresults></d:limit>
</d:basicsearch>
</d:searchrequest>"""
    }

    private fun xmlEscape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/**
 * Lists everything [search] can return, newest first, in pages of [pageSize].
 *
 * Results are ordered by modification time, newest first, so a full page holds every file newer
 * than its oldest second; only that second may continue past the page. It is fetched whole with an
 * exact match, and the next page starts strictly below it. Phase 1.5 found 2,677 files sharing one
 * second on a real server, which stalled plain date-window paging. [onBatch] sees each file once, as
 * it arrives, so a first import can commit before the listing ends.
 */
internal fun listByModifiedWindows(
    pageSize: Int,
    onBatch: (List<RemoteFile>) -> Unit = {},
    search: (filter: ModifiedFilter?, limit: Int) -> List<RemoteFile>,
): List<RemoteFile> {
    val files = LinkedHashMap<String, RemoteFile>()
    fun add(batch: List<RemoteFile>) = batch.filter { files.putIfAbsent(it.fileId, it) == null }.also(onBatch)
    var filter: ModifiedFilter? = null
    while (true) {
        val page = search(filter, pageSize)
        add(page)
        if (page.size < pageSize) break
        val oldestSecond = page.minOf { it.lastModifiedMillis } / 1000L
        add(search(ModifiedFilter.Exactly(oldestSecond), WHOLE_SECOND_LIMIT))
        if (oldestSecond <= 0L) break
        filter = ModifiedFilter.AtOrBefore(oldestSecond - 1)
    }
    return files.values.toList()
}

/** Large enough for any one second's worth of files; Nextcloud doesn't cap nresults. */
private const val WHOLE_SECOND_LIMIT = 1_000_000

/**
 * Parses a WebDAV multistatus response as a stream (SAX), so a large listing never sits in memory
 * as a document tree. javax.xml exists both on Android and on the JVM, so the parser is unit-tested
 * against the saved server fixtures in testdata/. Entries without a file ID or MIME type (folders)
 * are skipped.
 */
object MultistatusParser {
    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"
    private const val NC = "http://nextcloud.org/ns"

    fun parse(input: InputStream): List<RemoteFile> = ArrayList<RemoteFile>().also { files ->
        parse(input) { files += it }
    }

    fun parse(input: InputStream, onFile: (RemoteFile) -> Unit) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newSAXParser().parse(input, Handler(onFile))
    }

    private class Handler(private val onFile: (RemoteFile) -> Unit) : DefaultHandler() {
        private val text = StringBuilder()
        private var href: String? = null
        private val props = HashMap<String, String>()
        private val propstatProps = HashMap<String, String>()
        private var propstatOk = false
        private var inProp = false
        private var depthInProp = 0
        private var currentProp: String? = null

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            text.setLength(0)
            when {
                inProp -> {
                    depthInProp++
                    if (depthInProp == 1) currentProp = key(uri, localName)
                }
                uri == DAV && localName == "response" -> {
                    href = null
                    props.clear()
                }
                uri == DAV && localName == "propstat" -> {
                    propstatProps.clear()
                    propstatOk = false
                }
                uri == DAV && localName == "prop" -> {
                    inProp = true
                    depthInProp = 0
                }
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val value = text.toString().trim()
            text.setLength(0)
            when {
                inProp && depthInProp == 0 -> inProp = false // the closing d:prop itself
                inProp -> {
                    when (depthInProp) {
                        1 -> propstatProps[currentProp!!] = value
                        // Structured values such as the photo size have children without a namespace.
                        2 -> propstatProps["$currentProp/$localName"] = value
                    }
                    depthInProp--
                }
                uri == DAV && localName == "href" -> href = value
                uri == DAV && localName == "status" -> propstatOk = value.contains(" 200 ")
                uri == DAV && localName == "propstat" -> if (propstatOk) props.putAll(propstatProps)
                uri == DAV && localName == "response" -> toRemoteFile()?.let(onFile)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        private fun toRemoteFile(): RemoteFile? {
            fun text(namespace: String, name: String) = props[key(namespace, name)]?.takeIf(String::isNotEmpty)
            val size = key(NC, "metadata-photos-size")
            return RemoteFile(
                href = href ?: return null,
                fileId = text(OC, "fileid") ?: return null,
                etag = text(DAV, "getetag")?.trim('"').orEmpty(),
                mimeType = text(DAV, "getcontenttype") ?: return null,
                sizeBytes = text(DAV, "getcontentlength")?.toLongOrNull() ?: 0L,
                lastModifiedMillis = text(DAV, "getlastmodified")?.let(::parseHttpDate) ?: 0L,
                originalDateTimeMillis = text(NC, "metadata-photos-original_date_time")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.times(1000L),
                width = props["$size/width"]?.toIntOrNull() ?: 0,
                height = props["$size/height"]?.toIntOrNull() ?: 0,
                isFavorite = text(OC, "favorite") == "1",
                isHidden = text(NC, "hidden") == "true",
            )
        }
    }

    /**
     * Nextcloud always sends `Thu, 01 Feb 2024 10:00:00 GMT`. Parsed by hand because going through
     * ZonedDateTime cost about 4 ms per file on the phone (Phase 2), most of a listing's time.
     */
    internal fun parseHttpDate(value: String): Long? {
        val parts = value.split(' ')
        if (parts.size == 6 && parts[5] == "GMT") {
            val time = parts[4].split(':')
            val month = MONTHS.indexOf(parts[2]) + 1
            if (month > 0 && time.size == 3) {
                return runCatching {
                    LocalDateTime.of(parts[3].toInt(), month, parts[1].toInt(), time[0].toInt(), time[1].toInt(), time[2].toInt())
                        .toEpochSecond(ZoneOffset.UTC) * 1000L
                }.getOrNull()
            }
        }
        return runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun key(namespace: String?, name: String?) = "${namespace.orEmpty()}|$name"
}

/** Decodes %XX escapes as UTF-8. Unlike URLDecoder it leaves '+' alone, as a path must. */
internal fun percentDecode(value: String): String {
    val bytes = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val decoded = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (decoded != null) {
                bytes.write(decoded)
                i += 3
                continue
            }
        }
        val codePoint = value.codePointAt(i)
        bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
        i += Character.charCount(codePoint)
    }
    return bytes.toString(Charsets.UTF_8.name())
}
