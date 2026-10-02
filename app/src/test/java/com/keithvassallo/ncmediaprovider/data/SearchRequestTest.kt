package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestTest {
    @Test
    fun `scope is the raw folder path, XML-escaped`() {
        // Nextcloud answers 404 for a percent-encoded scope (Phase 0.2).
        val body = SearchRequest.body("alice", listOf("/Shared trip & more"), listOf("image/"), null, 1000)
        assertTrue(body.contains("<d:href>/files/alice/Shared trip &amp; more</d:href>"))
    }

    @Test
    fun `root folder scopes the whole home`() {
        assertTrue(SearchRequest.body("alice", listOf("/"), listOf("image/"), null, 10).contains("<d:href>/files/alice</d:href>"))
    }

    @Test
    fun `one request covers every folder`() {
        val body = SearchRequest.body("alice", listOf("/InstantUpload", "/Photos"), listOf("image/"), null, 10)
        assertTrue(
            body.contains(
                "<d:from><d:scope><d:href>/files/alice/InstantUpload</d:href><d:depth>infinity</d:depth></d:scope>" +
                    "<d:scope><d:href>/files/alice/Photos</d:href><d:depth>infinity</d:depth></d:scope></d:from>",
            ),
        )
    }

    @Test
    fun `several MIME prefixes become one OR`() {
        val body = SearchRequest.body("alice", listOf("/Photos"), listOf("image/", "video/"), null, 10)
        assertTrue(
            body.contains(
                "<d:or><d:like><d:prop><d:getcontenttype/></d:prop><d:literal>image/%</d:literal></d:like>" +
                    "<d:like><d:prop><d:getcontenttype/></d:prop><d:literal>video/%</d:literal></d:like></d:or>",
            ),
        )
    }

    @Test
    fun `marker search asks for every marker name`() {
        val body = SearchRequest.markers("alice", listOf("/Photos"))
        for (name in SearchRequest.HIDING_MARKERS) {
            assertTrue(body.contains("<d:eq><d:prop><d:displayname/></d:prop><d:literal>$name</d:literal></d:eq>"))
        }
        assertTrue(body.contains("<d:or>"))
    }

    @Test
    fun `always sets nresults, which otherwise defaults to 100`() {
        assertTrue(SearchRequest.body("alice", listOf("/Photos"), listOf("image/"), null, 1000).contains("<d:nresults>1000</d:nresults>"))
    }

    @Test
    fun `modification filters use whole seconds`() {
        val photos = listOf("/Photos")
        val first = SearchRequest.body("alice", photos, listOf("image/"), null, 1000)
        val next = SearchRequest.body("alice", photos, listOf("image/"), ModifiedFilter.AtOrBefore(1706781600L), 1000)
        val second = SearchRequest.body("alice", photos, listOf("image/"), ModifiedFilter.Exactly(1706781600L), 1000)
        assertFalse(first.contains("<d:lte>"))
        assertTrue(next.contains("<d:lte><d:prop><d:getlastmodified/></d:prop><d:literal>1706781600</d:literal></d:lte>"))
        assertTrue(second.contains("<d:eq><d:prop><d:getlastmodified/></d:prop><d:literal>1706781600</d:literal></d:eq>"))
        assertTrue(next.contains("<d:literal>image/%</d:literal>"))
    }
}
