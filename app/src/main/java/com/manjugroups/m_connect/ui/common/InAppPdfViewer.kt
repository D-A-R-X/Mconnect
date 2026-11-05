package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Full-screen PDF preview inside the app, rendered with the platform
 * [PdfRenderer]: no outside viewer, no app picker. Pages scroll vertically;
 * tapping a page opens it in the pinch-zoom image preview. "Open with" stays
 * available for the rare file the renderer can't open (encrypted PDFs).
 */
object InAppPdfViewer {

    private const val MAX_PAGE_WIDTH_PX = 1600

    /** A PDF already on disk (e.g. a document picked or scanned this session). */
    fun showFile(context: Context, owner: LifecycleOwner, file: File, title: String) {
        Session(context, owner, title, file).open()
    }

    /** A PDF on the server: downloaded to the cache, then shown. */
    fun showUrl(context: Context, owner: LifecycleOwner, url: String, title: String) {
        Session(context, owner, title, null, url).open()
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private class Session(
        private val context: Context,
        private val owner: LifecycleOwner,
        private val title: String,
        private var file: File?,
        private val url: String? = null,
    ) {
        // PdfRenderer is not thread-safe: every call goes through one thread.
        private val renderThread = Executors.newSingleThreadExecutor()
        private val renderDispatcher = renderThread.asCoroutineDispatcher()
        private var descriptor: ParcelFileDescriptor? = null
        private var renderer: PdfRenderer? = null
        private var closed = false

        private lateinit var dialog: Dialog
        private lateinit var list: RecyclerView
        private lateinit var progress: ProgressBar
        private lateinit var pageLabel: TextView

        fun open() {
            dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(buildView())
            dialog.setOnDismissListener { close() }
            dialog.show()
            owner.lifecycleScope.launch { load() }
        }

        private suspend fun load() {
            try {
                val local = file ?: withContext(Dispatchers.IO) { download(url!!) }
                file = local
                val count = withContext(renderDispatcher) {
                    val fd = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptor = fd
                    PdfRenderer(fd).also { renderer = it }.pageCount
                }
                if (closed) return
                progress.visibility = View.GONE
                pageLabel.text = if (count == 1) "1 page" else "$count pages"
                list.adapter = PageAdapter(count)
            } catch (e: Exception) {
                if (closed) return
                progress.visibility = View.GONE
                Toast.makeText(context, "Couldn't show this PDF here. Opening it in another app.", Toast.LENGTH_LONG).show()
                openExternally()
                dialog.dismiss()
            }
        }

        private fun download(url: String): File {
            val folder = File(context.cacheDir, "pdf-preview").apply { mkdirs() }
            val out = File(folder, "preview_${url.hashCode().toUInt()}.pdf")
            if (out.exists() && out.length() > 0) return out
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = checkNotNull(response.body)
                val partial = File(folder, out.name + ".part")
                partial.outputStream().use { body.byteStream().copyTo(it) }
                check(partial.renameTo(out)) { "Couldn't save the PDF" }
            }
            return out
        }

        private suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? = withContext(renderDispatcher) {
            val pdf = renderer ?: return@withContext null
            if (closed) return@withContext null
            pdf.openPage(index).use { page ->
                val width = targetWidth.coerceIn(1, MAX_PAGE_WIDTH_PX)
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }

        private fun close() {
            if (closed) return
            closed = true
            renderThread.execute {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                renderer = null
                descriptor = null
            }
            renderThread.shutdown()
        }

        private fun openExternally() {
            val local = file ?: url?.let { remote ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(remote)))
                }
                return
            } ?: return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", local)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(intent, "Open PDF with"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No PDF viewer is installed", Toast.LENGTH_SHORT).show()
            }
        }

        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

        private fun buildView(): View {
            val root = FrameLayout(context).apply { setBackgroundColor(Color.parseColor("#1D2939")) }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.parseColor("#101828"))
            }
            val back = ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_left)
                setColorFilter(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Close preview"
                setOnClickListener { dialog.dismiss() }
            }
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
            }
            val titleView = TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
            }
            pageLabel = TextView(context).apply {
                text = "Loading…"
                setTextColor(Color.parseColor("#98A2B3"))
                textSize = 12f
            }
            titles.addView(titleView)
            titles.addView(pageLabel)
            val openWith = TextView(context).apply {
                text = "Open with"
                setTextColor(Color.parseColor("#84CAFF"))
                textSize = 13f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { openExternally() }
            }
            bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(openWith)

            list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                clipToPadding = false
                setPadding(dp(12), dp(12), dp(12), dp(24))
            }
            column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(column)

            progress = ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            root.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            return root
        }

        private inner class PageAdapter(private val count: Int) : RecyclerView.Adapter<PageHolder>() {
            override fun getItemCount() = count

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                val image = ImageView(parent.context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                    minimumHeight = dp(240)
                }
                image.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
                return PageHolder(image)
            }

            override fun onBindViewHolder(holder: PageHolder, position: Int) {
                holder.bind(position)
            }

            override fun onViewRecycled(holder: PageHolder) {
                holder.clear()
            }
        }

        private inner class PageHolder(private val image: ImageView) : RecyclerView.ViewHolder(image) {
            private var boundPage = -1
            private var bitmap: Bitmap? = null

            fun bind(index: Int) {
                clear()
                boundPage = index
                image.contentDescription = "Page ${index + 1}"
                val width = (list.width - list.paddingLeft - list.paddingRight)
                    .takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
                owner.lifecycleScope.launch {
                    val rendered = runCatching { renderPage(index, width) }.getOrNull() ?: return@launch
                    if (boundPage != index || closed) {
                        rendered.recycle()
                        return@launch
                    }
                    bitmap = rendered
                    image.setImageBitmap(rendered)
                    image.setOnClickListener { zoom(rendered, index) }
                }
            }

            fun clear() {
                boundPage = -1
                image.setOnClickListener(null)
                image.setImageDrawable(null)
                bitmap = null
            }
        }

        /** One page, full screen, pinch to zoom. */
        private fun zoom(page: Bitmap, index: Int) {
            val zoomDialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
            val zoomView = com.manjugroups.m_connect.ui.chat.ZoomableImageView(context).apply {
                setImageBitmap(page)
                contentDescription = "Page ${index + 1}"
            }
            val closeButton = ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_left)
                setColorFilter(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = "Back to pages"
                setOnClickListener { zoomDialog.dismiss() }
            }
            frame.addView(zoomView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(16)
                leftMargin = dp(12)
            })
            zoomDialog.setContentView(frame)
            zoomDialog.show()
            zoomView.post { zoomView.requestRecenter() }
        }
    }
}
