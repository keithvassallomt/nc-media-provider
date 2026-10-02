package com.keithvassallo.ncmediaprovider.keyboard

/** Which type a photo goes to an app as, from the types its text field accepts (PLAN 4.8). */
internal object KeyboardFormats {
    /**
     * The photo's own type when the app accepts it; JPEG for other images when the app takes JPEG
     * (HEIC, mostly: Messenger accepts PNG, GIF, JPEG and WebP); null when nothing fits.
     */
    fun outputType(mimeType: String, accepted: List<String>): String? = when {
        accepted.any { matches(mimeType, it) } -> mimeType
        mimeType.startsWith("image/") && accepted.any { matches(JPEG, it) } -> JPEG
        else -> null
    }

    fun acceptsImages(accepted: List<String>): Boolean = accepted.any { matches(JPEG, it) }

    /** [type] against a MIME pattern in which * stands for any part, as ClipDescription.compareMimeTypes does. */
    fun matches(type: String, pattern: String): Boolean {
        val (kind, sub) = type.lowercase().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (patternKind, patternSub) = pattern.lowercase().split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return (patternKind == "*" || patternKind == kind) && (patternSub == "*" || patternSub == sub)
    }

    const val JPEG = "image/jpeg"
}
