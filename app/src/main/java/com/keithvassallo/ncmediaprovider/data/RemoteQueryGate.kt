package com.keithvassallo.ncmediaprovider.data

import java.io.IOException

/**
 * Keeps concurrent or repeatedly failing metadata requests from holding up the system picker.
 * Media downloads deliberately do not use this gate because they are explicit user actions.
 */
internal class RemoteQueryGate(
    private val retryDelayMillis: Long,
    private val shouldBackOff: (Exception) -> Boolean = { true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var retryAfterMillis = 0L

    fun <T> query(block: () -> T): T {
        synchronized(lock) {
            val now = nowMillis()
            if (now < retryAfterMillis) {
                throw RemoteQueryDeferredException((retryAfterMillis - now).coerceAtLeast(0L))
            }
        }

        return try {
            block().also {
                synchronized(lock) { retryAfterMillis = 0L }
            }
        } catch (error: Exception) {
            if (shouldBackOff(error)) {
                synchronized(lock) { retryAfterMillis = nowMillis() + retryDelayMillis }
            }
            throw error
        }
    }

    fun reset() {
        synchronized(lock) { retryAfterMillis = 0L }
    }
}

internal class RemoteQueryDeferredException(retryInMillis: Long) : IOException(
    "Metadata request deferred for ${retryInMillis}ms after another slow or failed request",
)
