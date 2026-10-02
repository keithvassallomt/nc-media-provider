package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestTest {
    @Test
    fun `scope is the raw folder path, XML-escaped`() {
        // Nextcloud answers 404 for a percent-encoded scope (Phase 0.2).
        val body = SearchRequest.body("alice", "/Shared trip & more", "image/", null, 1000)
        assertTrue(body.contains("<d:href>/files/alice/Shared trip &amp; more</d:href>"))
    }

    @Test
    fun `root folder scopes the whole home`() {
        assertTrue(SearchRequest.body("alice", "/", "image/", null, 10).contains("<d:href>/files/alice</d:href>"))
    }

    @Test
    fun `always sets nresults, which otherwise defaults to 100`() {
        assertTrue(SearchRequest.body("alice", "/Photos", "image/", null, 1000).contains("<d:nresults>1000</d:nresults>"))
    }

    @Test
    fun `date window filters on modification time in seconds`() {
        val first = SearchRequest.body("alice", "/Photos", "image/", null, 1000)
        val next = SearchRequest.body("alice", "/Photos", "image/", 1706781600L, 1000)
        assertFalse(first.contains("<d:lte>"))
        assertTrue(next.contains("<d:lte><d:prop><d:getlastmodified/></d:prop><d:literal>1706781600</d:literal></d:lte>"))
        assertTrue(next.contains("<d:literal>image/%</d:literal>"))
    }
}
