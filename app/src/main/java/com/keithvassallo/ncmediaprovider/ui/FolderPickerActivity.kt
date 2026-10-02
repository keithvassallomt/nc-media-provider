package com.keithvassallo.ncmediaprovider.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.LibrarySettings
import com.keithvassallo.ncmediaprovider.databinding.ActivityFolderPickerBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemFolderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chooses the library folders (PLAN 4.3). Tapping a folder opens it and its checkbox selects it; a
 * folder inside a selected one is already covered. The first selection comes from Memories'
 * timeline folders and the usual photo folders; changing an existing selection rebuilds the library.
 */
class FolderPickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFolderPickerBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private val selected = sortedSetOf<String>()
    private var path = "/"
    private var loadJob: Job? = null
    private val adapter = FolderAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!repository.hasAccount) return finish()

        binding.toolbar.setNavigationOnClickListener { goBack() }
        binding.folders.layoutManager = LinearLayoutManager(this)
        binding.folders.adapter = adapter
        binding.doneButton.setOnClickListener { done() }
        binding.retryButton.setOnClickListener { load() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goBack()
            },
        )

        if (savedInstanceState != null) {
            path = savedInstanceState.getString(STATE_PATH, "/")
            selected += savedInstanceState.getStringArrayList(STATE_SELECTED).orEmpty()
        } else if (repository.foldersChosen) {
            selected += repository.folders()
        } else {
            lifecycleScope.launch {
                binding.selectionSummary.setText(R.string.folders_suggesting)
                val suggested = withContext(Dispatchers.IO) { runCatching { repository.suggestedFolders() }.getOrDefault(emptyList()) }
                if (selected.isEmpty()) selected += suggested
                showSelection()
                adapter.notifyDataSetChanged()
            }
        }
        showSelection()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    private fun load() {
        binding.toolbar.subtitle = path
        binding.progress.visibility = View.VISIBLE
        binding.error.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.empty.visibility = View.GONE
        adapter.submit(emptyList())
        loadJob?.cancel()
        val folder = path
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.childFolders(folder) } }
            binding.progress.visibility = View.GONE
            result.onSuccess { entries ->
                adapter.submit(entries.map { child(folder, it.name) })
                binding.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                binding.error.visibility = View.VISIBLE
                binding.retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun open(folder: String) {
        path = folder
        load()
    }

    private fun goBack() {
        if (path == "/") finish() else open(path.substringBeforeLast('/').ifEmpty { "/" })
    }

    private fun toggle(folder: String, checked: Boolean) {
        if (checked) {
            // Selecting a folder covers whatever was selected inside it.
            selected.removeAll { it.startsWith("$folder/") }
            selected += folder
        } else {
            selected -= folder
        }
        showSelection()
        adapter.notifyDataSetChanged()
    }

    private fun showSelection() {
        binding.selectionSummary.text = if (selected.isEmpty()) {
            getString(R.string.folders_none_selected)
        } else {
            resources.getQuantityString(R.plurals.folders_selected, selected.size, selected.size, selected.joinToString(", "))
        }
        binding.doneButton.isEnabled = selected.isNotEmpty()
    }

    private fun done() {
        val choice = LibrarySettings.normalizeFolders(selected)
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.folders_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        if (!repository.foldersChosen || repository.folders() == choice) return save(choice)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folders_rebuild_title)
            .setMessage(R.string.folders_rebuild_message)
            .setPositiveButton(R.string.folders_rebuild_confirm) { _, _ -> save(choice) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save(choice: List<String>) {
        repository.chooseFolders(choice)
        finish()
    }

    private fun child(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

    private fun coveredBySelection(folder: String) = selected.any { folder.startsWith("$it/") }

    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.Holder>() {
        private var folders: List<String> = emptyList()

        fun submit(items: List<String>) {
            folders = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = folders.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val folder = folders[position]
            val covered = coveredBySelection(folder)
            with(holder.binding) {
                name.text = folder.substringAfterLast('/')
                check.setOnCheckedChangeListener(null)
                check.isChecked = covered || folder in selected
                check.isEnabled = !covered
                check.contentDescription = getString(R.string.folders_select, name.text)
                check.setOnCheckedChangeListener { _, checked -> toggle(folder, checked) }
                root.setOnClickListener { open(folder) }
            }
        }

        inner class Holder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private companion object {
        const val STATE_PATH = "path"
        const val STATE_SELECTED = "selected"
    }
}
