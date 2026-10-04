package com.keithvassallo.ncmediaprovider.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.BuildConfig
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.activation.DeviceConfigUserService
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.LibrarySettings
import com.keithvassallo.ncmediaprovider.databinding.ActivityOnboardingBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemOnboardingStepHeaderBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemSwitchRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

/**
 * Onboarding (#52): four steps as a checklist, in the order that fails fastest. Turning the
 * app on in the picker comes first, since a phone that can't do it needs nothing else; then signing
 * in, the folders and a few extras. Each step's state is read from the app and the phone, not kept
 * here, so leaving halfway and coming back resumes where things stand.
 */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private val preferences by lazy { UiPreferences(this) }

    private val login = LoginFlowController(this) { renderLogin(it) }
    private val shizuku = ShizukuActivator(
        activity = this,
        keepGooglePhotos = { preferences.keepGooglePhotos },
        onChange = { renderShizuku(it) },
        onResult = { onShizukuResult(it) },
    )
    private val mediaRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        repository.onLocalMediaAccessChanged()
        render()
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (repository.foldersChosen) {
            chosen.clear()
            chosen += repository.folders()
            open = nextOpenStep()
        }
        render()
    }

    private enum class Route { SHIZUKU, COMPUTER }

    /** The open step, 1 to 4, or 0 when none is. */
    private var open = 0
    private var route: Route? = null
    private var computerNote: String? = null
    private var suggested: List<String>? = null
    private var loadingSuggestions = false
    private val chosen = sortedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        open = savedInstanceState?.getInt(STATE_OPEN) ?: nextOpenStep()
        route = savedInstanceState?.getString(STATE_ROUTE)?.let(Route::valueOf)
        savedInstanceState?.getStringArrayList(STATE_CHOSEN)?.let { chosen += it }
        savedInstanceState?.getStringArrayList(STATE_SUGGESTED)?.let { suggested = it }

        header(binding.pickerHeader, R.string.step_picker_title, 1)
        header(binding.connectHeader, R.string.step_connect_title, 2)
        header(binding.foldersHeader, R.string.step_folders_title, 3)
        header(binding.extrasHeader, R.string.step_extras_title, 4)

        // Step 1.
        binding.shizukuOption.setOnClickListener { route = Route.SHIZUKU; render() }
        binding.computerOption.setOnClickListener { route = Route.COMPUTER; render() }
        binding.useComputerButton.setOnClickListener { route = Route.COMPUTER; render() }
        binding.useShizukuButton.setOnClickListener { route = Route.SHIZUKU; render() }
        binding.guideButton.setOnClickListener { openLink(Links.GUIDE) }
        binding.shizukuGuideButton.setOnClickListener { openLink(Links.GUIDE_SHIZUKU) }
        binding.computerGuideButton.setOnClickListener { openLink(Links.GUIDE_COMPUTER) }
        binding.shizukuButton.setOnClickListener { shizuku.act() }
        for (toggle in listOf(binding.shizukuKeepGooglePhotos, binding.computerKeepGooglePhotos)) {
            toggle.visibility = if (preferences.googlePhotosInstalled) View.VISIBLE else View.GONE
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked == preferences.keepGooglePhotos) return@setOnCheckedChangeListener
                preferences.keepGooglePhotos = checked
                render()
            }
        }
        binding.copyCommandsButton.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), commands()))
            Toast.makeText(this, R.string.commands_copied, Toast.LENGTH_SHORT).show()
        }
        binding.sendCommandsButton.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, commands())
            startActivity(Intent.createChooser(send, getString(R.string.send_commands)))
        }
        binding.checkActivationButton.setOnClickListener { checkActivation() }
        binding.selectInPickerButton.setOnClickListener { openPickerSettings() }

        // Step 2.
        binding.connectButton.setOnClickListener { login.start(binding.serverField.text?.toString().orEmpty()) }
        binding.serverField.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_GO).also { if (it) login.start(binding.serverField.text?.toString().orEmpty()) }
        }
        binding.reopenButton.setOnClickListener { login.reopen() }
        if (savedInstanceState == null) repository.account()?.let { binding.serverField.setText(it.baseUrl) }

        // Step 3.
        binding.browseFoldersButton.setOnClickListener { folderPicker.launch(Intent(this, FolderPickerActivity::class.java)) }
        binding.useFoldersButton.setOnClickListener { confirmFolders() }

        // Step 4.
        binding.extraLocal.root.setOnClickListener {
            if (repository.hasFullLocalMediaAccess) openAppSettings()
            else mediaRequest.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        }
        binding.extraNotifications.root.setOnClickListener {
            if (notificationsAllowed() || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.extraKeyboard.root.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        binding.finishButton.setOnClickListener { finishOnboarding() }

        login.restore(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Back from Shizuku, the picker's settings, a permission screen or the keyboard settings.
        if (open != 0 && isDone(open)) open = nextOpenStep()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_OPEN, open)
        route?.let { outState.putString(STATE_ROUTE, it.name) }
        outState.putStringArrayList(STATE_CHOSEN, ArrayList(chosen))
        suggested?.let { outState.putStringArrayList(STATE_SUGGESTED, ArrayList(it)) }
        login.save(outState)
    }

    // Steps.

    private fun isDone(step: Int): Boolean = when (step) {
        1 -> PickerActivation.read(this).selected
        2 -> repository.hasAccount && !repository.signInRequired
        3 -> repository.foldersChosen
        4 -> preferences.onboardingDone
        else -> false
    }

    /** The first step not done; signing in comes before folders, which need the account. */
    private fun nextOpenStep(): Int = (1..4).firstOrNull { !isDone(it) } ?: 4

    private fun header(header: ItemOnboardingStepHeaderBinding, title: Int, step: Int) {
        header.title.setText(title)
        header.number.text = "%d".format(step)
        header.change.setOnClickListener {
            open = step
            render()
        }
    }

    private fun render() {
        val done = (1..4).map(::isDone)
        val count = done.count { it }
        binding.progress.setProgressCompat(count, true)
        binding.progressLabel.text = getString(R.string.onboarding_progress, count)

        val activation = PickerActivation.read(this)
        val account = repository.account()
        val summaries = listOf(
            getString(if (preferences.keepGooglePhotos) R.string.step_picker_summary_kept else R.string.step_picker_summary),
            account?.let { getString(R.string.step_connect_summary, it.userId, it.baseUrl.toUri().host ?: it.baseUrl) }.orEmpty(),
            repository.folders().joinToString(", "),
            getString(R.string.step_extras_summary),
        )
        val steps = listOf(
            Triple(binding.pickerCard, binding.pickerHeader, binding.pickerContent),
            Triple(binding.connectCard, binding.connectHeader, binding.connectContent),
            Triple(binding.foldersCard, binding.foldersHeader, binding.foldersContent),
            Triple(binding.extrasCard, binding.extrasHeader, binding.extrasContent),
        )
        steps.forEachIndexed { index, (card, header, content) ->
            val step = index + 1
            styleStep(card, header, isOpen = open == step, isDone = done[index], summary = summaries[index], canChange = step < 4)
            content.visibility = if (open == step) View.VISIBLE else View.GONE
        }

        renderPicker(activation)
        renderFolders()
        renderExtras()
    }

    private fun styleStep(card: MaterialCardView, header: ItemOnboardingStepHeaderBinding, isOpen: Boolean, isDone: Boolean, summary: String, canChange: Boolean) {
        card.strokeWidth = if (isOpen) resources.getDimensionPixelSize(R.dimen.step_open_stroke) else 0
        card.strokeColor = color(AppCompatR.attr.colorPrimary)
        card.alpha = if (isOpen || isDone) 1f else 0.7f
        header.tick.visibility = if (isDone) View.VISIBLE else View.GONE
        header.number.visibility = if (isDone) View.GONE else View.VISIBLE
        val (badge, onBadge) = when {
            isDone -> AppCompatR.attr.colorPrimary to MaterialR.attr.colorOnPrimary
            isOpen -> MaterialR.attr.colorPrimaryContainer to MaterialR.attr.colorOnPrimaryContainer
            else -> MaterialR.attr.colorSurfaceContainerHighest to MaterialR.attr.colorOnSurfaceVariant
        }
        header.badge.backgroundTintList = android.content.res.ColorStateList.valueOf(color(badge))
        header.number.setTextColor(color(onBadge))
        header.summary.text = summary
        header.summary.visibility = if (isDone && !isOpen && summary.isNotEmpty()) View.VISIBLE else View.GONE
        header.change.visibility = if (isDone && !isOpen && canChange) View.VISIBLE else View.GONE
    }

    // Step 1: the picker.

    private fun renderPicker(activation: PickerActivation) {
        binding.routeChoice.visibility = if (route == null) View.VISIBLE else View.GONE
        binding.shizukuPanel.visibility = if (route == Route.SHIZUKU) View.VISIBLE else View.GONE
        binding.computerPanel.visibility = if (route == Route.COMPUTER) View.VISIBLE else View.GONE
        binding.shizukuKeepGooglePhotos.isChecked = preferences.keepGooglePhotos
        binding.computerKeepGooglePhotos.isChecked = preferences.keepGooglePhotos
        binding.commands.text = commands()
        binding.computerStatus.text = computerNote
        binding.computerStatus.visibility = if (computerNote == null) View.GONE else View.VISIBLE
        // Allowed but not chosen: the picker's own settings finish the job.
        binding.selectInPickerButton.visibility = if (route != null && activation.allowed && !activation.selected) View.VISIBLE else View.GONE
        renderShizuku(shizuku.state)
    }

    private fun renderShizuku(state: ShizukuActivator.State) {
        if (!::binding.isInitialized) return
        binding.shizukuButton.isEnabled = state != ShizukuActivator.State.ACTIVATING
        binding.shizukuButton.setText(
            when (state) {
                ShizukuActivator.State.NOT_RUNNING, ShizukuActivator.State.UNSUPPORTED, ShizukuActivator.State.DENIED -> R.string.open_shizuku
                ShizukuActivator.State.NEEDS_PERMISSION -> R.string.grant_shizuku_access
                ShizukuActivator.State.READY, ShizukuActivator.State.ACTIVATING -> R.string.turn_on_with_shizuku
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
        result.onSuccess { outcome ->
            when (outcome) {
                ShizukuActivator.RESULT_SELECTED -> Unit
                DeviceConfigUserService.RESULT_RESTART -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.shizuku_activation_restart_title)
                    .setMessage(R.string.shizuku_activation_restart_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                else -> binding.shizukuStatus.setText(R.string.activation_select_needed)
            }
        }.onFailure { binding.shizukuStatus.text = getString(R.string.shizuku_activation_failed, ShizukuActivator.describe(it)) }
        if (isDone(1)) open = nextOpenStep()
        render()
    }

    /** After the adb commands: the last one selects the app, so it is usually selected already. */
    private fun checkActivation() {
        val activation = PickerActivation.read(this)
        computerNote = when {
            activation.selected -> null
            activation.allowed -> getString(R.string.activation_select_needed)
            else -> getString(R.string.activation_not_yet)
        }
        if (activation.selected) open = nextOpenStep()
        render()
    }

    private fun commands(): String = ActivationCommands.enable(BuildConfig.APPLICATION_ID, preferences.keepGooglePhotos)

    // Step 2: signing in.

    private fun renderLogin(state: LoginFlowController.State) {
        if (!::binding.isInitialized) return
        binding.httpWarning.visibility = if (login.plainHttp) View.VISIBLE else View.GONE
        binding.connectError.visibility = View.GONE
        when (state) {
            LoginFlowController.State.Idle -> {
                binding.connectBusy.visibility = View.GONE
                binding.connectButton.isEnabled = true
            }
            is LoginFlowController.State.Busy -> {
                binding.connectBusy.visibility = View.VISIBLE
                binding.connectStatus.text = state.message
                binding.reopenButton.visibility = View.GONE
                binding.connectButton.isEnabled = false
            }
            LoginFlowController.State.Waiting -> {
                binding.connectBusy.visibility = View.VISIBLE
                binding.connectStatus.setText(R.string.sign_in_waiting)
                binding.reopenButton.visibility = View.VISIBLE
                binding.connectButton.isEnabled = true
            }
            is LoginFlowController.State.Failed -> {
                binding.connectBusy.visibility = View.GONE
                binding.connectError.text = state.message
                binding.connectError.visibility = View.VISIBLE
                binding.connectButton.isEnabled = true
            }
            LoginFlowController.State.SignedIn -> {
                binding.connectBusy.visibility = View.GONE
                binding.connectButton.isEnabled = true
                suggested = null
                chosen.clear()
                open = nextOpenStep()
                render()
            }
        }
    }

    // Step 3: folders.

    private fun renderFolders() {
        if (open != 3) return
        if (suggested == null) return loadSuggestions()
        binding.foldersProgress.visibility = View.GONE
        val options = (suggested.orEmpty() + chosen).distinct().sorted()
        binding.folderList.removeAllViews()
        for (folder in options) binding.folderList.addView(folderRow(folder, folder in suggested.orEmpty()))
        binding.useFoldersButton.isEnabled = chosen.isNotEmpty()
        binding.useFoldersButton.text = if (chosen.isEmpty()) {
            getString(R.string.folders_none_selected)
        } else {
            resources.getQuantityString(R.plurals.use_folders, chosen.size, chosen.size)
        }
    }

    private fun loadSuggestions() {
        binding.foldersProgress.visibility = View.VISIBLE
        binding.useFoldersButton.isEnabled = false
        if (loadingSuggestions) return
        loadingSuggestions = true
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { repository.suggestedFolders() }.getOrDefault(emptyList()) }
            loadingSuggestions = false
            suggested = found
            if (chosen.isEmpty()) chosen += if (repository.foldersChosen) repository.folders() else found
            render()
        }
    }

    private fun folderRow(folder: String, isSuggested: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.row_min_height)
        }
        val check = MaterialCheckBox(this).apply {
            text = folder
            isChecked = folder in chosen
            setOnCheckedChangeListener { _, checked ->
                if (checked) chosen += folder else chosen -= folder
                renderFolders()
            }
        }
        row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isSuggested) {
            row.addView(
                TextView(this).apply {
                    setText(R.string.suggested_badge)
                    setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelMedium)
                    setTextColor(color(MaterialR.attr.colorOnSecondaryContainer))
                    setBackgroundResource(R.drawable.bg_pill)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color(MaterialR.attr.colorSecondaryContainer))
                    val horizontal = resources.getDimensionPixelSize(R.dimen.badge_padding_horizontal)
                    val vertical = resources.getDimensionPixelSize(R.dimen.badge_padding_vertical)
                    setPadding(horizontal, vertical, horizontal, vertical)
                },
            )
        }
        return row
    }

    private fun confirmFolders() {
        val choice = LibrarySettings.normalizeFolders(chosen)
        if (chosen.isEmpty()) return
        val save = {
            repository.chooseFolders(choice)
            open = nextOpenStep()
            render()
        }
        if (!repository.foldersChosen || repository.folders() == choice) return save()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folders_rebuild_title)
            .setMessage(R.string.folders_rebuild_message)
            .setPositiveButton(R.string.folders_rebuild_confirm) { _, _ -> save() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Step 4: extras.

    private fun renderExtras() {
        switchRow(binding.extraLocal, R.string.extra_local_title, R.string.extra_local_detail, repository.hasFullLocalMediaAccess)
        switchRow(binding.extraNotifications, R.string.extra_notifications_title, R.string.extra_notifications_detail, notificationsAllowed())
        switchRow(binding.extraKeyboard, R.string.extra_keyboard_title, R.string.extra_keyboard_detail, isPhotoKeyboardEnabled(this))
    }

    private fun notificationsAllowed() = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    private fun finishOnboarding() {
        preferences.onboardingDone = true
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun color(attribute: Int): Int = MaterialColors.getColor(binding.root, attribute)

    private companion object {
        const val STATE_OPEN = "open"
        const val STATE_ROUTE = "route"
        const val STATE_CHOSEN = "chosen"
        const val STATE_SUGGESTED = "suggested"
    }
}

/** Fills an [ItemSwitchRowBinding] and makes the whole row read as a switch to accessibility services. */
internal fun switchRow(row: ItemSwitchRowBinding, title: Int, detail: Int, checked: Boolean) {
    row.title.setText(title)
    row.detail.setText(detail)
    row.toggle.isChecked = checked
    row.root.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = row.toggle.isChecked
        }
    }
}
