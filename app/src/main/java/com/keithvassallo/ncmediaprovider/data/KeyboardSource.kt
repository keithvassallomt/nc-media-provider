package com.keithvassallo.ncmediaprovider.data

import java.time.Instant
import java.time.ZoneId

/** What the photo keyboard's grid shows (PLAN 4.8): the library, favourites, an album or a person. */
sealed interface KeyboardSource {
    data object Library : KeyboardSource

    data object Favourites : KeyboardSource

    data class Album(val id: String) : KeyboardSource

    data class Person(val id: String) : KeyboardSource
}

/** Paging an album's contents for the keyboard in memory: albums hold hundreds, not thousands. */
internal object KeyboardPages {
    private val newestFirst = compareByDescending<MediaItem> { it.dateTakenMillis }.thenByDescending { it.id }

    /** Items before the keyset position ([date], [id]), newest first; with [newer], the next newer ones, oldest first. */
    fun page(items: List<MediaItem>, date: Long, id: String, newer: Boolean, limit: Int): List<MediaItem> =
        if (newer) {
            items.filter { it.dateTakenMillis > date || (it.dateTakenMillis == date && it.id > id) }.sortedWith(newestFirst.reversed()).take(limit)
        } else {
            items.filter { it.dateTakenMillis < date || (it.dateTakenMillis == date && it.id < id) }.sortedWith(newestFirst).take(limit)
        }

    /** The years [items] were taken in, newest first, in [zone]. */
    fun years(items: List<MediaItem>, zone: ZoneId): List<Int> =
        items.map { Instant.ofEpochMilli(it.dateTakenMillis).atZone(zone).year }.distinct().sortedDescending()
}
