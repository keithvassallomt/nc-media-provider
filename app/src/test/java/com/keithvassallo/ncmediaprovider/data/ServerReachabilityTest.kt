package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ServerReachabilityTest {
    private var now = 1_000_000L
    private var network = true
    private val reachability = ServerReachability(hasNetwork = { network }, nowMillis = { now })
    private var calls = 0

    private fun fails(error: Exception) = assertThrows(error.javaClass) { reachability.request<Unit> { calls++; throw error } }

    @Test
    fun `without a network nothing is tried`() {
        network = false
        assertThrows(ServerUnreachableException::class.java) { reachability.request { calls++ } }
        assertEquals(0, calls)
    }

    @Test
    fun `two unreachable failures in a row fail fast until the window passes`() {
        fails(UnknownHostException("nc.example"))
        // One failure isn't enough: a slow thumbnail mustn't blank the screen.
        reachability.check()
        fails(IOException("timeout"))
        assertThrows(ServerUnreachableException::class.java) { reachability.request { calls++ } }
        assertEquals(2, calls)

        now += 15_000L
        // One probe after the window; it fails, so the next window starts.
        fails(IOException("timeout"))
        assertThrows(ServerUnreachableException::class.java) { reachability.check() }
        now += 15_000L
        reachability.request { calls++ }
        reachability.request { calls++ }
        assertEquals(5, calls)
    }

    @Test
    fun `an answer from the server, even a refusal, clears the failures`() {
        fails(IOException("timeout"))
        fails(NextcloudHttpException(404, "missing"))
        fails(IOException("timeout"))
        reachability.check()
    }

    @Test
    fun `cancelled requests and server errors are told apart`() {
        fails(IOException("Canceled"))
        fails(IOException("Canceled"))
        reachability.check()
        fails(NextcloudHttpException(503, "maintenance"))
        fails(NextcloudHttpException(502, "bad gateway"))
        assertThrows(ServerUnreachableException::class.java) { reachability.check() }
    }
}
