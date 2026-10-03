package com.keithvassallo.ncmediaprovider.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.MediaDiskCache
import com.keithvassallo.ncmediaprovider.data.ThumbnailPrecacheWorker
import com.keithvassallo.ncmediaprovider.databinding.ActivityStorageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Storage (PLAN 8.2 and 5.6): how much the downloads may take, clearing them, and the pre-cache's range. */
class StorageActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStorageBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }

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

        binding.precacheSwitch.isChecked = repository.precacheEnabled
        binding.precacheRange.check(
            when (repository.precacheMonths) {
                12 -> R.id.precacheYear
                3 -> R.id.precacheQuarter
                else -> R.id.precacheAll
            },
        )
        binding.precacheSwitch.setOnCheckedChangeListener { _, _ -> savePrecache() }
        binding.precacheRange.addOnButtonCheckedListener { _, _, checked -> if (checked) savePrecache() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.precacheJobs().collect { jobs ->
                    val running = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val ready = running?.progress?.getInt(ThumbnailPrecacheWorker.KEY_READY, -1) ?: -1
                    val total = running?.progress?.getInt(ThumbnailPrecacheWorker.KEY_TOTAL, -1) ?: -1
                    showPrecache(if (ready >= 0 && total > 0) ready to total else null)
                }
            }
        }
        showUsage()
    }

    private fun showUsage() {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { repository.cacheStats() }
            val size = { bytes: Long -> Formatter.formatShortFileSize(this@StorageActivity, bytes) }
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

    private fun precacheMonths(): Int = when (binding.precacheRange.checkedButtonId) {
        R.id.precacheYear -> 12
        R.id.precacheQuarter -> 3
        else -> 0
    }

    private fun savePrecache() {
        repository.setPrecache(binding.precacheSwitch.isChecked, precacheMonths())
        lifecycleScope.launch { showPrecache(null) }
        showUsage()
    }

    private suspend fun showPrecache(running: Pair<Int, Int>?) {
        val on = binding.precacheSwitch.isChecked
        binding.precacheRange.visibility = if (on) View.VISIBLE else View.GONE
        val months = precacheMonths()
        val (count, bytes) = withContext(Dispatchers.IO) { runCatching { repository.precacheEstimate(months) }.getOrDefault(0 to 0L) }
        val estimate = getString(
            R.string.precache_estimate,
            Formatter.formatShortFileSize(this, bytes),
            count,
            Formatter.formatShortFileSize(this, MediaDiskCache.Area.PRECACHE.maximumBytes),
        )
        val progress = when {
            !on -> null
            running != null -> getString(R.string.precache_running, running.first, running.second)
            else -> repository.precacheReady().takeIf { it.second > 0 }?.let { getString(R.string.precache_ready, it.first, it.second) }
        }
        binding.precacheStatus.text = listOfNotNull(estimate, progress).joinToString(" ")
    }

    private companion object {
        const val ORIGINALS_SMALL_BYTES = 512L * 1024L * 1024L
        const val ORIGINALS_LARGE_BYTES = 8L * 1024L * 1024L * 1024L
    }
}
