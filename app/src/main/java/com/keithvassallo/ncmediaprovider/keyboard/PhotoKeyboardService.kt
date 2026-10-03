package com.keithvassallo.ncmediaprovider.keyboard

import android.content.ClipDescription
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Point
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.KeyboardSource
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.data.PickerAlbum
import com.keithvassallo.ncmediaprovider.data.PickerPerson
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardAlbumBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardMonthBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardPersonBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardPhotoBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardYearBinding
import com.keithvassallo.ncmediaprovider.databinding.KeyboardViewBinding
import com.keithvassallo.ncmediaprovider.share.SendFromNextcloudActivity
import com.keithvassallo.ncmediaprovider.ui.HomeActivity
import java.io.File
import java.io.FileInputStream
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A keyboard that inserts Nextcloud photos (PLAN 4.8), for apps with their own photo grid such as
 * Messenger, which never open the system picker. Tabs show recent photos, albums, people and
 * favourites; photos come by month with a rail of years to jump through, and the tapped one is
 * inserted through the keyboard content API, the way GIF keyboards work. It never handles text.
 */
class PhotoKeyboardService : InputMethodService() {
    private val repository by lazy { LibraryRepository.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private val thumbnailLoader = Executors.newFixedThreadPool(THUMBNAIL_THREADS)
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var binding: KeyboardViewBinding? = null
    private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    private var accepting = false

    private var tab = Tab.RECENT

    /** An album or a person opened from its tab. */
    private var opened: Opened? = null

    /** The grid's photos: the library or favourites from their tabs, or what was opened. */
    private val source: KeyboardSource?
        get() = opened?.source ?: when (tab) {
            Tab.RECENT -> KeyboardSource.Library
            Tab.FAVOURITES -> KeyboardSource.Favourites
            else -> null
        }

    // The grid holds the source from [newest] down to [oldest]; after a jump to a year, newer pages
    // load as it scrolls up.
    private val rows = ArrayList<Row>()
    private var newest: MediaItem? = null
    private var oldest: MediaItem? = null
    private var startDate = Long.MAX_VALUE
    private var olderDone = false
    private var newerDone = true
    private var loadingOlder = false
    private var loadingNewer = false

    /** Bumped whenever what is shown starts over, so pages for something earlier are dropped. */
    private var generation = 0
    private val photoAdapter = PhotoAdapter()
    private var grid: GridLayoutManager? = null

    private var years: List<Int> = emptyList()
    private var shownYear: Int? = null
    private val yearAdapter = YearAdapter()

    private var albums: List<PickerAlbum> = emptyList()
    private var people: List<PickerPerson> = emptyList()
    private val albumAdapter = AlbumAdapter()
    private val peopleAdapter = PeopleAdapter()

    override fun onCreateInputView(): View {
        // Material views need the app's theme; an input method's own context doesn't carry it.
        val themed = ContextThemeWrapper(this, R.style.Theme_NcMediaProvider)
        val view = KeyboardViewBinding.inflate(LayoutInflater.from(themed))
        binding = view
        view.backButton.setOnClickListener { if (!switchToPreviousInputMethod()) switchToNextInputMethod(false) }
        view.actionButton.setOnClickListener { onAction() }
        view.setBack.setOnClickListener { closeOpened() }

        Tab.entries.forEach { entry ->
            view.tabs.addTab(view.tabs.newTab().setIcon(entry.icon).setText(entry.label).setTag(entry), entry == tab)
        }
        view.tabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(selected: TabLayout.Tab) {
                    tab = selected.tag as Tab
                    opened = null
                    show()
                }

                override fun onTabUnselected(unselected: TabLayout.Tab) = Unit

                override fun onTabReselected(reselected: TabLayout.Tab) {
                    if (opened != null) closeOpened() else if (source != null) startGrid(null)
                }
            },
        )

        val layout = GridLayoutManager(themed, COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Month) COLUMNS else 1
            }
        }
        grid = layout
        view.photos.layoutManager = layout
        view.photos.adapter = photoAdapter
        view.photos.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val first = layout.findFirstVisibleItemPosition()
                    if (layout.findLastVisibleItemPosition() >= rows.size - LOAD_AHEAD) loadOlder()
                    if (first in 0 until LOAD_AHEAD) loadNewer()
                    noteShownYear(first)
                }
            },
        )
        view.years.layoutManager = LinearLayoutManager(themed)
        view.years.adapter = yearAdapter
        return view.root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val accepted = acceptedTypes(info)
        Log.i(TAG, "${info.packageName} accepts from keyboards: ${accepted.ifEmpty { listOf("nothing") }}")
        showState(accepted)
        // A keyboard opens fresh, at the newest photos, with whatever the last sync brought.
        if (accepting && !restarting) show()
    }

    override fun onDestroy() {
        thumbnailLoader.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** What this field takes, or nothing; the explanation and action follow from it. */
    private fun showState(accepted: List<String>) {
        val view = binding ?: return
        val message = when {
            !repository.isReady -> R.string.keyboard_not_ready
            !KeyboardFormats.acceptsImages(accepted) -> R.string.keyboard_no_images
            else -> null
        }
        accepting = message == null
        view.tabs.isVisible = accepting
        view.message.isVisible = !accepting
        view.actionButton.isVisible = !accepting
        if (!accepting) {
            view.gridArea.isVisible = false
            view.sets.isVisible = false
            view.setHeader.isVisible = false
        }
        message?.let(view.message::setText)
        view.actionButton.setText(if (!repository.isReady) R.string.keyboard_open_app else R.string.send_now)
    }

    private fun onAction() {
        val target = if (repository.isReady) SendFromNextcloudActivity::class.java else HomeActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shows the current tab, or what was opened from it. */
    private fun show() {
        val view = binding ?: return
        if (!accepting) return
        val current = opened
        view.setHeader.isVisible = current != null
        if (current != null) {
            view.setTitle.text = current.name.ifEmpty { getString(R.string.keyboard_unnamed) }
            view.setCount.text = photoCount(current.count)
        }
        val gridSource = source
        view.gridArea.isVisible = gridSource != null
        view.sets.isVisible = gridSource == null
        showEmpty(null)
        when {
            gridSource != null -> {
                startGrid(null)
                loadYears(gridSource)
            }
            tab == Tab.ALBUMS -> loadAlbums()
            else -> loadPeople()
        }
    }

    private fun closeOpened() {
        opened = null
        show()
    }

    private fun showEmpty(@StringRes text: Int?) {
        val view = binding ?: return
        view.message.isVisible = text != null
        view.actionButton.isVisible = false
        text?.let(view.message::setText)
    }

    // The photo grid.

    /** Starts the grid at the newest photo, or at the newest of [year]. */
    private fun startGrid(year: Int?) {
        generation++
        rows.clear()
        photoAdapter.notifyDataSetChanged()
        newest = null
        oldest = null
        startDate = year?.let { startOf(it + 1) } ?: Long.MAX_VALUE
        olderDone = false
        newerDone = year == null
        loadingOlder = false
        loadingNewer = false
        shownYear = year
        yearAdapter.notifyDataSetChanged()
        showEmpty(null)
        loadOlder()
    }

    private fun loadOlder() {
        val from = source ?: return
        if (loadingOlder || olderDone) return
        loadingOlder = true
        val run = generation
        val date = oldest?.dateTakenMillis ?: startDate
        val id = oldest?.id.orEmpty()
        worker.execute {
            val page = runCatching { repository.keyboardPhotos(from, date, id, newer = false, limit = PAGE_SIZE) }.getOrDefault(emptyList())
            main.post {
                if (run != generation) return@post
                loadingOlder = false
                if (page.size < PAGE_SIZE) olderDone = true
                if (page.isEmpty()) {
                    if (rows.isEmpty()) showEmpty(R.string.keyboard_empty)
                    return@post
                }
                if (newest == null) newest = page.first()
                oldest = page.last()
                appendRows(page)
            }
        }
    }

    private fun loadNewer() {
        val from = source ?: return
        val top = newest ?: return
        if (loadingNewer || newerDone) return
        loadingNewer = true
        val run = generation
        worker.execute {
            val page = runCatching { repository.keyboardPhotos(from, top.dateTakenMillis, top.id, newer = true, limit = PAGE_SIZE) }.getOrDefault(emptyList())
            main.post {
                if (run != generation) return@post
                loadingNewer = false
                if (page.size < PAGE_SIZE) newerDone = true
                if (page.isEmpty()) return@post
                newest = page.first()
                prependRows(page)
            }
        }
    }

    private fun appendRows(page: List<MediaItem>) {
        val start = rows.size
        var month = (rows.lastOrNull { it is Row.Photo } as? Row.Photo)?.let { monthOf(it.item) }
        for (item in page) {
            val itemMonth = monthOf(item)
            if (itemMonth != month) rows += Row.Month(itemMonth)
            month = itemMonth
            rows += Row.Photo(item)
        }
        photoAdapter.notifyItemRangeInserted(start, rows.size - start)
        if (start == 0) noteShownYear(0)
    }

    /** Newer photos above what is shown, keeping the photos on screen where they are. */
    private fun prependRows(page: List<MediaItem>) {
        val layout = grid ?: return
        val added = ArrayList<Row>()
        var month: String? = null
        for (item in page) {
            val itemMonth = monthOf(item)
            if (itemMonth != month) added += Row.Month(itemMonth)
            month = itemMonth
            added += Row.Photo(item)
        }
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val offset = layout.findViewByPosition(first)?.top ?: 0
        // The month shown first carries on from the page's last month: one heading is enough.
        var removed = 0
        if ((rows.firstOrNull() as? Row.Month)?.label == month) {
            rows.removeAt(0)
            photoAdapter.notifyItemRemoved(0)
            removed = 1
        }
        rows.addAll(0, added)
        photoAdapter.notifyItemRangeInserted(0, added.size)
        layout.scrollToPositionWithOffset((first - removed).coerceAtLeast(0) + added.size, offset)
    }

    /** Highlights the year of the photos at the top of the grid. */
    private fun noteShownYear(first: Int) {
        if (first < 0) return
        val item = (first until minOf(rows.size, first + COLUMNS + 1)).firstNotNullOfOrNull { (rows[it] as? Row.Photo)?.item } ?: return
        val year = Instant.ofEpochMilli(item.dateTakenMillis).atZone(ZoneId.systemDefault()).year
        if (year == shownYear) return
        shownYear = year
        yearAdapter.notifyDataSetChanged()
        years.indexOf(year).takeIf { it >= 0 }?.let { binding?.years?.scrollToPosition(it) }
    }

    private fun loadYears(from: KeyboardSource) {
        val run = generation
        worker.execute {
            val found = runCatching { repository.keyboardYears(from) }.getOrDefault(emptyList())
            main.post {
                if (run != generation) return@post
                years = found
                yearAdapter.notifyDataSetChanged()
                binding?.years?.isVisible = found.size > 1
            }
        }
    }

    // Albums and people.

    private fun loadAlbums() {
        val view = binding ?: return
        view.sets.layoutManager = GridLayoutManager(view.sets.context, ALBUM_COLUMNS)
        view.sets.adapter = albumAdapter
        val run = ++generation
        worker.execute {
            val found = runCatching { repository.keyboardAlbums() }.getOrDefault(emptyList())
            main.post {
                if (run != generation) return@post
                albums = found
                albumAdapter.notifyDataSetChanged()
                showEmpty(if (found.isEmpty()) R.string.keyboard_no_albums else null)
            }
        }
    }

    private fun loadPeople() {
        val view = binding ?: return
        view.sets.layoutManager = GridLayoutManager(view.sets.context, PEOPLE_COLUMNS)
        view.sets.adapter = peopleAdapter
        val run = ++generation
        worker.execute {
            val found = runCatching { repository.keyboardPeople() }.getOrDefault(emptyList())
            main.post {
                if (run != generation) return@post
                people = found
                peopleAdapter.notifyDataSetChanged()
                showEmpty(if (found.isEmpty()) R.string.keyboard_no_people else null)
            }
        }
    }

    private fun open(source: KeyboardSource, name: String, count: Int) {
        opened = Opened(source, name, count)
        show()
    }

    // Inserting.

    /** Fetches the original, converts it if the app needs another type, and inserts it. */
    private fun insert(item: MediaItem) {
        val editor = currentInputEditorInfo ?: return
        val type = KeyboardFormats.outputType(item.mimeType, acceptedTypes(editor))
        if (type == null) {
            status(getString(R.string.keyboard_type_refused, item.mimeType.substringAfter('/').uppercase()))
            return
        }
        status(getString(R.string.keyboard_fetching, item.fileName))
        worker.execute {
            val started = System.currentTimeMillis()
            val prepared = runCatching { prepare(item, type) }
            main.post {
                val uri = prepared.getOrElse {
                    Log.w(TAG, "Fetching ${item.id} failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
                    status(getString(R.string.keyboard_fetch_failed, item.fileName))
                    return@post
                }
                val connection = currentInputConnection ?: return@post
                val committed = InputConnectionCompat.commitContent(
                    connection,
                    editor,
                    InputContentInfoCompat(uri, ClipDescription(item.fileName, arrayOf(type)), null),
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null,
                )
                Log.i(TAG, "Insert into ${editor.packageName}: ${item.mimeType} as $type in ${System.currentTimeMillis() - started} ms, committed=$committed")
                status(if (committed) null else getString(R.string.keyboard_insert_refused))
            }
        }
    }

    /** A copy the receiving app can read through this app's FileProvider. */
    private fun prepare(item: MediaItem, type: String): Uri {
        val directory = File(cacheDir, KEYBOARD_DIRECTORY).apply { mkdirs() }
        pruneOldFiles(directory)
        val original = File(directory, "${item.id}-original")
        repository.openOriginal(item.id, null).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input -> original.outputStream().use(input::copyTo) }
        }
        val output = if (type == item.mimeType) {
            File(directory, item.fileName.ifBlank { item.id }).also { original.renameTo(it) }
        } else {
            File(directory, item.fileName.substringBeforeLast('.').ifBlank { item.id } + ".jpg").also { jpeg ->
                toJpeg(original, jpeg)
                original.delete()
            }
        }
        return FileProvider.getUriForFile(this, "$packageName.keyboardfiles", output)
    }

    /** On the phone, so it works without the server's HEIC previews; capped to keep memory in check. */
    private fun toJpeg(source: File, target: File) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_CONVERTED_PX) {
                val scale = MAX_CONVERTED_PX.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
    }

    /** Receiving apps read the file right away; keep the last few in case one reads late. */
    private fun pruneOldFiles(directory: File) {
        directory.listFiles()?.sortedByDescending(File::lastModified)?.drop(KEPT_FILES)?.forEach(File::delete)
    }

    private fun status(message: String?) {
        val view = binding ?: return
        view.status.text = message.orEmpty()
        view.status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun acceptedTypes(editor: EditorInfo): List<String> = EditorInfoCompat.getContentMimeTypes(editor).toList()

    // Helpers.

    /** A thumbnail, a cover or a face, from the picker's caches; [owner]'s tag guards against reuse. */
    private fun loadThumbnail(id: String, target: ImageView, owner: View) {
        owner.tag = id
        val cached = thumbnails.get(id)
        target.setImageBitmap(cached)
        if (cached != null) return
        thumbnailLoader.execute {
            val bitmap = runCatching {
                repository.openPreview(id, Point(THUMBNAIL_PX, THUMBNAIL_PX), true, null, fromPicker = false)
                    .use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
            }.getOrNull() ?: return@execute
            thumbnails.put(id, bitmap)
            main.post { if (owner.tag == id) target.setImageBitmap(bitmap) }
        }
    }

    private fun monthOf(item: MediaItem): String =
        monthFormat.format(Instant.ofEpochMilli(item.dateTakenMillis).atZone(ZoneId.systemDefault()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private fun startOf(year: Int): Long = LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun photoCount(count: Int): String = resources.getQuantityString(R.plurals.keyboard_photo_count, count, NumberFormat.getIntegerInstance().format(count))

    private enum class Tab(@DrawableRes val icon: Int, @StringRes val label: Int) {
        RECENT(R.drawable.ic_recent, R.string.keyboard_tab_recent),
        ALBUMS(R.drawable.ic_albums, R.string.keyboard_tab_albums),
        PEOPLE(R.drawable.ic_people, R.string.keyboard_tab_people),
        FAVOURITES(R.drawable.ic_star, R.string.keyboard_tab_favourites),
    }

    private data class Opened(val source: KeyboardSource, val name: String, val count: Int)

    private sealed interface Row {
        data class Month(val label: String) : Row
        data class Photo(val item: MediaItem) : Row
    }

    private inner class PhotoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Month) MONTH else PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == MONTH) {
                object : RecyclerView.ViewHolder(ItemKeyboardMonthBinding.inflate(inflater, parent, false).root) {}
            } else {
                PhotoHolder(ItemKeyboardPhotoBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Month -> ItemKeyboardMonthBinding.bind(holder.itemView).month.text = row.label
                is Row.Photo -> (holder as PhotoHolder).bind(row.item)
            }
        }
    }

    private inner class PhotoHolder(private val view: ItemKeyboardPhotoBinding) : RecyclerView.ViewHolder(view.root) {
        fun bind(item: MediaItem) {
            view.root.contentDescription = item.fileName
            view.root.setOnClickListener { insert(item) }
            loadThumbnail(item.id, view.thumbnail, view.root)
        }
    }

    private inner class YearAdapter : RecyclerView.Adapter<YearHolder>() {
        override fun getItemCount() = years.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            YearHolder(ItemKeyboardYearBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: YearHolder, position: Int) = holder.bind(years[position])
    }

    private inner class YearHolder(private val view: ItemKeyboardYearBinding) : RecyclerView.ViewHolder(view.root) {
        fun bind(year: Int) {
            val selected = year == shownYear
            view.year.text = getString(R.string.keyboard_year_short, year % 100)
            view.year.contentDescription = getString(R.string.keyboard_jump_to_year, year)
            view.year.isSelected = selected
            view.year.backgroundTintList = ColorStateList.valueOf(
                if (selected) MaterialColors.getColor(view.year, com.google.android.material.R.attr.colorSecondaryContainer) else Color.TRANSPARENT,
            )
            view.year.setTextColor(
                MaterialColors.getColor(
                    view.year,
                    if (selected) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
            view.year.setOnClickListener { startGrid(year) }
        }
    }

    private inner class AlbumAdapter : RecyclerView.Adapter<AlbumHolder>() {
        override fun getItemCount() = albums.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AlbumHolder(ItemKeyboardAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: AlbumHolder, position: Int) = holder.bind(albums[position])
    }

    private inner class AlbumHolder(private val view: ItemKeyboardAlbumBinding) : RecyclerView.ViewHolder(view.root) {
        fun bind(album: PickerAlbum) {
            view.name.text = album.name
            view.count.text = NumberFormat.getIntegerInstance().format(album.count)
            view.root.contentDescription = album.name + ", " + photoCount(album.count)
            view.root.setOnClickListener { open(KeyboardSource.Album(album.id), album.name, album.count) }
            loadThumbnail(album.coverId, view.cover, view.root)
        }
    }

    private inner class PeopleAdapter : RecyclerView.Adapter<PersonHolder>() {
        override fun getItemCount() = people.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PersonHolder(ItemKeyboardPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PersonHolder, position: Int) = holder.bind(people[position])
    }

    private inner class PersonHolder(private val view: ItemKeyboardPersonBinding) : RecyclerView.ViewHolder(view.root) {
        fun bind(person: PickerPerson) {
            view.name.text = person.name
            view.count.text = NumberFormat.getIntegerInstance().format(person.count)
            view.root.contentDescription = person.name.ifEmpty { getString(R.string.keyboard_unnamed) } + ", " + photoCount(person.count)
            view.root.setOnClickListener { open(KeyboardSource.Person(person.id), person.name, person.count) }
            loadThumbnail(person.faceCoverId, view.face, view.root)
        }
    }

    private companion object {
        const val TAG = "PhotoKeyboard"
        const val COLUMNS = 4
        const val ALBUM_COLUMNS = 3
        const val PEOPLE_COLUMNS = 4
        const val PAGE_SIZE = 120
        const val LOAD_AHEAD = 24
        const val MONTH = 0
        const val PHOTO = 1
        const val THUMBNAIL_PX = 256
        const val THUMBNAIL_THREADS = 4
        const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
        const val KEYBOARD_DIRECTORY = "keyboard"
        const val KEPT_FILES = 10
        const val MAX_CONVERTED_PX = 4096
        const val JPEG_QUALITY = 92
    }
}
