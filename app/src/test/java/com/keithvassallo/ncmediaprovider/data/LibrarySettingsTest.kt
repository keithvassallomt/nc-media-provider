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
}
