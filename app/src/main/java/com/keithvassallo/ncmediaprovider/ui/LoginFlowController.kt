package com.keithvassallo.ncmediaprovider.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.LoginFlow
import com.keithvassallo.ncmediaprovider.data.LoginGrant
import com.keithvassallo.ncmediaprovider.data.NextcloudHttpException
import com.keithvassallo.ncmediaprovider.data.PlainHttpNotAllowedException
import com.keithvassallo.ncmediaprovider.data.ServerApi
import com.keithvassallo.ncmediaprovider.data.isLocalNetworkAddress
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException

/**
 * Signs in with Nextcloud's Login Flow v2 (PLAN 4.1), for whichever screen hosts it: the user
 * approves this app on their server's own page in a browser tab, and the app polls for the app
 * password it is given. Android 17 cuts this app's network while it is in the background, so
 * polling also runs each time the user comes back; [save] and [restore] carry the flow across the
 * activity being recreated. Create it as a field of the activity: it registers for a permission result.
 */
class LoginFlowController(
    private val activity: AppCompatActivity,
    private val onState: (State) -> Unit,
) : DefaultLifecycleObserver {
    sealed interface State {
        data object Idle : State
        data class Busy(val message: String) : State
        data object Waiting : State
        data class Failed(val message: String) : State
        data object SignedIn : State
    }

    private val repository by lazy { LibraryRepository.get(activity.applicationContext) }
    private var baseUrl: String? = null
    private var flow: LoginFlow? = null
    private var deadline = 0L
    private var pollJob: Job? = null

    /** Whether the address being signed in to uses plain HTTP, which the screen warns about. */
    var plainHttp = false
        private set

    private val localNetworkRequest = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) activity.lifecycleScope.launch { checkServer() } else fail(activity.getString(R.string.sign_in_local_network_denied))
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    val isWaiting: Boolean get() = flow != null

    fun restore(state: Bundle?) {
        state ?: return
        baseUrl = state.getString(STATE_URL)
        val token = state.getString(STATE_TOKEN) ?: return
        flow = LoginFlow(state.getString(STATE_LOGIN_URL).orEmpty(), state.getString(STATE_ENDPOINT).orEmpty(), token)
        deadline = state.getLong(STATE_DEADLINE)
        onState(State.Waiting)
    }

    fun save(outState: Bundle) {
        outState.putString(STATE_URL, baseUrl)
        flow?.let {
            outState.putString(STATE_LOGIN_URL, it.loginUrl)
            outState.putString(STATE_ENDPOINT, it.pollEndpoint)
            outState.putString(STATE_TOKEN, it.token)
            outState.putLong(STATE_DEADLINE, deadline)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (flow != null && pollJob?.isActive != true) startPolling()
    }

    /** Starts over with what the user typed. */
    fun start(typed: String) {
        val url = ServerApi.normalizeServerUrl(typed) ?: return fail(activity.getString(R.string.sign_in_invalid_address))
        baseUrl = url
        flow = null
        pollJob?.cancel()
        activity.lifecycleScope.launch { checkServer() }
    }

    /** Stops waiting, back to the address. */
    fun cancel() {
        flow = null
        pollJob?.cancel()
        onState(State.Idle)
    }

    /** Opens the login page again, if the user closed the browser tab. */
    fun reopen() {
        val loginUrl = flow?.loginUrl ?: return
        onState(State.Waiting)
        runCatching { CustomTabsIntent.Builder().build().launchUrl(activity, loginUrl.toUri()) }
            .recoverCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, loginUrl.toUri())) }
            .onFailure { fail(activity.getString(R.string.sign_in_no_browser)) }
    }

    /** Checks the address, asks for local network access if it needs it, then opens the login page. */
    private suspend fun checkServer() {
        val url = baseUrl ?: return
        onState(State.Busy(activity.getString(R.string.sign_in_checking)))
        val isHttp = !url.toHttpUrl().isHttps
        val isLocal = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(url.toHttpUrl().host).any(::isLocalNetworkAddress) }.getOrDefault(false)
        }
        if (isHttp && !isLocal) return fail(activity.getString(R.string.sign_in_http_public))
        plainHttp = isHttp
        if (isLocal && activity.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sign_in_local_network_title)
                .setMessage(R.string.sign_in_local_network_message)
                .setPositiveButton(R.string.sign_in_local_network_allow) { _, _ -> localNetworkRequest.launch(ACCESS_LOCAL_NETWORK) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onState(State.Idle) }
                .setOnCancelListener { onState(State.Idle) }
                .show()
            return
        }
        val status = withContext(Dispatchers.IO) { runCatching { repository.serverStatus(url) } }
            .getOrElse { return fail(describe(it)) }
        when {
            !status.installed -> return fail(activity.getString(R.string.sign_in_not_installed))
            status.maintenance -> return fail(activity.getString(R.string.sign_in_maintenance))
        }
        flow = withContext(Dispatchers.IO) { runCatching { repository.startLogin(url) } }
            .getOrElse { return fail(describe(it)) }
        deadline = SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS
        reopen()
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = activity.lifecycleScope.launch {
            while (isActive && !pollOnce()) delay(POLL_INTERVAL_MS)
        }
    }

    /** True once polling is over: approved, expired or abandoned. Failed polls just try again. */
    private suspend fun pollOnce(): Boolean {
        val url = baseUrl ?: return true
        val current = flow ?: return true
        if (SystemClock.elapsedRealtime() > deadline) {
            flow = null
            fail(activity.getString(R.string.sign_in_expired))
            return true
        }
        val grant = withContext(Dispatchers.IO) { runCatching { repository.pollLogin(url, current) }.getOrNull() } ?: return false
        complete(url, grant)
        return true
    }

    private suspend fun complete(url: String, grant: LoginGrant) {
        flow = null
        onState(State.Busy(activity.getString(R.string.sign_in_finishing)))
        withContext(Dispatchers.IO) { runCatching { repository.completeSignIn(url, grant) } }
            .getOrElse { return fail(describe(it)) }
        onState(State.SignedIn)
    }

    private fun fail(message: String) = onState(State.Failed(message))

    private fun describe(error: Throwable): String = activity.getString(
        when (error) {
            is UnknownHostException -> R.string.sign_in_unknown_host
            is SSLException, is CertificateException -> R.string.sign_in_certificate
            is PlainHttpNotAllowedException -> R.string.sign_in_http_public
            is ConnectException, is SocketTimeoutException -> R.string.sign_in_unreachable
            is JSONException -> R.string.sign_in_not_nextcloud
            is NextcloudHttpException -> if (error.statusCode == 404) R.string.sign_in_not_nextcloud else R.string.sign_in_server_error
            else -> R.string.sign_in_failed
        },
    )

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        // Nextcloud keeps a login flow open for 20 minutes.
        const val LOGIN_TIMEOUT_MS = 20L * 60L * 1_000L
        const val POLL_INTERVAL_MS = 3_000L

        const val STATE_URL = "login_url_base"
        const val STATE_LOGIN_URL = "login_url"
        const val STATE_ENDPOINT = "login_endpoint"
        const val STATE_TOKEN = "login_token"
        const val STATE_DEADLINE = "login_deadline"
    }
}
