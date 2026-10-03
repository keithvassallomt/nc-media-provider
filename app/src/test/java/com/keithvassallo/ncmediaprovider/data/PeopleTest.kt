package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** People from Recognize, through Memories (PLAN 7.3). */
class PeopleTest {
    @Test
    fun `face groups are read with their key and signature, and broken ones skipped`() {
        // The shape of Memories 9's clusters/recognize on Keith's server, names changed.
        val clusters = People.parseClusters(
            """
            [
              {"id": 1, "user_id": "keith", "count": 3252, "cover": 15309, "cover_etag": "4828c9", "cluster_id": 1, "cluster_type": "recognize", "name": "Alex"},
              {"id": 178, "user_id": "keith", "count": 40, "cover": 15558, "cover_etag": "71ea52", "cluster_id": 178, "cluster_type": "recognize", "name": null},
              {"id": 9, "count": 3, "cluster_id": 9},
              "not a cluster"
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("keith/1", "keith/178"), clusters.map(MemoriesCluster::memoriesKey))
        assertEquals("Alex", clusters[0].name)
        assertEquals("", clusters[1].name)
        assertEquals("40|71ea52", clusters[1].signature)
    }

    @Test
    fun `IDs are prefixed, and a face cover leads back to its person`() {
        val cluster = MemoriesCluster("keith", 178, "Alex", 40, "e")
        val person = People.personId(cluster)
        assertTrue(People.isPersonId(person))
        assertFalse(People.isFaceId(person))
        val face = People.faceId(person)
        assertTrue(People.isFaceId(face))
        assertEquals(person, People.personOfFace(face))
        // No slash: the picker puts cover IDs in content URIs.
        assertFalse('/' in face)
    }

    @Test
    fun `the picker gets named people first, the most photographed first, and nobody without photos`() {
        val shown = People.forPicker(
            listOf(
                PersonSummary("nc-person-1", "", shown = 900, newest = 1),
                PersonSummary("nc-person-2", "Bea", shown = 10, newest = 2),
                PersonSummary("nc-person-3", "Alex", shown = 50, newest = 3),
                PersonSummary("nc-person-4", "Cy", shown = 0, newest = 0),
            ),
        )
        assertEquals(listOf("Alex", "Bea", ""), shown.map(PickerPerson::name))
        assertEquals("nc-face-3", shown[0].faceCoverId)
    }
}
