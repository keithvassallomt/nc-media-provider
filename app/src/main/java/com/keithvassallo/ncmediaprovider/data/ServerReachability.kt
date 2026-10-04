package com.keithvassallo.ncmediaprovider.data

import java.io.FileNotFoundException

/**
 * Whether the server can be reached right now, so a file that isn't cached fails at once instead
 * of making the picker, or the app it hands the file to, wait out a timeout (#47). Without a
 * network nothing is tried. After [failuresBeforeFailFast] requests in a row failed for want of
 * the server, nothing is tried for [failFastMillis]; any answer from the server clears that. One
 * failure isn't enough: a single slow thumbnail blanked whole screens in Phase 5. Thumbnails keep
 * their own back-off too ([RemoteQueryGate]); this one covers originals and streams as well, and
 * shares what each request learns.
 */
internal class ServerReachability(
    private val hasNetwork: () -> Boolean,
    private val failuresBeforeFailFast: Int = 2,
    private val failFastMillis: Long = 15_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var failuresInARow = 0
    private var lastFailureMillis = 0L

    /** Throws [ServerUnreachableException] when a request now can't succeed. */
    @Throws(ServerUnreachableException::class)
    fun check() {
        if (!hasNetwork()) throw ServerUnreachableException("No network")
        synchronized(lock) {
            val since = nowMillis() - lastFailureMillis
            if (failuresInARow >= failuresBeforeFailFast && since < failFastMillis) {
                throw ServerUnreachableException("Nextcloud couldn't be reached ${since / 1_000} s ago")
            }
        }
    }

    /** Runs [block] after [check], and learns from how it went. */
    fun <T> request(block: () -> T): T {
        check()
        return try {
            block().also { noteAnswer() }
        } catch (error: Exception) {
            when {
                isReachabilityFailure(error) -> synchronized(lock) {
                    failuresInARow++
                    lastFailureMillis = nowMillis()
                }
                // The server answered, if only to say no.
                error is NextcloudHttpException -> noteAnswer()
            }
            throw error
        }
    }

    private fun noteAnswer() = synchronized(lock) { failuresInARow = 0 }
}

/** A file that isn't cached and can't be fetched now; the picker shows it as unavailable. */
class ServerUnreachableException(message: String) : FileNotFoundException(message)
