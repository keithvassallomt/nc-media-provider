package com.keithvassallo.ncmediaprovider.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
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
import com.keithvassallo.ncmediaprovider.databinding.ActivitySignInBinding
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
 * Signs in with Nextcloud's Login Flow v2 (PLAN 4.1): the user approves this app on their server's
 * own page in a browser tab, and the app polls for the app password it is given. Android 17 cuts
 * this app's network while it is in the background, so polling also runs each time the user comes
 * back; the flow survives the activity being recreated.
 */
class SignInActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignInBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private var baseUrl: String? = null
    private var flow: LoginFlow? = null
    private var deadline = 0L
    private var pollJob: Job? = null

    private val localNetworkRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch { checkServer() } else showError(getString(R.string.sign_in_local_network_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.let { state ->
            baseUrl = state.getString(STATE_URL)
            val token = state.getString(STATE_TOKEN)
            if (token != null) {
                flow = LoginFlow(state.getString(STATE_LOGIN_URL).orEmpty(), state.getString(STATE_ENDPOINT).orEmpty(), token)
                deadline = state.getLong(STATE_DEADLINE)
            }
        }
        if (savedInstanceState == null) repository.account()?.let { binding.serverField.setText(it.baseUrl) }

        binding.continueButton.setOnClickListener { start() }
        binding.serverField.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_GO).also { if (it) start() }
        }
        binding.reopenButton.setOnClickListener { openLoginPage() }
        if (flow != null) showWaiting()
    }

    override fun onResume() {
        super.onResume()
        if (flow != null && pollJob?.isActive != true) startPolling()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, baseUrl)
        flow?.let {
            outState.putString(STATE_LOGIN_URL, it.loginUrl)
            outState.putString(STATE_ENDPOINT, it.pollEndpoint)
            outState.putString(STATE_TOKEN, it.token)
            outState.putLong(STATE_DEADLINE, deadline)
        }
    }

    private fun start() {
        val url = ServerApi.normalizeServerUrl(binding.serverField.text?.toString().orEmpty())
            ?: return showError(getString(R.string.sign_in_invalid_address))
        baseUrl = url
        flow = null
        pollJob?.cancel()
        lifecycleScope.launch { checkServer() }
    }

    /** Checks the address, asks for local network access if it needs it, then opens the login page. */
    private suspend fun checkServer() {
        val url = baseUrl ?: return
        showBusy(getString(R.string.sign_in_checking))
        val isHttp = !url.toHttpUrl().isHttps
        val isLocal = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(url.toHttpUrl().host).any(::isLocalNetworkAddress) }.getOrDefault(false)
        }
        if (isHttp && !isLocal) return showError(getString(R.string.sign_in_http_public))
        binding.httpWarning.visibility = if (isHttp) View.VISIBLE else View.GONE
        if (isLocal && checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sign_in_local_network_title)
                .setMessage(R.string.sign_in_local_network_message)
                .setPositiveButton(R.string.sign_in_local_network_allow) { _, _ -> localNetworkRequest.launch(ACCESS_LOCAL_NETWORK) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> showIdle() }
                .setOnCancelListener { showIdle() }
                .show()
            return
        }
        val status = withContext(Dispatchers.IO) { runCatching { repository.serverStatus(url) } }
            .getOrElse { return showError(describe(it)) }
        when {
            !status.installed -> return showError(getString(R.string.sign_in_not_installed))
            status.maintenance -> return showError(getString(R.string.sign_in_maintenance))
        }
        flow = withContext(Dispatchers.IO) { runCatching { repository.startLogin(url) } }
            .getOrElse { return showError(describe(it)) }
        deadline = SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS
        openLoginPage()
        startPolling()
    }

    private fun openLoginPage() {
        val loginUrl = flow?.loginUrl ?: return
        showWaiting()
        runCatching { CustomTabsIntent.Builder().build().launchUrl(this, loginUrl.toUri()) }
            .recoverCatching { startActivity(Intent(Intent.ACTION_VIEW, loginUrl.toUri())) }
            .onFailure { showError(getString(R.string.sign_in_no_browser)) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive && !pollOnce()) delay(POLL_INTERVAL_MS)
        }
    }

    /** True once polling is over: approved, expired or abandoned. Failed polls just try again. */
    private suspend fun pollOnce(): Boolean {
        val url = baseUrl ?: return true
        val current = flow ?: return true
        if (SystemClock.elapsedRealtime() > deadline) {
            flow = null
            showError(getString(R.string.sign_in_expired))
            return true
        }
        val grant = withContext(Dispatchers.IO) { runCatching { repository.pollLogin(url, current) }.getOrNull() } ?: return false
        complete(url, grant)
        return true
    }

    private suspend fun complete(url: String, grant: LoginGrant) {
        flow = null
        showBusy(getString(R.string.sign_in_finishing))
        withContext(Dispatchers.IO) { runCatching { repository.completeSignIn(url, grant) } }
            .getOrElse { return showError(describe(it)) }
        if (!repository.foldersChosen) startActivity(Intent(this, FolderPickerActivity::class.java))
        finish()
    }

    private fun describe(error: Throwable): String = getString(
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

    private fun showBusy(message: String) {
        binding.status.text = message
        binding.progress.visibility = View.VISIBLE
        binding.continueButton.isEnabled = false
        binding.reopenButton.visibility = View.GONE
    }

    private fun showWaiting() {
        binding.status.setText(R.string.sign_in_waiting)
        binding.progress.visibility = View.VISIBLE
        binding.continueButton.isEnabled = true
        binding.reopenButton.visibility = View.VISIBLE
    }

    private fun showIdle() {
        binding.status.text = ""
        binding.progress.visibility = View.GONE
        binding.continueButton.isEnabled = true
    }

    private fun showError(message: String) {
        showIdle()
        binding.status.text = message
        binding.reopenButton.visibility = View.GONE
    }

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        // Nextcloud keeps a login flow open for 20 minutes.
        const val LOGIN_TIMEOUT_MS = 20L * 60L * 1_000L
        const val POLL_INTERVAL_MS = 3_000L

        const val STATE_URL = "url"
        const val STATE_LOGIN_URL = "login_url"
        const val STATE_ENDPOINT = "endpoint"
        const val STATE_TOKEN = "token"
        const val STATE_DEADLINE = "deadline"
    }
}
