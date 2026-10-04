package com.keithvassallo.ncmediaprovider.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bug report masks the server and the user name (PLAN 9.5). */
class BugReportTest {
    private val secrets = mapOf(
        "https://cloud.example.org" to "<server>",
        "cloud.example.org" to "<server>",
        "keith" to "<user>",
    )

    @Test
    fun `the server and the user name are masked wherever they appear`() {
        val log = "GET https://cloud.example.org/remote.php/dav/files/keith/Photos failed; host cloud.example.org, user KEITH"
        assertEquals(
            "GET <server>/remote.php/dav/files/<user>/Photos failed; host <server>, user <user>",
            BugReport.redact(log, secrets),
        )
    }

    @Test
    fun `a user name inside a longer word is left alone`() {
        val line = "Process: com.keithvassallo.ncmediaprovider, keithv's phone"
        assertEquals(line, BugReport.redact(line, secrets))
    }
}
