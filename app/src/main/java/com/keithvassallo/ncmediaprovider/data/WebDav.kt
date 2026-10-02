package com.keithvassallo.ncmediaprovider.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

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

/**
 * Builds a WebDAV SEARCH for one folder tree, newest first, optionally limited to files modified at
 * or before a time. Paging walks backwards through those date windows (PLAN 2.2): offset paging is
 * unreliable because results can't be ordered by file ID.
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
        modifiedAtOrBeforeSeconds: Long?,
        pageSize: Int,
    ): String {
        // The scope is a raw path: Nextcloud answers 404 for a percent-encoded one.
        val scope = "/files/$userId" + if (folder == "/") "" else folder
        val mimeFilter = "<d:like><d:prop><d:getcontenttype/></d:prop><d:literal>${xmlEscape(mimePrefix)}%</d:literal></d:like>"
        val where = if (modifiedAtOrBeforeSeconds == null) {
            mimeFilter
        } else {
            "<d:and>$mimeFilter<d:lte><d:prop><d:getlastmodified/></d:prop>" +
                "<d:literal>$modifiedAtOrBeforeSeconds</d:literal></d:lte></d:and>"
        }
        // Without nresults Nextcloud caps the answer at 100.
        return """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
<d:basicsearch>
<d:select><d:prop>$PROPS</d:prop></d:select>
<d:from><d:scope><d:href>${xmlEscape(scope)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
<d:where>$where</d:where>
<d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
<d:limit><d:nresults>$pageSize</d:nresults></d:limit>
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
 * Parses a WebDAV multistatus response. Uses DOM from javax.xml, which exists both on Android and
 * on the JVM, so the parser is unit-tested against the saved server fixtures in testdata/.
 * Entries without a file ID or MIME type (folders) are skipped.
 */
object MultistatusParser {
    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"
    private const val NC = "http://nextcloud.org/ns"

    fun parse(input: InputStream): List<RemoteFile> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val responses = factory.newDocumentBuilder().parse(input).getElementsByTagNameNS(DAV, "response")
        return (0 until responses.length).mapNotNull { parseResponse(responses.item(it) as Element) }
    }

    private fun parseResponse(response: Element): RemoteFile? {
        val href = response.child(DAV, "href")?.textContent?.trim() ?: return null
        val props = HashMap<String, Element>()
        response.children(DAV, "propstat")
            .filter { it.child(DAV, "status")?.textContent?.contains(" 200 ") == true }
            .forEach { propstat ->
                propstat.child(DAV, "prop")?.childElements()?.forEach { props[key(it.namespaceURI, it.localName)] = it }
            }
        fun text(namespace: String, name: String): String? =
            props[key(namespace, name)]?.textContent?.trim()?.takeIf(String::isNotEmpty)

        val fileId = text(OC, "fileid") ?: return null
        val mimeType = text(DAV, "getcontenttype") ?: return null
        val size = props[key(NC, "metadata-photos-size")]
        return RemoteFile(
            href = href,
            fileId = fileId,
            etag = text(DAV, "getetag")?.trim('"').orEmpty(),
            mimeType = mimeType,
            sizeBytes = text(DAV, "getcontentlength")?.toLongOrNull() ?: 0L,
            lastModifiedMillis = text(DAV, "getlastmodified")?.let(::parseHttpDate) ?: 0L,
            originalDateTimeMillis = text(NC, "metadata-photos-original_date_time")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.times(1000L),
            // The size's width and height children carry no namespace.
            width = size?.childText("width")?.toIntOrNull() ?: 0,
            height = size?.childText("height")?.toIntOrNull() ?: 0,
            isFavorite = text(OC, "favorite") == "1",
            isHidden = text(NC, "hidden") == "true",
        )
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private fun key(namespace: String?, name: String?) = "${namespace.orEmpty()}|$name"

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>()

    private fun Element.children(namespace: String, name: String): List<Element> =
        childElements().filter { it.namespaceURI == namespace && it.localName == name }

    private fun Element.child(namespace: String, name: String): Element? = children(namespace, name).firstOrNull()

    private fun Element.childText(name: String): String? = childElements()
        .firstOrNull { (it.localName ?: it.nodeName) == name && it.nodeType == Node.ELEMENT_NODE }
        ?.textContent
        ?.trim()
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
