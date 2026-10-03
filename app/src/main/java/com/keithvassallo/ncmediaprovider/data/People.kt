package com.keithvassallo.ncmediaprovider.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONException

/**
 * A person: a face group from Recognize, read through Memories (PLAN 7.3). Recognize's own WebDAV
 * wants a server-side key, so Memories is the way in; a server without both has no people.
 */
@Entity(tableName = "person")
data class Person(
    @PrimaryKey val id: String,
    /** Memories' name for the group, `<user>/<cluster>`, which its preview endpoint takes. */
    val memoriesKey: String,
    /** Empty for a face nobody has named yet. */
    val name: String,
    /** Memories' count and cover etag when the photos were last listed; a change lists them again. */
    val signature: String,
)

/** A photo of a person, by Nextcloud file ID. */
@Entity(tableName = "person_item", primaryKeys = ["personId", "id"], indices = [Index(value = ["id"])])
data class PersonItem(
    val personId: String,
    val id: String,
)

/** A face group as Memories lists it. */
data class MemoriesCluster(
    val user: String,
    val clusterId: Long,
    val name: String,
    val count: Int,
    val coverEtag: String,
) {
    val memoriesKey: String get() = "$user/$clusterId"
    val signature: String get() = "$count|$coverEtag"
}

/** A person as the picker gets them: only photos in the library count. */
data class PickerPerson(
    val id: String,
    val name: String,
    val faceCoverId: String,
    val count: Int,
    val newestMillis: Long,
)

/** Room's summary of a person with what the library holds of them. */
data class PersonSummary(
    val id: String,
    val name: String,
    val shown: Int,
    val newest: Long,
)

internal object People {
    /** Person IDs double as album IDs on the older picker, so they need a prefix of their own. */
    private const val PERSON_PREFIX = "nc-person-"

    /** A person's face, cropped by Memories: a cover only, never a photo the picker can pick. */
    private const val FACE_PREFIX = "nc-face-"

    /** The one category the app offers the newer picker. */
    const val CATEGORY_ID = "nc-people"

    fun personId(cluster: MemoriesCluster): String = PERSON_PREFIX + cluster.clusterId

    fun isPersonId(id: String): Boolean = id.startsWith(PERSON_PREFIX)

    fun faceId(personId: String): String = FACE_PREFIX + personId.removePrefix(PERSON_PREFIX)

    fun isFaceId(id: String): Boolean = id.startsWith(FACE_PREFIX)

    /** The person a face cover belongs to. */
    fun personOfFace(faceId: String): String = PERSON_PREFIX + faceId.removePrefix(FACE_PREFIX)

    /** Memories' `clusters/recognize`: the user's face groups. One without an ID or user is skipped. */
    @Throws(JSONException::class)
    fun parseClusters(json: String): List<MemoriesCluster> {
        val clusters = JSONArray(json)
        return (0 until clusters.length()).mapNotNull { i ->
            val cluster = clusters.optJSONObject(i) ?: return@mapNotNull null
            val id = cluster.optLong("cluster_id", -1L).takeIf { it >= 0L } ?: return@mapNotNull null
            val user = cluster.optString("user_id", "").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            MemoriesCluster(
                user = user,
                clusterId = id,
                name = cluster.optString("name", "").let { if (it == "null") "" else it.trim() },
                count = cluster.optInt("count", 0),
                coverEtag = cluster.optString("cover_etag", ""),
            )
        }
    }

    /** Named people first, then faces nobody named; the most photographed first within each. */
    fun forPicker(summaries: List<PersonSummary>): List<PickerPerson> = summaries
        .filter { it.shown > 0 }
        .sortedWith(compareBy<PersonSummary> { it.name.isEmpty() }.thenByDescending { it.shown }.thenBy { it.name })
        .map { PickerPerson(it.id, it.name, faceId(it.id), it.shown, it.newest) }
}
