package com.keithvassallo.ncmediaprovider.data

import java.io.IOException

/**
 * Keeps concurrent or repeatedly failing metadata requests from holding up the system picker.
 * Media downloads deliberately do not use this gate because they are explicit user actions.
 * It backs off for [retryDelayMillis] after [failuresBeforeBackOff] failures in a row.
 */
internal class RemoteQueryGate(
    private val retryDelayMillis: Long,
    private val shouldBackOff: (Exception) -> Boolean = { true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val failuresBeforeBackOff: Int = 1,
) {
    private val lock = Any()
    private var retryAfterMillis = 0L
    private var failuresInARow = 0

    fun <T> query(block: () -> T): T {
        synchronized(lock) {
            val now = nowMillis()
            if (now < retryAfterMillis) {
                throw RemoteQueryDeferredException((retryAfterMillis - now).coerceAtLeast(0L))
            }
        }

        return try {
            block().also {
                synchronized(lock) {
                    retryAfterMillis = 0L
                    failuresInARow = 0
                }
            }
        } catch (error: Exception) {
            if (shouldBackOff(error)) {
                synchronized(lock) {
                    failuresInARow++
                    if (failuresInARow >= failuresBeforeBackOff) {
                        retryAfterMillis = nowMillis() + retryDelayMillis
                        failuresInARow = 0
                    }
                }
            }
            throw error
        }
    }

    fun reset() {
        synchronized(lock) {
            retryAfterMillis = 0L
            failuresInARow = 0
        }
    }
}

internal class RemoteQueryDeferredException(retryInMillis: Long) : IOException(
    "Metadata request deferred for ${retryInMillis}ms after another slow or failed request",
)
