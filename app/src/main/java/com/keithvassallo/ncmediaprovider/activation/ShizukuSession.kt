package com.keithvassallo.ncmediaprovider.activation

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.keithvassallo.ncmediaprovider.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs [DeviceConfigUserService] through Shizuku, without root (#31). */
object ShizukuSession {
    val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, DeviceConfigUserService::class.java.name))
            .daemon(false)
            .tag("nc-media-provider-activation")
            .processNameSuffix("activation")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /**
     * Whether Shizuku is running and this app may use it, waiting up to [waitMillis] for its binder:
     * a freshly started process only receives it shortly after start-up. Blocks; not on the main thread.
     */
    fun isUsable(waitMillis: Long = 0L): Boolean {
        if (waitMillis > 0L && !ping()) {
            val received = CountDownLatch(1)
            val listener = Shizuku.OnBinderReceivedListener { received.countDown() }
            Shizuku.addBinderReceivedListenerSticky(listener)
            received.await(waitMillis, TimeUnit.MILLISECONDS)
            Shizuku.removeBinderReceivedListener(listener)
        }
        return ping() &&
            runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }

    /** Binds the service, runs [block] and unbinds; null if it didn't connect in time. Blocks. */
    fun <T> withService(timeoutMillis: Long = BIND_TIMEOUT_MS, block: (IActivationService) -> T): T? {
        val connected = CountDownLatch(1)
        var service: IActivationService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (binder.pingBinder()) service = IActivationService.Stub.asInterface(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        Shizuku.bindUserService(userServiceArgs, connection)
        return try {
            if (!connected.await(timeoutMillis, TimeUnit.MILLISECONDS)) null else service?.let(block)
        } finally {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
    }

    private fun ping() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private const val BIND_TIMEOUT_MS = 20_000L
}
