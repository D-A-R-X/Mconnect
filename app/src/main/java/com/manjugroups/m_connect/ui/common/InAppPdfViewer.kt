package com.manjugroups.m_connect.ui.common

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.ui.chat.ZoomableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Full-screen PDF preview inside the app, rendered with the platform
 * [PdfRenderer]: no outside viewer, no app picker. It looks like the image
 * preview (black, round controls).
 *
 * - One page: the page fits the screen and pinch / double-tap zoom work in
 *   place.
 * - Several pages: pages scroll vertically as cards, a "2 / 5" counter
 *   follows the scroll, and tapping a page opens it sharp in the zoom view.
 *
 * "Open with" stays available for the rare file the renderer can't open
 * (encrypted PDFs).
 */
object InAppPdfViewer {

    /** Longest side we render for a page in the scrolling list. */
    private const val MAX_LIST_WIDTH_PX = 1600

    /** Zoom renders are sharper but capped so a big page can't run out of memory. */
    private const val MAX_ZOOM_WIDTH_PX = 2400
    private const val MAX_PAGE_PIXELS = 6_000_000

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

    private const val BACKDROP = "#000000"
    private const val PLACEHOLDER = "#1C1C1E"
    private const val SUBTLE_TEXT = "#B3FFFFFF"
    private const val CONTROL_FILL = "#26FFFFFF"
    private const val PILL_FILL = "#CC1C1C1E"

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

        /** Width / height of each page, read once so cards have their size before rendering. */
        private var aspects: FloatArray = FloatArray(0)

        private lateinit var dialog: Dialog
        private lateinit var content: FrameLayout
        private lateinit var progress: ProgressBar
        private lateinit var pageLabel: TextView
        private lateinit var pagePill: TextView

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
                val sizes = withContext(renderDispatcher) {
                    val fd = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptor = fd
                    val pdf = PdfRenderer(fd).also { renderer = it }
                    FloatArray(pdf.pageCount) { index ->
                        pdf.openPage(index).use { page ->
                            if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.707f
                        }
                    }
                }
                if (closed) return
                aspects = sizes
                pageLabel.text = if (sizes.size == 1) "1 page" else "${sizes.size} pages"
                if (sizes.size == 1) showSinglePage() else showPageList(sizes.size)
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

        /** Renders [index] at [targetWidth] (capped by [maxWidth] and [MAX_PAGE_PIXELS]). */
        private suspend fun renderPage(index: Int, targetWidth: Int, maxWidth: Int): Bitmap? =
            withContext(renderDispatcher) {
                val pdf = renderer ?: return@withContext null
                if (closed) return@withContext null
                pdf.openPage(index).use { page ->
                    var width = targetWidth.coerceIn(1, maxWidth)
                    var height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val pixels = width.toLong() * height
                    if (pixels > MAX_PAGE_PIXELS) {
                        val shrink = sqrt(MAX_PAGE_PIXELS.toDouble() / pixels)
                        width = (width * shrink).toInt().coerceAtLeast(1)
                        height = (height * shrink).toInt().coerceAtLeast(1)
                    }
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

        private val screenWidth get() = context.resources.displayMetrics.widthPixels

        // ── Layout ────────────────────────────────────────────────────────

        private fun circle(fill: String) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(fill))
        }

        private fun roundButton(icon: Int, iconSize: Int, description: String, onClick: () -> Unit) =
            FrameLayout(context).apply {
                background = circle(CONTROL_FILL)
                isClickable = true
                isFocusable = true
                contentDescription = description
                setOnClickListener { onClick() }
                addView(
                    ImageView(context).apply {
                        setImageResource(icon)
                        setColorFilter(Color.WHITE)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER),
                )
            }

        private fun buildView(): View {
            val root = FrameLayout(context).apply { setBackgroundColor(Color.parseColor(BACKDROP)) }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(10))
            }
            val back = roundButton(R.drawable.ic_arrow_left, 22, "Close preview") { dialog.dismiss() }
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
            }
            val titleView = TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
            }
            pageLabel = TextView(context).apply {
                text = "Loading…"
                setTextColor(Color.parseColor(SUBTLE_TEXT))
                textSize = 12f
                typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
            }
            titles.addView(titleView)
            titles.addView(pageLabel)
            val openWith = roundButton(R.drawable.ic_open_external, 20, "Open with another app") { openExternally() }
            bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(openWith, LinearLayout.LayoutParams(dp(44), dp(44)))

            content = FrameLayout(context)
            column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(column)

            progress = ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            root.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

            pagePill = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor(PILL_FILL))
                }
                visibility = View.GONE
            }
            root.addView(
                pagePill,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = dp(28) },
            )
            return root
        }

        // ── One page: fit the screen, zoom in place ───────────────────────

        private fun showSinglePage() {
            val zoomView = ZoomableImageView(context).apply { contentDescription = "Page 1" }
            // Margins, not padding: ZoomableImageView fits against its full size.
            content.addView(
                zoomView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(dp(12), dp(4), dp(12), dp(20))
                },
            )
            owner.lifecycleScope.launch {
                // Rendered above screen width so pinch zoom stays readable.
                val page = runCatching { renderPage(0, screenWidth * 2, MAX_ZOOM_WIDTH_PX) }.getOrNull()
                if (closed) {
                    page?.recycle()
                    return@launch
                }
                progress.visibility = View.GONE
                if (page == null) {
                    Toast.makeText(context, "Couldn't show this page", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                zoomView.setImageBitmap(page)
                zoomView.requestRecenter()
            }
        }

        // ── Several pages: cards + page counter ───────────────────────────

        private fun showPageList(count: Int) {
            val layoutManager = LinearLayoutManager(context)
            val list = RecyclerView(context).apply {
                this.layoutManager = layoutManager
                clipToPadding = false
                setPadding(dp(16), dp(4), dp(16), dp(84))
                adapter = PageAdapter(count)
            }
            content.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            progress.visibility = View.GONE

            fun updatePill() {
                val first = layoutManager.findFirstVisibleItemPosition()
                val last = layoutManager.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                // The page that fills most of the middle of the screen.
                val middle = list.height / 2
                var current = first
                for (position in first..last) {
                    val view = layoutManager.findViewByPosition(position) ?: continue
                    if (view.top <= middle && view.bottom >= middle) {
                        current = position
                        break
                    }
                }
                if (!list.canScrollVertically(1)) current = last
                pagePill.text = "${current + 1} / $count"
            }
            pagePill.text = "1 / $count"
            pagePill.visibility = View.VISIBLE
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updatePill()
            })
        }

        private inner class PageAdapter(private val count: Int) : RecyclerView.Adapter<PageHolder>() {
            override fun getItemCount() = count

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
                val radius = dp(6).toFloat()
                val image = ImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setBackgroundColor(Color.parseColor(PLACEHOLDER))
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }
                    clipToOutline = true
                }
                image.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(240),
                ).apply { bottomMargin = dp(14) }
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

            fun bind(index: Int) {
                clear()
                boundPage = index
                image.contentDescription = "Page ${index + 1} — tap to zoom"
                val list = image.parent as? RecyclerView
                val width = list?.let { it.width - it.paddingLeft - it.paddingRight }
                    ?.takeIf { it > 0 } ?: (screenWidth - dp(32))
                // Size the card from the page shape now, so nothing jumps when it renders.
                val aspect = aspects.getOrNull(index)?.takeIf { it > 0f } ?: 0.707f
                image.layoutParams = image.layoutParams.apply { height = (width / aspect).toInt() }
                owner.lifecycleScope.launch {
                    val rendered = runCatching { renderPage(index, width, MAX_LIST_WIDTH_PX) }.getOrNull()
                        ?: return@launch
                    if (boundPage != index || closed) {
                        rendered.recycle()
                        return@launch
                    }
                    image.setImageBitmap(rendered)
                    image.setOnClickListener { zoom(index) }
                }
            }

            fun clear() {
                boundPage = -1
                image.setOnClickListener(null)
                image.setImageDrawable(null)
            }
        }

        /** One page, full screen, rendered sharp for zooming. */
        private fun zoom(index: Int) {
            val zoomDialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val frame = FrameLayout(context).apply { setBackgroundColor(Color.parseColor(BACKDROP)) }
            val zoomView = ZoomableImageView(context).apply {
                contentDescription = "Page ${index + 1}"
            }
            val spinner = ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val closeButton = roundButton(R.drawable.ic_arrow_left, 22, "Back to pages") { zoomDialog.dismiss() }
            val label = TextView(context).apply {
                text = "Page ${index + 1} of ${aspects.size}"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor(PILL_FILL))
                }
            }
            frame.addView(zoomView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            frame.addView(closeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(14)
                leftMargin = dp(16)
            })
            frame.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(28) })
            zoomDialog.setContentView(frame)
            zoomDialog.show()
            owner.lifecycleScope.launch {
                val page = runCatching { renderPage(index, screenWidth * 2, MAX_ZOOM_WIDTH_PX) }.getOrNull()
                spinner.visibility = View.GONE
                if (page == null || closed || !zoomDialog.isShowing) {
                    page?.recycle()
                    return@launch
                }
                zoomView.setImageBitmap(page)
                zoomView.requestRecenter()
            }
        }
    }
}
