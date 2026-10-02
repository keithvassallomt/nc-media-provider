package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs against real SEARCH responses saved from the Phase 0.2 test servers (testdata/nextcloud). */
class MultistatusParserTest {
    private fun fixture(path: String): List<RemoteFile> =
        File("../testdata/nextcloud/$path").inputStream().use(MultistatusParser::parse)

    private val jpegs = fixture("35-full/coverage-jpeg.xml").associateBy(RemoteFile::fileName)

    @Test
    fun `parses every file in the response`() {
        assertEquals(8, jpegs.size)
    }

    @Test
    fun `reads ids, etags without quotes, sizes and modification times`() {
        val beach = jpegs.getValue("beach-exif.jpg")
        assertEquals("102", beach.fileId)
        assertEquals("5514073bc5e8248e999d4c6195326f64", beach.etag)
        assertEquals("image/jpeg", beach.mimeType)
        assertEquals(38417L, beach.sizeBytes)
        assertEquals(1706781600_000L, beach.lastModifiedMillis) // Thu, 01 Feb 2024 10:00:00 GMT
        assertEquals("/remote.php/dav/files/alice/Photos/2021/Summer/beach-exif.jpg", beach.href)
    }

    @Test
    fun `reads date taken and favourite`() {
        val beach = jpegs.getValue("beach-exif.jpg")
        assertEquals(1626276600_000L, beach.originalDateTimeMillis)
        assertTrue(beach.isFavorite)
        assertFalse(jpegs.getValue("no-exif.jpg").isFavorite)
    }

    @Test
    fun `sizes are in display orientation`() {
        // Stored as 1200x800 with EXIF orientation 6.
        val rotated = jpegs.getValue("rotated-exif6.jpg")
        assertEquals(800, rotated.width)
        assertEquals(1200, rotated.height)
    }

    @Test
    fun `decodes percent-encoded UTF-8 file names`() {
        assertTrue("Ünïcødé & spaces #1.jpg" in jpegs)
        assertTrue("trip-1.jpg" in jpegs)
    }

    @Test
    fun `missing metadata leaves size at zero`() {
        // Nextcloud 33 records no dimensions for HEIC.
        val heic = fixture("33-default/coverage-heic.xml").associateBy(RemoteFile::fileName).getValue("portrait.heic")
        assertEquals(0, heic.width)
        assertEquals(0, heic.height)
        assertEquals("image/heic", heic.mimeType)
        assertEquals(1706871600_000L, heic.originalDateTimeMillis)
        assertFalse(heic.isHidden)
    }

    @Test
    fun `HTTP dates parse by hand and by the fallback alike`() {
        assertEquals(1706781600_000L, MultistatusParser.parseHttpDate("Thu, 01 Feb 2024 10:00:00 GMT"))
        assertEquals(951782400_000L, MultistatusParser.parseHttpDate("Tue, 29 Feb 2000 00:00:00 GMT"))
        assertEquals(1706781600_000L, MultistatusParser.parseHttpDate("Thu, 1 Feb 2024 10:00:00 +0000")) // fallback
        assertNull(MultistatusParser.parseHttpDate("yesterday"))
    }

    @Test
    fun `entries without a date taken fall back to null`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/u/a+b.jpg</d:href>
  <d:propstat><d:prop><oc:fileid>7</oc:fileid><d:getcontenttype>image/jpeg</d:getcontenttype>
   <d:getcontentlength>10</d:getcontentlength><nc:hidden>true</nc:hidden></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><nc:metadata-photos-original_date_time/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/u/folder/</d:href>
  <d:propstat><d:prop><oc:fileid>8</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""
        val files = MultistatusParser.parse(xml.byteInputStream())
        assertEquals(1, files.size) // the folder has no MIME type
        assertNull(files.single().originalDateTimeMillis)
        assertTrue(files.single().isHidden)
        assertEquals("a+b.jpg", files.single().fileName) // '+' is not a space in a path
    }
}
