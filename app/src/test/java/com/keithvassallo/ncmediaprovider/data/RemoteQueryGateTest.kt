package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
