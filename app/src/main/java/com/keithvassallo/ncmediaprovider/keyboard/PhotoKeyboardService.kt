package com.keithvassallo.ncmediaprovider.keyboard

import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardMonthBinding
import com.keithvassallo.ncmediaprovider.databinding.ItemKeyboardPhotoBinding
import com.keithvassallo.ncmediaprovider.databinding.KeyboardViewBinding
import com.keithvassallo.ncmediaprovider.share.SendFromNextcloudActivity
import com.keithvassallo.ncmediaprovider.ui.SetupActivity
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A keyboard that inserts Nextcloud photos (PLAN 4.8), for apps with their own photo grid such as
 * Messenger, which never open the system picker. It browses the library newest first, by month, and
 * inserts the tapped photo through the keyboard content API, the way GIF keyboards work. It never
 * handles text.
 */
class PhotoKeyboardService : InputMethodService() {
    private val repository by lazy { LibraryRepository.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private val thumbnailLoader = Executors.newFixedThreadPool(THUMBNAIL_THREADS)
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val rows = ArrayList<Row>()
    private var lastLoaded: MediaItem? = null
    private var loading = false
    private var exhausted = false
    private var binding: KeyboardViewBinding? = null
    private val adapter = PhotoAdapter()
    private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())

    override fun onCreateInputView(): View {
        // Material views need the app's theme; an input method's own context doesn't carry it.
        val themed = ContextThemeWrapper(this, R.style.Theme_NcMediaProvider)
        val view = KeyboardViewBinding.inflate(LayoutInflater.from(themed))
        binding = view
        view.backButton.setOnClickListener { if (!switchToPreviousInputMethod()) switchToNextInputMethod(false) }
        view.actionButton.setOnClickListener { onAction() }
        val grid = GridLayoutManager(themed, COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Month) COLUMNS else 1
            }
        }
        view.photos.layoutManager = grid
        view.photos.adapter = adapter
        view.photos.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (grid.findLastVisibleItemPosition() >= rows.size - LOAD_AHEAD) loadMore()
                }
            },
        )
        return view.root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val accepted = acceptedTypes(info)
        Log.i(TAG, "${info.packageName} accepts from keyboards: ${accepted.ifEmpty { listOf("nothing") }}")
        showState(accepted)
        if (rows.isEmpty() && repository.isReady) loadMore()
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
        view.message.visibility = if (message == null) View.GONE else View.VISIBLE
        view.photos.visibility = if (message == null) View.VISIBLE else View.GONE
        view.actionButton.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(view.message::setText)
        view.actionButton.setText(if (!repository.isReady) R.string.keyboard_open_app else R.string.send_now)
    }

    private fun onAction() {
        val target = if (repository.isReady) SendFromNextcloudActivity::class.java else SetupActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        val after = lastLoaded
        worker.execute {
            val page = runCatching { repository.newestPhotos(after, PAGE_SIZE) }.getOrDefault(emptyList())
            main.post {
                loading = false
                if (page.size < PAGE_SIZE) exhausted = true
                if (page.isEmpty()) return@post
                val start = rows.size
                var month = (rows.lastOrNull { it is Row.Photo } as? Row.Photo)?.let { monthOf(it.item) }
                for (item in page) {
                    val itemMonth = monthOf(item)
                    if (itemMonth != month) rows += Row.Month(itemMonth)
                    month = itemMonth
                    rows += Row.Photo(item)
                }
                lastLoaded = page.last()
                adapter.notifyItemRangeInserted(start, rows.size - start)
            }
        }
    }

    private fun monthOf(item: MediaItem): String =
        monthFormat.format(Instant.ofEpochMilli(item.dateTakenMillis).atZone(ZoneId.systemDefault()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

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
            view.root.tag = item.id
            view.root.contentDescription = item.fileName
            view.root.setOnClickListener { insert(item) }
            val cached = thumbnails.get(item.id)
            view.thumbnail.setImageBitmap(cached)
            if (cached != null) return
            thumbnailLoader.execute {
                val bitmap = runCatching {
                    repository.openPreview(item.id, Point(THUMBNAIL_PX, THUMBNAIL_PX), true, null)
                        .use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
                }.getOrNull() ?: return@execute
                thumbnails.put(item.id, bitmap)
                main.post { if (view.root.tag == item.id) view.thumbnail.setImageBitmap(bitmap) }
            }
        }
    }

    private companion object {
        const val TAG = "PhotoKeyboard"
        const val COLUMNS = 4
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
