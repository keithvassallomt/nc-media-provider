package com.keithvassallo.ncmediaprovider.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/** An album as Nextcloud Photos lists it (PLAN 7.1): one of the user's own, or one shared with them. */
data class PhotosAlbumEntry(
    /** Percent-encoded collection path ending in '/', e.g. `/remote.php/dav/photos/alice/albums/Holiday/`. */
    val href: String,
    val itemCount: Int,
    /** Photos' cover: the file ID of the photo added last. */
    val lastPhotoId: String?,
    /** Photos' first and last capture times, as the JSON it sends. */
    val dateRange: String,
    val isShared: Boolean,
) {
    /** The album's name; Photos ends a shared album's with its owner, as in "Road trip (bob)". */
    val name: String get() = percentDecode(href.trimEnd('/').substringAfterLast('/'))

    /** Changes when Photos' count, cover or date range does, as adding or removing a photo does. */
    val signature: String get() = "$itemCount|${lastPhotoId.orEmpty()}|$dateRange"
}

/** An album as last listed. */
@Entity(tableName = "album")
data class Album(
    @PrimaryKey val id: String,
    val href: String,
    val name: String,
    val isShared: Boolean,
    /** Photos' cover, the photo added last; see [Albums.forPicker]. */
    val coverId: String?,
    /** [PhotosAlbumEntry.signature] when the album's files were last listed. */
    val signature: String,
)

/**
 * A file in an album, as the album lists it. A file in the library is shown with its library row
 * (Memories' date, the phone's copy); this row serves files outside the library, a shared album's
 * photos say, which the picker can then show and open too.
 */
@Entity(tableName = "album_item", primaryKeys = ["albumId", "id"], indices = [Index(value = ["id"])])
data class AlbumItem(
    val albumId: String,
    /** The Nextcloud file ID: the same as the library row's for a file in the library. */
    val id: String,
    /** The path inside the album, which works where the user's own file paths don't (files shared through the album). */
    val href: String,
    val etag: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val dateTakenMillis: Long,
    val width: Int,
    val height: Int,
    val isFavorite: Boolean,
)

/** An album as the picker gets it: only photos and videos it can show count, and it needs a cover. */
data class PickerAlbum(
    val id: String,
    val name: String,
    val count: Int,
    val coverId: String,
    val dateTakenMillis: Long,
)

internal object Albums {
    /** The picker reserves IDs such as "Favorites" and "Camera" for its own albums; these can't match one. */
    private const val ID_PREFIX = "nc-album-"
    private const val PHOTOS_DAV = "/remote.php/dav/photos/"

    /** [isAlbumPath] as an SQL LIKE pattern. */
    const val ALBUM_PATH_PATTERN = "%$PHOTOS_DAV%"

    /** A stable ID for the album at [href]; renaming an album gives it a new one. */
    fun albumId(href: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(percentDecode(href).trimEnd('/').toByteArray())
        return ID_PREFIX + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun isAlbumId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** Whether [href] is a path inside an album, where a file needs Photos' own preview endpoint. */
    fun isAlbumPath(href: String): Boolean = PHOTOS_DAV in href

    /** An album's file as stored; Photos names album files `<fileid>-<name>`, which is undone here. */
    fun item(albumId: String, file: RemoteFile) = AlbumItem(
        albumId = albumId,
        id = file.fileId,
        href = file.href,
        etag = file.etag,
        fileName = file.fileName.removePrefix("${file.fileId}-"),
        mimeType = file.mimeType,
        sizeBytes = file.sizeBytes,
        lastModifiedMillis = file.lastModifiedMillis,
        // As the library does: Nextcloud's date taken, else the modification time (PLAN 2.8).
        dateTakenMillis = file.originalDateTimeMillis ?: file.lastModifiedMillis,
        width = file.width,
        height = file.height,
        isFavorite = file.isFavorite,
    )

    /** A file outside the library, as a row the picker can show; [generation] is the library's current one. */
    fun AlbumItem.toMediaItem(generation: Long) = MediaItem(
        id = id,
        href = href,
        etag = etag,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis,
        dateTakenMillis = dateTakenMillis,
        width = width,
        height = height,
        isFavorite = isFavorite,
        generation = generation,
        folder = parentFolderKey(href),
    )

    /**
     * What the picker shows of an album: each file's library row where it has one, else the album's
     * own row. Live-photo videos hidden from the library stay hidden here.
     */
    fun contents(items: List<AlbumItem>, libraryRows: Map<String, MediaItem>, generation: Long): List<MediaItem> =
        items.mapNotNull { item ->
            val row = libraryRows[item.id]
            when {
                row?.isLiveVideo == true -> null
                row != null -> row
                else -> item.toMediaItem(generation)
            }
        }

    /** [album] for the picker, or null when nothing in it can be shown. The cover is Photos' when it can be shown. */
    fun forPicker(album: Album, contents: List<MediaItem>): PickerAlbum? {
        if (contents.isEmpty()) return null
        val newest = contents.maxBy(MediaItem::dateTakenMillis)
        return PickerAlbum(
            id = album.id,
            name = album.name,
            count = contents.size,
            coverId = album.coverId?.takeIf { cover -> contents.any { it.id == cover } } ?: newest.id,
            dateTakenMillis = newest.dateTakenMillis,
        )
    }
}
