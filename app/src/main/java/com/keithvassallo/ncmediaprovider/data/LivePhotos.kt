package com.keithvassallo.ncmediaprovider.data

/**
 * The videos that are the moving half of a live photo, which the picker shouldn't show as videos
 * of their own (PLAN 5.5 and 6.2). Memories' pairing is one way to know. The other is the name: an
 * iPhone keeps a live photo as a HEIC or JPEG and a MOV of about 3 s with the same name, and numbers
 * photos and videos from one counter, so a video of its own never shares a photo's name. On Keith's
 * server Memories paired 256 and listed 13 more as videos of their own; names catch those, and
 * work without Memories once a video's length has been read.
 */
internal object LivePhotos {
    /** Live-photo videos run up to about 3 s; a little more allows for rounding. */
    const val MAX_MILLIS = 4_000L

    fun halves(items: Collection<MediaItem>, memories: (String) -> MemoriesFile?): Set<String> =
        MemoriesApi.liveHalves(items, memories) + byName(items)

    /** Short MOVs that share a folder and a name with a photo. A length not read yet doesn't count. */
    fun byName(items: Collection<MediaItem>): Set<String> {
        val photos = items.filter { it.mimeType.startsWith("image/") }.mapTo(HashSet(), MemoriesApi::pairKey)
        if (photos.isEmpty()) return emptySet()
        return items
            .filter { it.mimeType == "video/quicktime" && it.durationMillis in 1..MAX_MILLIS && MemoriesApi.pairKey(it) in photos }
            .mapTo(HashSet(), MediaItem::id)
    }
}
