package com.keithvassallo.ncmediaprovider.data

import android.os.OperationCanceledException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

class ReachabilityTest {
    @Test
    fun `network failures and server errors arm the back-off`() {
        assertTrue(isReachabilityFailure(SocketTimeoutException("connect timed out")))
        assertTrue(isReachabilityFailure(NextcloudHttpException(503, "maintenance")))
        assertTrue(isReachabilityFailure(NextcloudHttpException(429, "slow down")))
    }

    @Test
    fun `cancelled requests and missing previews do not`() {
        assertFalse(isReachabilityFailure(IOException("Canceled")))
        assertFalse(isReachabilityFailure(OperationCanceledException()))
        assertFalse(isReachabilityFailure(FileNotFoundException("No server preview")))
        assertFalse(isReachabilityFailure(NextcloudHttpException(404, "gone")))
    }
}
