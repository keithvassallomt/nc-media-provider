package com.keithvassallo.ncmediaprovider.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.format.Formatter
import android.text.style.RelativeSizeSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.MediaDiskCache
import com.keithvassallo.ncmediaprovider.data.PrecacheLimits
import com.keithvassallo.ncmediaprovider.data.PrecachePlan
import com.keithvassallo.ncmediaprovider.data.ThumbnailPrecacheWorker
import com.keithvassallo.ncmediaprovider.databinding.ActivityStorageBinding
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage (PLAN 5.6 and 8.2): how much the thumbnail pre-cache keeps, by size on a slider or by date
 * with shortcuts, with a live estimate of what fits; and the space for photos and videos opened.
 */
class StorageActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStorageBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val saveLater = Runnable { saveLimit() }
    private var plan: PrecachePlan? = null

    /** The limit as shown: [months] back (0 for everything) while [bytes] is 0, else [bytes]. */
    private var months = 0
    private var bytes = 0L
    private var dragging = false
    private var running: Pair<Int, Int>? = null
    private lateinit var shortcuts: List<Pair<MaterialButton, Int>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.originalsLimit.check(
            when (repository.originalsCacheBytes) {
                ORIGINALS_SMALL_BYTES -> R.id.originalsLimitSmall
                ORIGINALS_LARGE_BYTES -> R.id.originalsLimitLarge
                else -> R.id.originalsLimitDefault
            },
        )
        binding.originalsLimit.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            repository.setOriginalsCacheLimit(
                when (id) {
                    R.id.originalsLimitSmall -> ORIGINALS_SMALL_BYTES
                    R.id.originalsLimitLarge -> ORIGINALS_LARGE_BYTES
                    else -> MediaDiskCache.Area.ORIGINAL.maximumBytes
                },
            )
            showUsage()
        }
        binding.clearDownloadsButton.setOnClickListener { confirmClear() }

        months = repository.precacheMonths
        bytes = repository.precacheBytes
        binding.precacheSwitch.isChecked = repository.precacheEnabled
        binding.precacheSwitch.setOnCheckedChangeListener { _, _ ->
            saveLimit()
            showUsage()
        }
        shortcuts = listOf(binding.precacheMonth to 1, binding.precacheQuarter to 3, binding.precacheHalfYear to 6, binding.precacheAll to 0)
        shortcuts.forEach { (button, shortcut) ->
            button.setOnClickListener {
                months = shortcut
                bytes = 0L
                saveLimit()
            }
        }
        binding.precacheSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) onSlide(value.roundToInt()) }
        binding.precacheSlider.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    dragging = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    dragging = false
                    saveLimit()
                }
            },
        )
        binding.precacheSlider.post { binding.precacheTicks.inset = binding.precacheSlider.trackSidePadding }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.precacheJobs().collect { jobs ->
                    val job = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val ready = job?.progress?.getInt(ThumbnailPrecacheWorker.KEY_READY, -1) ?: -1
                    val total = job?.progress?.getInt(ThumbnailPrecacheWorker.KEY_TOTAL, -1) ?: -1
                    val wasRunning = running != null
                    running = if (ready >= 0 && total > 0) ready to total else null
                    // A finished run trimmed or filled the pre-cache, which moves the ceiling.
                    if (wasRunning && running == null) loadPlan() else showStatus()
                }
            }
        }
        render()
        showUsage()
    }

    override fun onResume() {
        super.onResume()
        loadPlan()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveLater)
        super.onDestroy()
    }

    private fun loadPlan() {
        lifecycleScope.launch {
            plan = withContext(Dispatchers.IO) { runCatching { repository.precachePlan() }.getOrNull() }
            render()
        }
    }

    /** While dragging, the estimate follows; a date shortcut close by catches the thumb. */
    private fun onSlide(position: Int) {
        val current = plan ?: return
        val snapped = PrecacheLimits.snap(position, current)
        if (snapped != null) {
            months = snapped
            bytes = 0L
        } else {
            months = 0
            bytes = PrecacheLimits.bytesAt(position, current.ceilingBytes)
        }
        render()
        // Keyboard and accessibility changes have no touch to end them.
        handler.removeCallbacks(saveLater)
        if (!dragging) handler.postDelayed(saveLater, SAVE_DELAY_MS)
    }

    private fun saveLimit() {
        handler.removeCallbacks(saveLater)
        repository.setPrecache(binding.precacheSwitch.isChecked, months, bytes)
        render()
    }

    private fun render() {
        binding.precacheOptions.isVisible = binding.precacheSwitch.isChecked
        showStatus()
        val current = plan
        val ready = current != null && current.total > 0 && current.ceilingBytes > 0L
        binding.precacheSlider.isEnabled = ready
        shortcuts.forEach { (button, _) -> button.isEnabled = ready }
        if (!ready) {
            binding.precacheSize.text = ""
            binding.precacheEstimate.setText(R.string.precache_empty)
            binding.precacheTicks.setTicks(emptyList())
            binding.precacheCappedCard.isVisible = false
            return
        }
        val plan = current!!
        val size = { value: Long -> Formatter.formatShortFileSize(this, value) }
        val fits = { shortcut: Int -> plan.countFor(shortcut) * plan.bytesPerItem <= plan.ceilingBytes }
        val byDate = bytes <= 0L && fits(months)
        val limit = if (byDate) plan.countFor(months) * plan.bytesPerItem else minOf(if (bytes > 0L) bytes else plan.ceilingBytes, plan.ceilingBytes)
        val items = if (byDate) plan.countFor(months) else PrecacheLimits.itemsFor(limit, plan.bytesPerItem, plan.total)

        binding.precacheSize.text = size(limit)
        binding.precacheEstimate.text = if (items >= plan.total) {
            getString(R.string.precache_whole_library, number(plan.total))
        } else {
            getString(R.string.precache_reaches_back, number(if (items >= 1000) (items + 50) / 100 * 100 else items), monthName(PrecacheLimits.reachesBack(items, plan.months)))
        }
        if (!dragging) binding.precacheSlider.value = PrecacheLimits.position(limit, plan.ceilingBytes).toFloat()
        binding.precacheTicks.setTicks(
            listOf(1 to R.string.precache_tick_1, 3 to R.string.precache_tick_3, 6 to R.string.precache_tick_6)
                .filter { (shortcut, _) -> fits(shortcut) }
                .map { (shortcut, label) ->
                    PrecacheLimits.position(plan.countFor(shortcut) * plan.bytesPerItem, plan.ceilingBytes) / PrecacheLimits.STEPS.toFloat() to getString(label)
                },
        )
        binding.precacheMin.text = size(minOf(PrecacheLimits.MIN_BYTES, plan.ceilingBytes))
        val capped = plan.everythingBytes > plan.ceilingBytes
        binding.precacheMax.text = getString(if (capped) R.string.precache_max_half else R.string.precache_max_everything, size(plan.ceilingBytes))

        val labels = mapOf(1 to R.string.precache_last_month, 3 to R.string.precache_3_months, 6 to R.string.precache_6_months, 0 to R.string.precache_all)
        shortcuts.forEach { (button, shortcut) ->
            val needed = plan.countFor(shortcut) * plan.bytesPerItem
            val fit = fits(shortcut)
            button.text = SpannableStringBuilder()
                .append(getString(labels.getValue(shortcut)))
                .append('\n')
                .inSpans(RelativeSizeSpan(SIZE_LINE_SCALE)) { append(if (fit) size(needed) else getString(R.string.precache_needs, size(needed))) }
            button.isEnabled = fit
            button.isChecked = byDate && months == shortcut
        }

        binding.precacheFreeNote.text = getString(R.string.precache_free_note, plan.sizePx, size(plan.freeBytes))
        binding.precacheCappedCard.isVisible = capped
        if (capped) {
            binding.precacheCapped.text = getString(R.string.precache_capped, size(plan.everythingBytes), size(plan.freeBytes), size(plan.ceilingBytes))
        }
    }

    private fun showStatus() {
        val progress = when {
            !binding.precacheSwitch.isChecked -> null
            running != null -> running?.let { getString(R.string.precache_running, it.first, it.second) }
            else -> repository.precacheReady().takeIf { it.second > 0 }?.let { getString(R.string.precache_ready, it.first, it.second) }
        }
        binding.precacheStatus.text = progress.orEmpty()
        binding.precacheStatus.isVisible = progress != null
    }

    private fun showUsage() {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { repository.cacheStats() }
            val size = { value: Long -> Formatter.formatShortFileSize(this@StorageActivity, value) }
            binding.usage.text = getString(
                R.string.storage_usage,
                size(stats.originals.usedBytes + stats.previews.usedBytes),
                size(stats.originals.maximumBytes),
                size(stats.precached.usedBytes),
            )
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_downloads_title)
            .setMessage(R.string.clear_downloads_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_downloads) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.clearDownloads() }
                    Toast.makeText(this@StorageActivity, R.string.clear_downloads_done, Toast.LENGTH_SHORT).show()
                    showUsage()
                }
            }
            .show()
    }

    private fun number(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    private fun monthName(month: String?): String = month?.let {
        runCatching {
            YearMonth.parse(it).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
                .replaceFirstChar { first -> first.titlecase(Locale.getDefault()) }
        }.getOrDefault(it)
    }.orEmpty()

    private companion object {
        const val ORIGINALS_SMALL_BYTES = 512L * 1024L * 1024L
        const val ORIGINALS_LARGE_BYTES = 8L * 1024L * 1024L * 1024L
        const val SAVE_DELAY_MS = 800L
        const val SIZE_LINE_SCALE = 0.85f
    }
}
