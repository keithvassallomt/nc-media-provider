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
 * Builds WebDAV SEARCH requests over the library folders. One request covers every folder, and
 * Nextcloud orders and limits the results across all of them (checked on 33 and 35). File listings
 * come newest first, optionally filtered on modification time, and listing walks backwards through
 * those windows (see [listByModifiedWindows]): offset paging is unreliable because results can't be
 * ordered by file ID.
 */
object SearchRequest {
    private val FILE_PROPS = listOf(
        "oc:fileid", "d:getetag", "d:getcontenttype", "d:getcontentlength", "d:getlastmodified",
        "oc:favorite", "nc:hidden", "nc:metadata-photos-original_date_time", "nc:metadata-photos-size",
    ).joinToString("") { "<$it/>" }

    /**
     * Marker files that hide their folder, and everything below it, from Nextcloud Photos and
     * Memories. The app hides those folders too unless the user turns that off (#13).
     */
    val HIDING_MARKERS = listOf(".nomedia", ".noimage", ".nomemories")

    /** Files under [folders] whose MIME type starts with one of [mimePrefixes], newest first. */
    fun body(
        userId: String,
        folders: List<String>,
        mimePrefixes: List<String>,
        modified: ModifiedFilter?,
        limit: Int,
    ): String {
        val where = if (modified == null) {
            mimeFilter(mimePrefixes)
        } else {
            "<d:and>${mimeFilter(mimePrefixes)}<d:${modified.operator}><d:prop><d:getlastmodified/></d:prop>" +
                "<d:literal>${modified.seconds}</d:literal></d:${modified.operator}></d:and>"
        }
        val orderBy = "<d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order>"
        return search(FILE_PROPS, userId, folders, where, orderBy, limit)
    }

    /** Every folder under [folders] with its etag, in one request (#15). Not the folders themselves. */
    fun folders(userId: String, folders: List<String>): String = search(
        "<d:getetag/><d:resourcetype/>", userId, folders,
        "<d:eq><d:prop><d:getcontenttype/></d:prop><d:literal>httpd/unix-directory</d:literal></d:eq>",
        orderBy = "", limit = UNLIMITED,
    )

    /** File IDs of favourites under [folders]. Favouriting changes no etag, so it is checked apart. */
    fun favorites(userId: String, folders: List<String>, mimePrefixes: List<String>): String = search(
        "<oc:fileid/>", userId, folders,
        "<d:and><d:eq><d:prop><oc:favorite/></d:prop><d:literal>1</d:literal></d:eq>${mimeFilter(mimePrefixes)}</d:and>",
        orderBy = "", limit = UNLIMITED,
    )

    /** The [HIDING_MARKERS] under [folders]. */
    fun markers(userId: String, folders: List<String>): String = search(
        "<d:resourcetype/>", userId, folders,
        HIDING_MARKERS.joinToString("", "<d:or>", "</d:or>") {
            "<d:eq><d:prop><d:displayname/></d:prop><d:literal>${xmlEscape(it)}</d:literal></d:eq>"
        },
        orderBy = "", limit = UNLIMITED,
    )

    private fun search(select: String, userId: String, folders: List<String>, where: String, orderBy: String, limit: Int): String {
        // Each scope is a raw path: Nextcloud answers 404 for a percent-encoded one.
        val scopes = folders.joinToString("") { folder ->
            val scope = "/files/$userId" + if (folder == "/") "" else folder
            "<d:scope><d:href>${xmlEscape(scope)}</d:href><d:depth>infinity</d:depth></d:scope>"
        }
        // Without nresults Nextcloud caps the answer at 100. It imposes no upper limit of its own.
        return """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest $NAMESPACES>
<d:basicsearch>
<d:select><d:prop>$select</d:prop></d:select>
<d:from>$scopes</d:from>
<d:where>$where</d:where>
<d:orderby>$orderBy</d:orderby>
<d:limit><d:nresults>$limit</d:nresults></d:limit>
</d:basicsearch>
</d:searchrequest>"""
    }

    private fun mimeFilter(mimePrefixes: List<String>): String {
        val likes = mimePrefixes.map {
            "<d:like><d:prop><d:getcontenttype/></d:prop><d:literal>${xmlEscape(it)}%</d:literal></d:like>"
        }
        return likes.singleOrNull() ?: likes.joinToString("", "<d:or>", "</d:or>")
    }

    private const val UNLIMITED = 1_000_000

    internal fun xmlEscape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/** PROPFIND bodies: a folder's own etag (depth 0), the files directly inside it, and its subfolders (depth 1). */
object PropfindRequest {
    val ETAG = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind $NAMESPACES><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>"""

    val FILES = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind $NAMESPACES><d:prop><oc:fileid/><d:getetag/><d:getcontenttype/><d:getcontentlength/>
<d:getlastmodified/><oc:favorite/><nc:hidden/><nc:metadata-photos-original_date_time/>
<nc:metadata-photos-size/><d:resourcetype/></d:prop></d:propfind>"""

    /** Nextcloud Photos albums: how many files, the cover and the date range (#43). */
    val ALBUMS = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind $NAMESPACES><d:prop><nc:nbItems/><nc:last-photo/><nc:dateRange/></d:prop></d:propfind>"""

    /** Folder entries for the folder picker; end-to-end encrypted folders are flagged. */
    val FOLDERS = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind $NAMESPACES><d:prop><d:resourcetype/><oc:fileid/><nc:is-encrypted/></d:prop></d:propfind>"""
}

private const val NAMESPACES = """xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns""""

/** A folder or file seen in a listing, with just enough to compare it (#15). */
data class DavEntry(
    val href: String,
    val etag: String,
    val isFolder: Boolean,
    val fileId: String?,
    /** End-to-end encrypted: the server can't read it, so it can't list or preview its photos. */
    val isEncrypted: Boolean = false,
) {
    val name: String get() = percentDecode(href.trimEnd('/').substringAfterLast('/'))
}

/** Decoded folder path ending in '/': the key folders and their files are matched on (#15). */
internal fun folderKey(folderHref: String): String = percentDecode(folderHref).let { if (it.endsWith('/')) it else "$it/" }

/** [folderKey] of the folder holding a file. */
internal fun parentFolderKey(fileHref: String): String =
    percentDecode(fileHref).trimEnd('/').substringBeforeLast('/') + "/"

/** Whether the folder with key [folder] is one of [folders] or inside one of them. */
internal fun isInsideAny(folder: String, folders: Collection<String>): Boolean = folders.any(folder::startsWith)

/**
 * A full listing. Each file is in it once, although Nextcloud shows a file at two paths when it is
 * reachable through two mounts (a share and a share of one of its subfolders, say): the
 * alphabetically first path wins, so the choice doesn't flip between listings. [duplicates] counts
 * the files seen at more than one path (#13).
 */
internal class Listing(val files: List<RemoteFile>, val duplicates: Int)

/**
 * Lists everything [search] can return, newest first, in pages of [pageSize].
 *
 * Results are ordered by modification time, newest first, so a full page holds every file newer
 * than its oldest second; only that second may continue past the page. It is fetched whole with an
 * exact match, and the next page starts strictly below it. Phase 1.5 found 2,677 files sharing one
 * second on a real server, which stalled plain date-window paging. [onBatch] sees each file as it
 * arrives, so a first import can commit before the listing ends; a file comes again only when a
 * path that wins (see [Listing]) turns up.
 */
internal fun listByModifiedWindows(
    pageSize: Int,
    onBatch: (List<RemoteFile>) -> Unit = {},
    search: (filter: ModifiedFilter?, limit: Int) -> List<RemoteFile>,
): Listing {
    val files = LinkedHashMap<String, RemoteFile>()
    val duplicates = HashSet<String>()
    fun add(batch: List<RemoteFile>) = batch.filter { file ->
        val kept = files[file.fileId]
        if (kept != null && kept.href != file.href) duplicates += file.fileId
        (kept == null || file.href < kept.href).also { wins -> if (wins) files[file.fileId] = file }
    }.also(onBatch)
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
    return Listing(files.values.toList(), duplicates.size)
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

    /** The files in a response. Folders and other entries without a file ID or MIME type are skipped. */
    fun parse(input: InputStream): List<RemoteFile> = ArrayList<RemoteFile>().also { files ->
        parse(input) { files += it }
    }

    fun parse(input: InputStream, onFile: (RemoteFile) -> Unit) =
        parseResponses(input) { href, props -> toRemoteFile(href, props)?.let(onFile) }

    /** Every entry in a response, folders included, with its etag. */
    fun parseEntries(input: InputStream): List<DavEntry> = ArrayList<DavEntry>().also { entries ->
        parseResponses(input) { href, props ->
            entries += DavEntry(
                href = href,
                etag = props[key(DAV, "getetag")]?.trim('"').orEmpty(),
                isFolder = key(DAV, "resourcetype") + "/collection" in props,
                fileId = props[key(OC, "fileid")]?.takeIf(String::isNotEmpty),
                isEncrypted = props[key(NC, "is-encrypted")] == "1",
            )
        }
    }

    /** The albums in a Photos `albums` or `sharedalbums` listing; the listed collection itself is skipped. */
    fun parseAlbums(input: InputStream, isShared: Boolean): List<PhotosAlbumEntry> = ArrayList<PhotosAlbumEntry>().also { albums ->
        var first = true
        parseResponses(input) { href, props ->
            // The first response is the albums collection itself.
            if (first) {
                first = false
                return@parseResponses
            }
            albums += PhotosAlbumEntry(
                href = href,
                itemCount = props[key(NC, "nbItems")]?.toIntOrNull() ?: 0,
                lastPhotoId = props[key(NC, "last-photo")]?.takeIf { it.isNotEmpty() && it != "-1" },
                dateRange = props[key(NC, "dateRange")].orEmpty(),
                isShared = isShared,
            )
        }
    }

    private fun parseResponses(input: InputStream, onResponse: (href: String, props: Map<String, String>) -> Unit) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newSAXParser().parse(input, Handler(onResponse))
    }

    private class Handler(private val onResponse: (String, Map<String, String>) -> Unit) : DefaultHandler() {
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
                uri == DAV && localName == "response" -> href?.let { onResponse(it, props) }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }
    }

    private fun toRemoteFile(href: String, props: Map<String, String>): RemoteFile? {
        fun text(namespace: String, name: String) = props[key(namespace, name)]?.takeIf(String::isNotEmpty)
        val size = key(NC, "metadata-photos-size")
        return RemoteFile(
            href = href,
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
