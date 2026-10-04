package com.keithvassallo.ncmediaprovider.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.activation.DeviceConfigUserService
import com.keithvassallo.ncmediaprovider.activation.IActivationService
import com.keithvassallo.ncmediaprovider.activation.ShizukuSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Activation through Shizuku (PLAN 4.6), for whichever screen offers it: [act] does what the state
 * calls for next (open Shizuku, ask it for access, or activate), [turnOff] the same towards undoing
 * it, [onChange] reports every state, and [onResult] how it went: [RESULT_SELECTED],
 * [DeviceConfigUserService.RESULT_RESTART], allowed but not selected, or
 * [DeviceConfigUserService.RESULT_CLEARED]. Create it in the activity's constructor or onCreate.
 */
class ShizukuActivator(
    private val activity: AppCompatActivity,
    private val keepGooglePhotos: () -> Boolean,
    private val onChange: (State) -> Unit,
    private val onResult: (Result<String>) -> Unit,
) : DefaultLifecycleObserver {
    enum class State { NOT_RUNNING, UNSUPPORTED, NEEDS_PERMISSION, DENIED, READY, ACTIVATING }

    var state: State = State.NOT_RUNNING
        private set

    private var running = false

    /** Whether the next run undoes activation instead. */
    private var undoing = false
    private var connected = false
    private var attempt = 0

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        if (running) finish(Result.failure(IllegalStateException(activity.getString(R.string.shizuku_service_disconnected)))) else refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) start() else refresh()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!running || connected) return
            connected = true
            if (!service.pingBinder()) return finish(Result.failure(IllegalStateException("Invalid service binder")))
            val activation = IActivationService.Stub.asInterface(service)
            activity.lifecycleScope.launch {
                val keep = keepGooglePhotos()
                val undo = undoing
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        if (undo) return@withContext activation.deactivate()
                        val outcome = activation.activate(keep)
                        if (outcome == DeviceConfigUserService.RESULT_ACTIVE && activation.selectProvider()) RESULT_SELECTED else outcome
                    }
                }
                finish(result)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (running) finish(Result.failure(IllegalStateException(activity.getString(R.string.shizuku_service_disconnected))))
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onResume(owner: LifecycleOwner) = refresh()

    override fun onDestroy(owner: LifecycleOwner) {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (running) {
            running = false
            connected = false
            runCatching { Shizuku.unbindUserService(ShizukuSession.userServiceArgs, connection, true) }
        }
    }

    fun refresh() {
        state = when {
            running -> State.ACTIVATING
            !binderAvailable() -> State.NOT_RUNNING
            runCatching { Shizuku.isPreV11() }.getOrDefault(true) -> State.UNSUPPORTED
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) -> State.READY
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false) -> State.DENIED
            else -> State.NEEDS_PERMISSION
        }
        onChange(state)
    }

    /** The next step: open Shizuku, ask it for access, or activate. */
    fun act() = next(undo = false)

    /** The next step towards undoing activation: open Shizuku, ask it for access, or turn off. */
    fun turnOff() = next(undo = true)

    private fun next(undo: Boolean) {
        refresh()
        if (state == State.ACTIVATING) return
        undoing = undo
        when (state) {
            State.ACTIVATING -> Unit
            State.NOT_RUNNING, State.UNSUPPORTED, State.DENIED -> openManager()
            State.NEEDS_PERMISSION -> runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }.onFailure { onResult(Result.failure(it)) }
            State.READY -> start()
        }
    }

    private fun start() {
        if (running || !binderAvailable()) return
        running = true
        connected = false
        val current = ++attempt
        refresh()
        runCatching { Shizuku.bindUserService(ShizukuSession.userServiceArgs, connection) }
            .onFailure { return finish(Result.failure(it)) }
        activity.lifecycleScope.launch {
            delay(BIND_TIMEOUT_MS)
            if (running && !connected && attempt == current) {
                finish(Result.failure(IllegalStateException(activity.getString(R.string.shizuku_timeout))))
            }
        }
    }

    private fun finish(result: Result<String>) {
        if (!running) return
        running = false
        connected = false
        runCatching { Shizuku.unbindUserService(ShizukuSession.userServiceArgs, connection, true) }
        refresh()
        onResult(result)
    }

    private fun binderAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun openManager() {
        val intent = activity.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, DOWNLOAD_URL.toUri())
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, R.string.shizuku_manager_unavailable, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val RESULT_SELECTED = "selected"
        private const val PERMISSION_REQUEST = 41
        private const val BIND_TIMEOUT_MS = 20_000L
        private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

        /** A failure's first line, for a status text. */
        fun describe(error: Throwable): String =
            error.message?.lineSequence()?.firstOrNull()?.take(240)?.ifBlank { null } ?: error.javaClass.simpleName
    }
}
