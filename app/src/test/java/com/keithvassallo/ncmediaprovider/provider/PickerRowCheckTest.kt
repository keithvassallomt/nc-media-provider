package com.keithvassallo.ncmediaprovider.provider

import com.keithvassallo.ncmediaprovider.data.MediaItem
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PickerRowCheckTest {
    private val good = MediaItem("1", "/f/1.jpg", "e", "1.jpg", "image/jpeg", 10, 1_000, 1_000, generation = 1)

    @Test
    fun `a complete row passes, with or without a local copy`() {
        assertNull(PickerRowCheck.problem(good, null))
        assertNull(PickerRowCheck.problem(good, "content://media/external/images/media/123"))
        assertNull(PickerRowCheck.problem(good.copy(mimeType = "video/mp4"), "content://media/external/video/media/9"))
    }

    @Test
    fun `rows the picker would drop or choke on are caught`() {
        assertNotNull(PickerRowCheck.problem(good.copy(dateTakenMillis = 0), null))
        assertNotNull(PickerRowCheck.problem(good.copy(sizeBytes = 0), null))
        assertNotNull(PickerRowCheck.problem(good.copy(mimeType = "application/pdf"), null))
        assertNotNull(PickerRowCheck.problem(good.copy(generation = 0), null))
        assertNotNull(PickerRowCheck.problem(good, "file:///sdcard/DCIM/1.jpg"))
        assertNotNull(PickerRowCheck.problem(good, "content://media/external/images/media/abc"))
    }
}
