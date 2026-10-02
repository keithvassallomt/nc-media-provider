package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteQueryGateTest {
    @Test
    fun `defers requests during backoff after failure`() {
        var now = 1_000L
        val gate = RemoteQueryGate(retryDelayMillis = 30_000L, nowMillis = { now })

        assertThrows(IOException::class.java) {
            gate.query<Unit> { throw IOException("offline") }
        }
        assertThrows(RemoteQueryDeferredException::class.java) {
            gate.query { "should not run" }
        }

        now += 30_000L
        assertEquals("online", gate.query { "online" })
    }

    @Test
    fun `reset allows an immediate manual retry`() {
        val gate = RemoteQueryGate(retryDelayMillis = 30_000L, nowMillis = { 1_000L })
        assertThrows(IOException::class.java) {
            gate.query<Unit> { throw IOException("offline") }
        }

        gate.reset()

        assertEquals(42, gate.query { 42 })
    }

    @Test
    fun `does not back off for a non-reachability failure`() {
        val gate = RemoteQueryGate(
            retryDelayMillis = 30_000L,
            shouldBackOff = { it is IOException },
            nowMillis = { 1_000L },
        )
        assertThrows(IllegalArgumentException::class.java) {
            gate.query<Unit> { throw IllegalArgumentException("bad request") }
        }

        assertEquals("retry", gate.query { "retry" })
    }

    @Test
    fun `backs off only after enough failures in a row`() {
        var now = 0L
        val gate = RemoteQueryGate(1_000, nowMillis = { now }, failuresBeforeBackOff = 3)
        repeat(2) { runCatching { gate.query { throw IOException("down") } } }
        gate.query { "a success resets the count" }
        repeat(2) { runCatching { gate.query { throw IOException("down") } } }
        assertEquals("still tried", gate.query { "still tried" })
        runCatching { gate.query { throw IOException("down") } }
        runCatching { gate.query { throw IOException("down") } }
        runCatching { gate.query { throw IOException("down") } }
        assertTrue(runCatching { gate.query { "deferred" } }.exceptionOrNull() is RemoteQueryDeferredException)
        now += 1_000
        assertEquals("open again", gate.query { "open again" })
    }
}
