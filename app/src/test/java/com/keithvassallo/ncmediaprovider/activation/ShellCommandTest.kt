package com.keithvassallo.ncmediaprovider.activation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandTest {
    @Test
    fun `output larger than the pipe is read in full, without waiting for the timeout`() {
        val started = System.nanoTime()
        // 200 KB, past the 64 KB pipe that deadlocked MediaProvider's dumpsys on a stock Pixel.
        val result = ShellCommand.run(listOf("sh", "-c", "head -c 200000 /dev/zero | tr '\\\\0' x"), timeoutSeconds = 10)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(result.successful)
        assertEquals(200_000, result.output.length)
        assertTrue("took $seconds s", seconds < 5)
    }

    @Test
    fun `failures and timeouts are reported`() {
        assertFalse(ShellCommand.run(listOf("sh", "-c", "echo Error: no such flag; exit 0"), timeoutSeconds = 5).successful)
        assertFalse(ShellCommand.run(listOf("sh", "-c", "exit 3"), timeoutSeconds = 5).successful)
        assertEquals("command timed out", ShellCommand.run(listOf("sleep", "5"), timeoutSeconds = 1).output)
        assertFalse(ShellCommand.run(listOf("/no/such/binary"), timeoutSeconds = 1).successful)
    }
}
