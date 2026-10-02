package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class ServerApiTest {
    @Test
    fun `typed addresses become base URLs`() {
        assertEquals("https://cloud.example.com", ServerApi.normalizeServerUrl(" cloud.example.com/ "))
        assertEquals("https://cloud.example.com/nextcloud", ServerApi.normalizeServerUrl("cloud.example.com/nextcloud/"))
        assertEquals("http://192.168.1.5:8080", ServerApi.normalizeServerUrl("http://192.168.1.5:8080"))
        // Pasted from the address bar while using Nextcloud.
        assertEquals("https://cloud.example.com", ServerApi.normalizeServerUrl("https://cloud.example.com/index.php/apps/files/?dir=/Photos"))
        assertEquals("https://example.com/nc", ServerApi.normalizeServerUrl("https://example.com/nc/apps/memories/"))
        assertEquals("https://cloud.example.com", ServerApi.normalizeServerUrl("https://cloud.example.com/login"))
        assertNull(ServerApi.normalizeServerUrl("  "))
        assertNull(ServerApi.normalizeServerUrl("ftp://cloud.example.com"))
    }

    @Test
    fun `the granted address is kept, but never downgraded to http`() {
        assertEquals("https://example.com/nextcloud", ServerApi.chooseBaseUrl("https://example.com", "https://example.com/nextcloud/"))
        assertEquals("https://example.com", ServerApi.chooseBaseUrl("https://example.com", "http://example.com/"))
        assertEquals("http://192.168.1.5", ServerApi.chooseBaseUrl("http://192.168.1.5", "http://192.168.1.5/"))
        assertEquals("https://other.example.com", ServerApi.chooseBaseUrl("https://example.com", "https://other.example.com"))
        assertEquals("https://example.com/login/v2/poll", ServerApi.pollUrl("https://example.com", "http://example.com/login/v2/poll").toString())
    }

    @Test
    fun `parses the account endpoints`() {
        val fixtures = File("../testdata/nextcloud/35-full")
        val status = ServerApi.parseStatus(File(fixtures, "status.json").readText())
        assertTrue(status.installed)
        assertFalse(status.maintenance)
        assertEquals(35, status.majorVersion)
        assertEquals("alice", ServerApi.parseUserId(File(fixtures, "user.json").readText()))
        assertEquals(listOf("/Photos"), ServerApi.parseTimelinePaths(File(fixtures, "memories-config.json").readText()))
        assertEquals(listOf("/Photos", "/Camera"), ServerApi.parseTimelinePaths("""{"timeline_path":"/Photos;/Camera;"}"""))
        assertTrue(ServerApi.parseTimelinePaths("""{"timeline_path":"/"}""").isEmpty())

        val flow = ServerApi.parseLoginFlow(
            """{"poll":{"token":"t0k3n","endpoint":"https://example.com/login/v2/poll"},"login":"https://example.com/login/v2/flow/abc"}""",
        )
        assertEquals(LoginFlow("https://example.com/login/v2/flow/abc", "https://example.com/login/v2/poll", "t0k3n"), flow)
        assertEquals(
            LoginGrant("https://example.com", "alice@example.com", "secret"),
            ServerApi.parseLoginGrant("""{"server":"https://example.com","loginName":"alice@example.com","appPassword":"secret"}"""),
        )
        assertTrue(ServerApi.parseWipe("""{"wipe":true}"""))
    }

    @Test
    fun `local network addresses`() {
        for (local in listOf("192.168.1.5", "10.0.0.2", "172.20.1.1", "127.0.0.1", "169.254.3.4", "100.101.102.103", "fd12::1", "fe80::1", "::1")) {
            assertTrue(local, isLocalNetworkAddress(InetAddress.getByName(local)))
        }
        for (public in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2a00:1450::1")) {
            assertFalse(public, isLocalNetworkAddress(InetAddress.getByName(public)))
        }
    }
}
