package com.keithvassallo.ncmediaprovider.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.BuildConfig
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.activation.DeviceConfigUserService
import com.keithvassallo.ncmediaprovider.activation.IActivationService
import com.keithvassallo.ncmediaprovider.data.CredentialStore
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.LibrarySyncWorker
import com.keithvassallo.ncmediaprovider.data.SyncProgress
import com.keithvassallo.ncmediaprovider.databinding.ActivitySetupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private var shizukuActivationRunning = false
    private var shizukuServiceConnected = false
    private var shizukuAttempt = 0
    private var syncWasRunning = false

    private val shizukuUserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, DeviceConfigUserService::class.java.name),
        )
            .daemon(false)
            .tag("nc-media-provider-activation")
            .processNameSuffix("activation")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuUi()
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        if (shizukuActivationRunning) {
            finishShizukuActivation(
                Result.failure(IllegalStateException(getString(R.string.shizuku_service_disconnected))),
            )
        } else {
            updateShizukuUi()
        }
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) {
            activateWithShizuku()
        } else {
            updateShizukuUi()
            binding.shizukuStatus.setText(R.string.shizuku_permission_denied)
        }
    }

    private val shizukuServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!shizukuActivationRunning || shizukuServiceConnected) return
            shizukuServiceConnected = true
            if (!service.pingBinder()) {
                finishShizukuActivation(Result.failure(IllegalStateException("Invalid service binder")))
                return
            }
            val activationService = IActivationService.Stub.asInterface(service)
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { activationService.activate() }
                }
                finishShizukuActivation(result)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (shizukuActivationRunning) {
                finishShizukuActivation(
                    Result.failure(IllegalStateException(getString(R.string.shizuku_service_disconnected))),
                )
            }
        }
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        repository.onLocalMediaAccessChanged()
        updateMediaPermissionUi()
    }

    private val localNetworkRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { updateLocalNetworkUi() }

    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickerTest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.adbCommands.text = ActivationCommands.enable(BuildConfig.APPLICATION_ID)
        binding.restoreCommands.text = ActivationCommands.restore(BuildConfig.APPLICATION_ID)
        binding.grantMediaButton.setOnClickListener {
            permissionRequest.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                ),
            )
        }
        binding.grantLocalNetworkButton.setOnClickListener { localNetworkRequest.launch(ACCESS_LOCAL_NETWORK) }
        binding.copyCommandsButton.setOnClickListener { copyCommands() }
        binding.testPickerButton.setOnClickListener { openSystemPicker() }
        binding.pickerSettingsButton.setOnClickListener { openPickerSettings() }
        binding.shizukuButton.setOnClickListener { handleShizukuAction() }
        binding.refreshButton.setOnClickListener { repository.refreshNow() }
        binding.signInButton.setOnClickListener { startActivity(Intent(this, SignInActivity::class.java)) }
        binding.foldersButton.setOnClickListener { startActivity(Intent(this, FolderPickerActivity::class.java)) }
        binding.signOutButton.setOnClickListener { confirmSignOut() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.syncJobs().collect(::showSyncJobs)
            }
        }

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        updateConnectionUi()
        updateMediaPermissionUi()
        updateActivationUi()
        updateShizukuUi()
        updateCacheUi()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionUi()
        maybeAskForNotifications()
        updateLocalNetworkUi()
        updateMediaPermissionUi()
        updateActivationUi()
        updateShizukuUi()
        updateCacheUi()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (shizukuActivationRunning) {
            shizukuActivationRunning = false
            shizukuServiceConnected = false
            runCatching {
                Shizuku.unbindUserService(
                    shizukuUserServiceArgs,
                    shizukuServiceConnection,
                    true,
                )
            }
        }
        super.onDestroy()
    }

    /** The Server card: what the account needs next, and the actions that fit (PLAN 4.1 to 4.5). */
    private fun updateConnectionUi() {
        val account: CredentialStore.SavedAccount? = repository.account()
        val ready = repository.isReady
        binding.signInButton.visibility = if (account == null || repository.signInRequired) View.VISIBLE else View.GONE
        binding.signInButton.setText(if (account == null) R.string.action_sign_in else R.string.action_sign_in_again)
        binding.foldersButton.visibility = if (account != null && !repository.signInRequired) View.VISIBLE else View.GONE
        binding.foldersButton.setText(if (repository.foldersChosen) R.string.action_change_folders else R.string.action_choose_folders)
        binding.refreshButton.visibility = if (ready) View.VISIBLE else View.GONE
        binding.signOutButton.visibility = if (account != null) View.VISIBLE else View.GONE
        if (account == null) {
            binding.connectionStatus.setText(R.string.not_connected)
        } else if (repository.signInRequired) {
            binding.connectionStatus.setText(R.string.sign_in_required_status)
        } else if (!repository.foldersChosen) {
            binding.connectionStatus.text = getString(R.string.folders_needed_status, account.userId, account.baseUrl)
        } else {
            binding.connectionStatus.text = getString(
                R.string.connection_ready, account.userId, account.baseUrl, folderList(), getString(R.string.library_counting),
            )
            lifecycleScope.launch {
                // Room refuses to query on the main thread.
                val count = withContext(Dispatchers.IO) { runCatching { repository.itemCount() }.getOrNull() }
                val listed = when {
                    count == null || count == 0 -> getString(R.string.library_not_listed)
                    else -> resources.getQuantityString(R.plurals.library_listed, count, count)
                }
                binding.connectionStatus.text =
                    getString(R.string.connection_ready, account.userId, account.baseUrl, folderList(), listed)
            }
        }
    }

    private fun folderList(): String = repository.folders().joinToString(", ")

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_message)
            .setPositiveButton(R.string.action_sign_out) { _, _ ->
                lifecycleScope.launch {
                    val revoked = withContext(Dispatchers.IO) { repository.signOut() }
                    Toast.makeText(this@SetupActivity, if (revoked) R.string.sign_out_done else R.string.sign_out_offline, Toast.LENGTH_LONG).show()
                    updateConnectionUi()
                    updateCacheUi()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Notifications say when to sign in again or reselect the provider (PLAN 4.4, 4.6), so the
     * permission is asked for once, when there is an account to be told about.
     */
    private fun maybeAskForNotifications() {
        if (!repository.isReady || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        preferences.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
        notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Sync progress and the last check (PLAN 2.5), from the sync jobs' WorkManager state. */
    private suspend fun showSyncJobs(jobs: List<WorkInfo>) {
        binding.syncStatus.visibility = if (repository.isReady) View.VISIBLE else View.GONE
        if (!repository.isReady) {
            binding.syncProgress.visibility = View.GONE
            return
        }
        val running = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
        binding.syncProgress.visibility = if (running != null) View.VISIBLE else View.GONE
        binding.refreshButton.isEnabled = running == null
        if (running != null) {
            syncWasRunning = true
            binding.syncStatus.text = when (val progress = LibrarySyncWorker.progressOf(running)) {
                is SyncProgress.Listing -> if (progress.files == 0) {
                    getString(R.string.sync_listing_started)
                } else {
                    resources.getQuantityString(R.plurals.sync_listing, progress.files, progress.files)
                }
                else -> getString(R.string.sync_checking)
            }
            return
        }
        if (syncWasRunning) {
            syncWasRunning = false
            updateConnectionUi()
        }
        // The periodic job always waits in ENQUEUED between runs, so only one-off jobs count as queued.
        val queued = jobs.filter { it.state == WorkInfo.State.ENQUEUED }
        when {
            queued.any { it.runAttemptCount > 0 } -> binding.syncStatus.setText(R.string.sync_retrying)
            queued.any { it.periodicityInfo == null } -> binding.syncStatus.setText(R.string.sync_waiting)
            else -> {
                val last = withContext(Dispatchers.IO) { runCatching { repository.lastCheckMillis() }.getOrDefault(0L) }
                val now = System.currentTimeMillis()
                binding.syncStatus.text = when {
                    last == 0L -> getString(R.string.sync_never_checked)
                    now - last < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.sync_checked_just_now)
                    else -> getString(
                        R.string.sync_last_checked,
                        DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS),
                    )
                }
            }
        }
    }

    private fun updateLocalNetworkUi() {
        val granted = checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        binding.localNetworkStatus.setText(if (granted) R.string.local_network_granted else R.string.local_network_missing)
        binding.grantLocalNetworkButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun updateMediaPermissionUi() {
        val granted = repository.hasFullLocalMediaAccess
        binding.mediaPermissionStatus.setText(
            if (granted) R.string.media_access_granted else R.string.media_access_missing,
        )
        binding.grantMediaButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun updateActivationUi() {
        val authority = "${BuildConfig.APPLICATION_ID}.cloudmedia"
        val supported = runCatching {
            MediaStore.isSupportedCloudMediaProviderAuthority(contentResolver, authority)
        }.getOrDefault(false)
        val current = supported && runCatching {
            MediaStore.isCurrentCloudMediaProviderAuthority(contentResolver, authority)
        }.getOrDefault(false)
        binding.activationStatus.setText(
            when {
                current -> R.string.activation_status_active
                supported -> R.string.activation_status_select
                else -> R.string.activation_status_inactive
            },
        )
    }

    private fun openPickerSettings() {
        runCatching {
            startActivity(Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS))
        }.onFailure {
            Toast.makeText(this, R.string.picker_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleShizukuAction() {
        if (shizukuActivationRunning) return
        if (!isShizukuBinderAvailable()) {
            openShizukuManager()
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            openShizukuManager()
            return
        }

        val permission = runCatching { Shizuku.checkSelfPermission() }.getOrElse { error ->
            showShizukuFailure(error)
            return
        }
        if (permission == PackageManager.PERMISSION_GRANTED) {
            activateWithShizuku()
        } else if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            binding.shizukuStatus.setText(R.string.shizuku_permission_denied)
            openShizukuManager()
        } else {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
                .onFailure(::showShizukuFailure)
        }
    }

    private fun activateWithShizuku() {
        if (shizukuActivationRunning || !isShizukuBinderAvailable()) return
        shizukuActivationRunning = true
        shizukuServiceConnected = false
        val attempt = ++shizukuAttempt
        updateShizukuUi()
        runCatching {
            Shizuku.bindUserService(shizukuUserServiceArgs, shizukuServiceConnection)
        }.onFailure { error ->
            finishShizukuActivation(Result.failure(error))
            return
        }
        lifecycleScope.launch {
            delay(SHIZUKU_BIND_TIMEOUT_MS)
            if (
                shizukuActivationRunning &&
                !shizukuServiceConnected &&
                shizukuAttempt == attempt
            ) {
                finishShizukuActivation(
                    Result.failure(IllegalStateException(getString(R.string.shizuku_timeout))),
                )
            }
        }
    }

    private fun finishShizukuActivation(result: Result<String>) {
        if (!shizukuActivationRunning) return
        shizukuActivationRunning = false
        shizukuServiceConnected = false
        runCatching {
            Shizuku.unbindUserService(
                shizukuUserServiceArgs,
                shizukuServiceConnection,
                true,
            )
        }
        updateActivationUi()
        updateShizukuUi()
        result.onSuccess {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shizuku_activation_saved)
                .setMessage(R.string.shizuku_activation_saved_message)
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.open_picker_settings) { _, _ -> openPickerSettings() }
                .show()
        }.onFailure(::showShizukuFailure)
    }

    private fun updateShizukuUi() {
        if (shizukuActivationRunning) {
            binding.shizukuStatus.setText(R.string.shizuku_activating)
            binding.shizukuProgress.visibility = View.VISIBLE
            binding.shizukuButton.isEnabled = false
            return
        }

        binding.shizukuProgress.visibility = View.GONE
        binding.shizukuButton.isEnabled = true
        if (!isShizukuBinderAvailable()) {
            binding.shizukuStatus.setText(R.string.shizuku_not_running)
            binding.shizukuButton.setText(R.string.open_shizuku)
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            binding.shizukuStatus.setText(R.string.shizuku_unsupported)
            binding.shizukuButton.setText(R.string.open_shizuku)
            return
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (permissionGranted) {
            binding.shizukuStatus.setText(R.string.shizuku_ready)
            binding.shizukuButton.setText(R.string.activate_with_shizuku)
        } else {
            val denied = runCatching { Shizuku.shouldShowRequestPermissionRationale() }
                .getOrDefault(false)
            binding.shizukuStatus.setText(
                if (denied) R.string.shizuku_permission_denied else R.string.shizuku_permission_needed,
            )
            binding.shizukuButton.setText(
                if (denied) R.string.open_shizuku else R.string.grant_shizuku_access,
            )
        }
    }

    private fun isShizukuBinderAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun openShizukuManager() {
        val managerIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
            ?: Intent(Intent.ACTION_VIEW, SHIZUKU_DOWNLOAD_URL.toUri())
        runCatching { startActivity(managerIntent) }.onFailure {
            Toast.makeText(this, R.string.shizuku_manager_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showShizukuFailure(error: Throwable) {
        val detail = error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(240)
            ?.ifBlank { null }
            ?: error.javaClass.simpleName
        binding.shizukuStatus.text = getString(R.string.shizuku_activation_failed, detail)
    }

    private fun updateCacheUi() {
        binding.cacheSummary.setText(R.string.cache_loading)
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { repository.cacheStats() }
            binding.cacheSummary.text = getString(
                R.string.cache_summary,
                Formatter.formatFileSize(this@SetupActivity, stats.previews.usedBytes),
                Formatter.formatFileSize(this@SetupActivity, stats.previews.maximumBytes),
                Formatter.formatFileSize(this@SetupActivity, stats.originals.usedBytes),
                Formatter.formatFileSize(this@SetupActivity, stats.originals.maximumBytes),
            )
        }
    }

    private fun copyCommands() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NC Media Provider ADB activation", binding.adbCommands.text))
        Toast.makeText(this, R.string.commands_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openSystemPicker() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        runCatching { pickerTest.launch(intent) }.onFailure {
            Toast.makeText(this, R.string.picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val UI_PREFERENCES = "ui"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val SHIZUKU_PERMISSION_REQUEST = 41
        private const val SHIZUKU_BIND_TIMEOUT_MS = 20_000L
        private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }
}
