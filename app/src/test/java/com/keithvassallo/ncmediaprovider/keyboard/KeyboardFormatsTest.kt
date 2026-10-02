package com.keithvassallo.ncmediaprovider.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardFormatsTest {
    // What Messenger declared to the prototype (Phase 4.8).
    private val messenger = listOf("image/png", "image/gif", "image/jpeg", "image/webp")

    @Test
    fun `accepted types go as they are, other images as JPEG`() {
        assertEquals("image/jpeg", KeyboardFormats.outputType("image/jpeg", messenger))
        assertEquals("image/png", KeyboardFormats.outputType("image/png", messenger))
        assertEquals("image/jpeg", KeyboardFormats.outputType("image/heic", messenger))
        assertNull(KeyboardFormats.outputType("video/mp4", messenger))
        assertNull(KeyboardFormats.outputType("image/heic", listOf("image/gif")))
    }

    @Test
    fun `wildcards match as Android matches them`() {
        assertEquals("image/heic", KeyboardFormats.outputType("image/heic", listOf("image/*")))
        assertEquals("video/mp4", KeyboardFormats.outputType("video/mp4", listOf("*/*")))
        assertTrue(KeyboardFormats.acceptsImages(listOf("image/*")))
        assertFalse(KeyboardFormats.acceptsImages(emptyList()))
        assertFalse(KeyboardFormats.acceptsImages(listOf("image/gif")))
    }
}
