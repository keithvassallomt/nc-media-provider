package com.keithvassallo.ncmediaprovider.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.LibrarySyncWorker
import com.keithvassallo.ncmediaprovider.data.SyncProgress
import com.keithvassallo.ncmediaprovider.databinding.ActivityHomeBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemHomeRowBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemHomeTileBinding
import com.keithvassallo.ncmediaprovider.share.SendFromNextcloudActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

/**
 * The home screen (PLAN 9.3): whether the photos are in the picker, the four settings people
 * change as tiles, and everything else one tap away. A phone that isn't set up yet goes to
 * [OnboardingActivity] instead.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private val preferences by lazy { UiPreferences(this) }
    private val mediaRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        repository.onLocalMediaAccessChanged()
        render()
    }
    private var lastJobs: List<WorkInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (needsOnboarding()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tryPickerButton.setOnClickListener { openSystemPicker() }
        binding.sendPhotoButton.setOnClickListener { startActivity(Intent(this, SendFromNextcloudActivity::class.java)) }

        binding.tileMemories.root.setOnClickListener {
            repository.setUseMemories(!repository.useMemories)
            render()
        }
        binding.tilePrecache.root.setOnClickListener {
            repository.setPrecache(!repository.precacheEnabled, repository.precacheMonths, repository.precacheBytes)
            render()
        }
        binding.tileKeyboard.root.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        binding.tileLocal.root.setOnClickListener {
            if (repository.hasFullLocalMediaAccess) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            } else {
                mediaRequest.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
            }
        }

        row(binding.rowFolders, R.string.row_folders) { startActivity(Intent(this, FolderPickerActivity::class.java)) }
        row(binding.rowOtherApps, R.string.row_other_apps) { showOtherApps() }
        row(binding.rowStorage, R.string.row_storage) { startActivity(Intent(this, StorageActivity::class.java)) }
        row(binding.rowAccount, R.string.row_account) { showAccount() }
        row(binding.rowGuide, R.string.row_guide) { openLink(Links.GUIDE) }
        binding.rowGuide.trailing.setImageResource(R.drawable.ic_open_external)
        row(binding.rowDetails, R.string.row_details) { startActivity(Intent(this, DetailsActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.syncJobs().collect { jobs ->
                    lastJobs = jobs
                    renderStatus()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.precacheJobs().collect { renderTiles() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (needsOnboarding()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        render()
    }

    /**
     * Onboarding until it is finished or the phone is plainly set up already: signed in, folders
     * chosen and the app selected in the picker, as on phones set up before onboarding existed.
     */
    private fun needsOnboarding(): Boolean {
        if (!repository.hasAccount || !repository.foldersChosen) return true
        if (preferences.onboardingDone) return false
        val setUp = repository.isReady && PickerActivation.read(this).selected
        if (setUp) preferences.onboardingDone = true
        return !setUp
    }

    private fun render() {
        renderStatus()
        renderTiles()
        renderRows()
    }

    // Status.

    private fun renderStatus() {
        if (!::binding.isInitialized) return
        val activation = PickerActivation.read(this)
        repository.noteSelectedProvider(activation.selected)
        val problem = when {
            repository.signInRequired -> Problem(R.string.home_problem_sign_in_title, R.string.sign_in_required_status, R.string.action_sign_in_again) {
                startActivity(Intent(this, SignInActivity::class.java))
            }
            !activation.allowed -> Problem(R.string.home_problem_not_allowed_title, R.string.home_problem_not_allowed_text, R.string.home_problem_set_up_again) {
                preferences.onboardingDone = false
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
            }
            !activation.selected -> Problem(R.string.home_problem_switched_off_title, R.string.home_problem_switched_off_text, R.string.home_problem_select_again) {
                openPickerSettings()
            }
            else -> null
        }
        binding.problemCard.visibility = if (problem != null) View.VISIBLE else View.GONE
        binding.statusCard.visibility = if (problem == null) View.VISIBLE else View.GONE
        if (problem != null) {
            binding.problemTitle.setText(problem.title)
            binding.problemText.setText(problem.text)
            binding.problemButton.setText(problem.action)
            binding.problemButton.setOnClickListener { problem.act() }
            return
        }
        styleStatusCard()
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { runCatching { repository.itemCount() }.getOrDefault(0) }
            binding.statusCount.text = "%,d".format(count)
            binding.statusItems.text = resources.getQuantityString(R.plurals.home_items, count)
            binding.statusSync.text = syncLine(count)
        }
    }

    /** Primary in a light theme; in a dark one primary is pale, so its container reads better. */
    private fun styleStatusCard() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val background = color(if (dark) MaterialR.attr.colorPrimaryContainer else AppCompatR.attr.colorPrimary)
        val foreground = color(if (dark) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnPrimary)
        binding.statusCard.setCardBackgroundColor(background)
        for (text in listOf(binding.statusLabel, binding.statusCount, binding.statusItems, binding.statusSync)) text.setTextColor(foreground)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color(if (dark) AppCompatR.attr.colorPrimary else MaterialR.attr.colorPrimaryInverse))
        binding.tryPickerButton.backgroundTintList = ColorStateList.valueOf(foreground)
        binding.tryPickerButton.setTextColor(background)
        binding.sendPhotoButton.setTextColor(foreground)
        (binding.sendPhotoButton as? com.google.android.material.button.MaterialButton)?.strokeColor = ColorStateList.valueOf(foreground)
    }

    private suspend fun syncLine(count: Int): String {
        val running = lastJobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            return when (val progress = LibrarySyncWorker.progressOf(running)) {
                is SyncProgress.Listing -> if (progress.files == 0) {
                    getString(R.string.sync_listing_started)
                } else {
                    resources.getQuantityString(R.plurals.sync_listing, progress.files, progress.files)
                }
                else -> getString(R.string.sync_checking)
            }
        }
        val queued = lastJobs.filter { it.state == WorkInfo.State.ENQUEUED }
        if (queued.any { it.runAttemptCount > 0 }) return getString(R.string.sync_retrying)
        if (count == 0) return getString(R.string.library_not_listed)
        val last = withContext(Dispatchers.IO) { runCatching { repository.lastCheckMillis() }.getOrDefault(0L) }
        val now = System.currentTimeMillis()
        return when {
            last == 0L -> getString(R.string.sync_never_checked)
            now - last < DateUtils.MINUTE_IN_MILLIS -> getString(R.string.sync_checked_just_now)
            else -> getString(R.string.sync_last_checked, DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS))
        }
    }

    private class Problem(val title: Int, val text: Int, val action: Int, val act: () -> Unit)

    // Tiles.

    private fun renderTiles() {
        if (!::binding.isInitialized) return
        lifecycleScope.launch {
            val memories = withContext(Dispatchers.IO) { runCatching { repository.memoriesStatus() }.getOrNull() }
            val memoriesDetail = when {
                !repository.useMemories -> getString(R.string.tile_memories_off)
                memories?.version == null -> getString(R.string.tile_memories_unchecked)
                memories.version.isEmpty() -> getString(R.string.tile_memories_absent)
                !memories.supported -> getString(R.string.tile_memories_untested, memories.version)
                else -> getString(R.string.tile_memories_on)
            }
            tile(binding.tileMemories, R.string.tile_memories_title, repository.useMemories, memoriesDetail)
        }
        val (ready, total) = repository.precacheReady()
        tile(
            binding.tilePrecache, R.string.tile_precache_title, repository.precacheEnabled,
            when {
                !repository.precacheEnabled -> getString(R.string.tile_precache_off)
                total > 0 -> getString(R.string.tile_precache_ready, ready, total)
                else -> getString(R.string.tile_precache_starting)
            },
        )
        val keyboard = isPhotoKeyboardEnabled(this)
        tile(binding.tileKeyboard, R.string.tile_keyboard_title, keyboard, getString(if (keyboard) R.string.tile_keyboard_on else R.string.tile_keyboard_off))
        val local = repository.hasFullLocalMediaAccess
        tile(binding.tileLocal, R.string.tile_local_title, local, getString(if (local) R.string.tile_local_on else R.string.tile_local_off))
    }

    private fun tile(tile: ItemHomeTileBinding, title: Int, on: Boolean, detail: String) {
        tile.title.setText(title)
        tile.detail.text = detail
        tile.state.setText(if (on) R.string.tile_on else R.string.tile_off)
        val text = color(if (on) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant)
        tile.root.setCardBackgroundColor(color(if (on) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerLowest))
        tile.state.setTextColor(text)
        tile.detail.setTextColor(text)
        tile.title.setTextColor(color(if (on) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface))
        tile.dot.setBackgroundResource(if (on) R.drawable.bg_circle else R.drawable.bg_ring)
        tile.dot.backgroundTintList = ColorStateList.valueOf(color(if (on) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOutline))
        tile.root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = on
            }
        }
    }

    // Rows.

    private fun row(row: ItemHomeRowBinding, title: Int, act: () -> Unit) {
        row.title.setText(title)
        row.root.setOnClickListener { act() }
    }

    private fun renderRows() {
        binding.rowFolders.detail.text = repository.folders().joinToString(", ")
        binding.rowOtherApps.detail.setText(R.string.row_other_apps_detail)
        binding.rowStorage.detail.text = getString(R.string.row_storage_detail, Formatter.formatShortFileSize(this, repository.originalsCacheBytes))
        val account = repository.account()
        binding.rowAccount.detail.text = account?.let { getString(R.string.row_account_detail, it.userId, it.baseUrl.toUri().host ?: it.baseUrl) }.orEmpty()
        binding.rowGuide.detail.setText(R.string.row_guide_detail)
        binding.rowDetails.detail.setText(R.string.row_details_detail)
    }

    private fun showOtherApps() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.send_title)
            .setMessage(R.string.send_explanation)
            .setPositiveButton(R.string.send_now) { _, _ -> startActivity(Intent(this, SendFromNextcloudActivity::class.java)) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showAccount() {
        val account = repository.account() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.row_account)
            .setMessage(getString(R.string.account_message, account.userId, account.baseUrl))
            .setPositiveButton(R.string.action_sign_out) { _, _ -> confirmSignOut() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_message)
            .setPositiveButton(R.string.action_sign_out) { _, _ ->
                lifecycleScope.launch {
                    val revoked = withContext(Dispatchers.IO) { repository.signOut() }
                    Toast.makeText(this@HomeActivity, if (revoked) R.string.sign_out_done else R.string.sign_out_offline, Toast.LENGTH_LONG).show()
                    preferences.onboardingDone = false
                    startActivity(Intent(this@HomeActivity, OnboardingActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun color(attribute: Int): Int = MaterialColors.getColor(binding.root, attribute)
}
