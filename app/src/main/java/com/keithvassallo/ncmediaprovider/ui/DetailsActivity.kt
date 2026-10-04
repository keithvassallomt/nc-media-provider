package com.keithvassallo.ncmediaprovider.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.BuildConfig
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.activation.DeviceConfigUserService
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.LibrarySyncWorker
import com.keithvassallo.ncmediaprovider.data.SyncProgress
import com.keithvassallo.ncmediaprovider.databinding.ActivityDetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Details and troubleshooting: sync, whether Android lists and selects this app, activation with
 * Shizuku or adb and how to undo it, local network access, and diagnostics (#31). Everything
 * the home screen keeps out of the way.
 */
class DetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailsBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private val preferences by lazy { UiPreferences(this) }
    private var syncWasRunning = false

    private val shizuku = ShizukuActivator(
        activity = this,
        keepGooglePhotos = { preferences.keepGooglePhotos },
        onChange = { renderShizuku(it) },
        onResult = { onShizukuResult(it) },
    )
    private val localNetworkRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderLocalNetwork() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.refreshButton.setOnClickListener { repository.refreshNow() }
        binding.keepGooglePhotosSwitch.visibility = if (preferences.googlePhotosInstalled) View.VISIBLE else View.GONE
        binding.keepGooglePhotosSwitch.isChecked = preferences.keepGooglePhotos
        binding.keepGooglePhotosSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.keepGooglePhotos = checked
            renderCommands()
        }
        binding.pickerSettingsButton.setOnClickListener { openPickerSettings() }
        binding.testPickerButton.setOnClickListener { openSystemPicker() }
        binding.setupAgainButton.setOnClickListener { startActivity(android.content.Intent(this, OnboardingActivity::class.java)) }
        binding.shizukuButton.setOnClickListener { shizuku.act() }
        binding.turnOffButton.setOnClickListener { confirmTurnOff() }
        binding.shareDiagnosticsButton.setOnClickListener { shareDiagnostics() }
        binding.copyCommandsButton.setOnClickListener { copy(binding.adbCommands.text) }
        binding.copyRestoreButton.setOnClickListener { copy(binding.restoreCommands.text) }
        binding.grantLocalNetworkButton.setOnClickListener { localNetworkRequest.launch(ACCESS_LOCAL_NETWORK) }
        binding.restoreCommands.text = ActivationCommands.restore(BuildConfig.APPLICATION_ID)
        renderCommands()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.syncJobs().collect { renderSync(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderActivation()
        renderLocalNetwork()
        renderDiagnostics()
    }

    private fun renderCommands() {
        binding.adbCommands.text = ActivationCommands.enable(BuildConfig.APPLICATION_ID, preferences.keepGooglePhotos)
    }

    private fun renderActivation() {
        val activation = PickerActivation.read(this)
        binding.turnOffButton.visibility = if (activation.allowed) View.VISIBLE else View.GONE
        binding.activationStatus.setText(
            when {
                activation.selected -> R.string.activation_status_active
                activation.allowed -> R.string.activation_status_select
                else -> R.string.activation_status_inactive
            },
        )
        if (repository.hasAccount) repository.noteSelectedProvider(activation.selected)
    }

    private fun renderShizuku(state: ShizukuActivator.State) {
        if (!::binding.isInitialized) return
        binding.shizukuProgress.visibility = if (state == ShizukuActivator.State.ACTIVATING) View.VISIBLE else View.GONE
        binding.shizukuButton.isEnabled = state != ShizukuActivator.State.ACTIVATING
        binding.turnOffButton.isEnabled = state != ShizukuActivator.State.ACTIVATING
        binding.shizukuButton.setText(
            when (state) {
                ShizukuActivator.State.NOT_RUNNING, ShizukuActivator.State.UNSUPPORTED, ShizukuActivator.State.DENIED -> R.string.open_shizuku
                ShizukuActivator.State.NEEDS_PERMISSION -> R.string.grant_shizuku_access
                ShizukuActivator.State.READY, ShizukuActivator.State.ACTIVATING -> R.string.activate_with_shizuku
            },
        )
        binding.shizukuStatus.setText(
            when (state) {
                ShizukuActivator.State.NOT_RUNNING -> R.string.shizuku_not_running
                ShizukuActivator.State.UNSUPPORTED -> R.string.shizuku_unsupported
                ShizukuActivator.State.NEEDS_PERMISSION -> R.string.shizuku_permission_needed
                ShizukuActivator.State.DENIED -> R.string.shizuku_permission_denied
                ShizukuActivator.State.READY -> R.string.shizuku_ready
                ShizukuActivator.State.ACTIVATING -> R.string.shizuku_activating
            },
        )
    }

    private fun onShizukuResult(result: Result<String>) {
        renderActivation()
        result.onSuccess { outcome ->
            val dialog = MaterialAlertDialogBuilder(this)
            when (outcome) {
                DeviceConfigUserService.RESULT_CLEARED -> dialog
                    .setTitle(R.string.turned_off_title)
                    .setMessage(R.string.turned_off_message)
                    .setPositiveButton(android.R.string.ok, null)
                ShizukuActivator.RESULT_SELECTED -> dialog
                    .setTitle(R.string.shizuku_activation_saved)
                    .setMessage(R.string.shizuku_activation_selected_message)
                    .setPositiveButton(android.R.string.ok, null)
                DeviceConfigUserService.RESULT_RESTART -> dialog
                    .setTitle(R.string.shizuku_activation_restart_title)
                    .setMessage(R.string.shizuku_activation_restart_message)
                    .setPositiveButton(android.R.string.ok, null)
                else -> dialog
                    .setTitle(R.string.shizuku_activation_saved)
                    .setMessage(R.string.shizuku_activation_saved_message)
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.open_picker_settings) { _, _ -> openPickerSettings() }
            }
            dialog.show()
        }.onFailure { binding.shizukuStatus.text = getString(R.string.shizuku_activation_failed, ShizukuActivator.describe(it)) }
    }

    /** Sync progress and the last check (#16), from the sync jobs' WorkManager state. */
    private suspend fun renderSync(jobs: List<WorkInfo>) {
        val running = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
        binding.syncProgress.visibility = if (running != null) View.VISIBLE else View.GONE
        binding.refreshButton.isEnabled = repository.isReady && running == null
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
            renderDiagnostics()
        }
        // The periodic job always waits in ENQUEUED between runs, so only one-off jobs count as queued.
        val queued = jobs.filter { it.state == WorkInfo.State.ENQUEUED }
        binding.syncStatus.text = when {
            queued.any { it.runAttemptCount > 0 } -> getString(R.string.sync_retrying)
            queued.any { it.periodicityInfo == null } -> getString(R.string.sync_waiting)
            else -> {
                val last = withContext(Dispatchers.IO) { runCatching { repository.lastCheckMillis() }.getOrDefault(0L) }
                val now = System.currentTimeMillis()
                when {
                    last == 0L -> getString(R.string.sync_never_checked)
                    now - last < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.sync_checked_just_now)
                    else -> getString(R.string.sync_last_checked, DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS))
                }
            }
        }
    }

    /** The diagnostics export for a bug report (#54), shared as text to wherever the user files it. */
    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { BugReport.build(applicationContext, repository) }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.share_diagnostics_subject))
                .putExtra(android.content.Intent.EXTRA_TEXT, report)
            startActivity(android.content.Intent.createChooser(send, getString(R.string.share_diagnostics)))
        }
    }

    /** Undoing activation is a change to system settings, so it says what it does and asks first. */
    private fun confirmTurnOff() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.turn_off_title)
            .setMessage(R.string.turn_off_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.turn_off_confirm) { _, _ -> shizuku.turnOff() }
            .show()
    }

    private fun renderLocalNetwork() {
        val granted = checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        binding.localNetworkStatus.setText(if (granted) R.string.local_network_granted else R.string.local_network_missing)
        binding.grantLocalNetworkButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    /** Diagnostics (#31): the library, the last sync, the caches and Memories. */
    private fun renderDiagnostics() {
        lifecycleScope.launch {
            val diagnostics = withContext(Dispatchers.IO) { repository.diagnostics() }
            val size = { bytes: Long -> Formatter.formatFileSize(this@DetailsActivity, bytes) }
            val lastCheck = if (diagnostics.lastCheckMillis == 0L) {
                getString(R.string.diagnostics_never)
            } else {
                DateUtils.getRelativeTimeSpanString(diagnostics.lastCheckMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            }
            binding.cacheSummary.text = getString(
                R.string.diagnostics_summary,
                diagnostics.items,
                diagnostics.generation,
                diagnostics.matched,
                lastCheck,
                diagnostics.lastError ?: getString(R.string.diagnostics_no_error),
                size(diagnostics.cache.previews.usedBytes),
                size(diagnostics.cache.previews.maximumBytes),
                size(diagnostics.cache.originals.usedBytes),
                size(diagnostics.cache.originals.maximumBytes),
            )
            val memories = withContext(Dispatchers.IO) { runCatching { repository.memoriesStatus() }.getOrNull() } ?: return@launch
            binding.memoriesStatus.text = when {
                !repository.useMemories -> getString(R.string.memories_status_off)
                memories.version == null -> getString(R.string.memories_status_unchecked)
                memories.version.isEmpty() -> getString(R.string.memories_status_absent)
                !memories.supported -> getString(R.string.memories_status_untested, memories.version)
                else -> getString(R.string.memories_status_active, memories.version, memories.enriched, memories.liveVideos)
            }
        }
    }

    private fun copy(text: CharSequence) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.commands_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
