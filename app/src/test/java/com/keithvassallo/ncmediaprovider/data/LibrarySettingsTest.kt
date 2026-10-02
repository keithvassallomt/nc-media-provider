package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySettingsTest {
    @Test
    fun `folders are normalised`() {
        assertEquals("/Photos", LibrarySettings.normalizeFolder(" Photos/ "))
        assertEquals("/Photos/2024", LibrarySettings.normalizeFolder("/Photos/2024/"))
        assertEquals("/", LibrarySettings.normalizeFolder(""))
        assertEquals("/", LibrarySettings.normalizeFolder("/"))
    }

    @Test
    fun `a folder set is sorted, without repeats or folders inside another`() {
        assertEquals(
            listOf("/InstantUpload", "/Photos", "/Photos 2"),
            LibrarySettings.normalizeFolders(listOf("Photos/", "/Photos/2024", "/InstantUpload", "/Photos", "/Photos 2")),
        )
        assertEquals(listOf("/"), LibrarySettings.normalizeFolders(listOf("/Photos", "/")))
        assertEquals(listOf("/"), LibrarySettings.normalizeFolders(listOf("", " ")))
        assertEquals(listOf("/"), LibrarySettings.normalizeFolders(emptyList()))
    }
}
