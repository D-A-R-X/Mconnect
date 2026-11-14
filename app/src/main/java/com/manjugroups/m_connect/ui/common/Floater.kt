package com.manjugroups.m_connect.ui.common

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.ApiErrors
import java.lang.ref.WeakReference

/**
 * The app's one in-app notice: a small tinted capsule at the top of the
 * screen ("Server busy · retrying", "Leave applied"). Every toast and every
 * HTTP error lands here.
 *
 *   Floater.error("Couldn't save leave")
 *   Floater.success("Leave applied")
 *   Floater.show(anyText)           // kind picked from the text
 *   Floater.httpError(throwable)    // readable text for any network failure
 *
 * - It sits in its own small window, so it floats above bottom sheets and
 *   dialogs, and it never takes focus (the keyboard stays on the field).
 * - Up to three capsules stack; the same text twice just restarts its timer.
 * - One line by default; tapping a cut-off capsule expands the full text,
 *   tapping again dismisses it. It hides itself after a few seconds.
 * - Raw text is cleaned first: "HTTP 500 …", Convex stack noise, JSON error
 *   bodies and Java network exceptions become plain sentences.
 */
object Floater {

    enum class Kind(@AttrRes val colorAttr: Int, @DrawableRes val icon: Int, val durationMs: Long) {
        ERROR(R.attr.colorError, R.drawable.ic_floater_error, 4_500L),
        WARNING(R.attr.colorWarning, R.drawable.ic_floater_warning, 3_800L),
        OFFLINE(R.attr.colorWarning, R.drawable.ic_floater_offline, 3_800L),
        SUCCESS(R.attr.colorSuccess, R.drawable.ic_floater_success, 2_600L),
        INFO(R.attr.colorInfo, R.drawable.ic_floater_info, 3_000L),
    }

    private const val MAX_VISIBLE = 3
    private const val EXPANDED_MS = 8_000L
    private const val HEADER_OFFSET_DP = 64

    private val main = Handler(Looper.getMainLooper())
    private var resumed: WeakReference<Activity>? = null
    private val hosts = HashMap<Activity, Host>()
    private var appContext: Context? = null

    /** Notices raised between two screens (one closing, the next opening). */
    private class Pending(val text: String, val kind: Kind, val at: Long)
    private val pending = ArrayList<Pending>()
    private const val CARRY_MS = 2_500L

    /** Called once from Application.onCreate: tracks the screen to draw on. */
    fun install(app: Application) {
        appContext = app.applicationContext
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                flushPending()
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
                // A message shown right before this screen closed ("Password
                // updated") moves to the next screen instead of vanishing.
                hosts.remove(activity)?.let { host ->
                    host.recent(CARRY_MS).forEach { (text, kind) -> queue(text, kind) }
                    host.detach()
                }
            }
            override fun onActivityDestroyed(activity: Activity) { hosts.remove(activity)?.detach() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    fun error(text: CharSequence?) = show(text, Kind.ERROR)
    fun warning(text: CharSequence?) = show(text, Kind.WARNING)
    fun success(text: CharSequence?) = show(text, Kind.SUCCESS)
    fun info(text: CharSequence?) = show(text, Kind.INFO)

    /** Readable capsule for any failed call (HttpException, timeout, offline…). */
    fun httpError(error: Throwable, fallback: String? = null) {
        val notice = ApiErrors.noticeFor(error, fallback)
        show(notice.text, notice.kind.toFloaterKind())
    }

    /**
     * Shows [text] as a capsule. [kind] null = pick it from the text (errors,
     * validation, offline, success, otherwise info). Safe from any thread.
     */
    fun show(text: CharSequence?, kind: Kind? = null) {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        val notice = ApiErrors.noticeForText(raw)
        val resolvedKind = kind ?: notice.kind.toFloaterKind()
        // An error the user saw = a flow that didn't finish (Mobile → Issues).
        com.manjugroups.m_connect.network.IssueReporter.onNotice(notice.text, resolvedKind.name)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            present(notice.text, resolvedKind)
        } else {
            main.post { present(notice.text, resolvedKind) }
        }
    }

    /** Toast replacement: a capsule when a screen is showing, else a plain toast. */
    internal fun fromToast(context: Context?, text: CharSequence?, duration: Int) {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        // With no screen (a service, or the app in the background) the notice
        // waits briefly for one, then falls back to a plain toast.
        show(raw)
    }

    private fun activeActivity(): Activity? =
        resumed?.get()?.takeIf { !it.isFinishing && !(Build.VERSION.SDK_INT >= 17 && it.isDestroyed) }

    private fun queue(text: String, kind: Kind, at: Long = android.os.SystemClock.uptimeMillis()) {
        // [at] is kept from the first try, so a notice that can't find a
        // screen expires instead of retrying forever.
        if (pending.none { it.text == text }) pending.add(Pending(text, kind, at))
        main.removeCallbacks(pendingTimeout)
        main.postDelayed(pendingTimeout, 800L)
    }

    /** No screen came back in time (app went to the background): plain toast. */
    private val pendingTimeout = Runnable {
        if (activeActivity() != null) {
            flushPending()
        } else {
            val ctx = appContext
            pending.lastOrNull()
                ?.let { p -> ctx?.let { runCatching { Toast.makeText(it, p.text, Toast.LENGTH_SHORT).show() } } }
            pending.clear()
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        main.removeCallbacks(pendingTimeout)
        val now = android.os.SystemClock.uptimeMillis()
        val items = pending.filter { now - it.at <= CARRY_MS + 1_000L }
        pending.clear()
        // Wait one frame so the new screen has a window to attach to.
        main.post { items.forEach { present(it.text, it.kind, it.at) } }
    }

    private fun present(text: String, kind: Kind, at: Long = android.os.SystemClock.uptimeMillis()) {
        val activity = activeActivity()
        if (activity == null) {
            queue(text, kind, at)
            return
        }
        val host = hosts.getOrPut(activity) { Host(activity) }
        if (!host.add(text, kind)) queue(text, kind, at)
    }

    private fun ApiErrors.NoticeKind.toFloaterKind(): Kind = when (this) {
        ApiErrors.NoticeKind.ERROR -> Kind.ERROR
        ApiErrors.NoticeKind.WARNING -> Kind.WARNING
        ApiErrors.NoticeKind.OFFLINE -> Kind.OFFLINE
        ApiErrors.NoticeKind.SUCCESS -> Kind.SUCCESS
        ApiErrors.NoticeKind.INFO -> Kind.INFO
    }

    /** One small window per screen holding the capsule stack. */
    private class Host(activity: Activity) {
        private val activityRef = WeakReference(activity)
        private val windowManager = activity.windowManager
        private val density = activity.resources.displayMetrics.density
        private val stack = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Room for the capsule shadow inside the window.
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            clipChildren = false
        }
        private var attached = false

        private class Item(
            val view: LinearLayout,
            val label: TextView,
            val text: String,
            val kind: Kind,
            val dismiss: Runnable,
            val at: Long = android.os.SystemClock.uptimeMillis(),
        )
        private val items = ArrayList<Item>()

        /** Capsules raised in the last [withinMs], to carry to the next screen. */
        fun recent(withinMs: Long): List<Pair<String, Kind>> {
            val now = android.os.SystemClock.uptimeMillis()
            return items.filter { now - it.at <= withinMs }.map { it.text to it.kind }
        }

        /** False when the screen has no window yet; the caller retries. */
        fun add(text: String, kind: Kind): Boolean {
            val activity = activityRef.get() ?: return false
            items.firstOrNull { it.text == text }?.let { existing ->
                main.removeCallbacks(existing.dismiss)
                main.postDelayed(existing.dismiss, kind.durationMs)
                existing.view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(90L).withEndAction {
                    existing.view.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                }.start()
                return true
            }
            if (!attach(activity)) return false
            while (items.size >= MAX_VISIBLE) remove(items.first(), animate = false)

            val color = resolveColor(activity, kind.colorAttr)
            val label = TextView(activity).apply {
                this.text = text
                textSize = 13f
                setTextColor(color)
                typeface = ResourcesCompat.getFont(activity, R.font.inter_medium) ?: typeface
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = activity.resources.displayMetrics.widthPixels - dp(72)
                setLineSpacing(0f, 1.15f)
            }
            val icon = ImageView(activity).apply {
                setImageResource(kind.icon)
                setColorFilter(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val capsule = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(36)
                setPadding(dp(14), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(capsuleFill(activity, color))
                    setStroke(dp(1), ColorUtils.setAlphaComponent(color, 0x66))
                }
                elevation = dp(4).toFloat()
                addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)))
                addView(label, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) })
                contentDescription = text
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            lateinit var item: Item
            val dismiss = Runnable { remove(item, animate = true) }
            item = Item(capsule, label, text, kind, dismiss)
            capsule.setOnClickListener {
                val cut = label.layout?.let { it.lineCount > 0 && it.getEllipsisCount(it.lineCount - 1) > 0 } == true
                if (cut && label.maxLines == 1) {
                    // Show the whole message and keep it up long enough to read.
                    label.maxLines = 8
                    label.ellipsize = null
                    (capsule.background as? GradientDrawable)?.cornerRadius = dp(16).toFloat()
                    main.removeCallbacks(dismiss)
                    main.postDelayed(dismiss, EXPANDED_MS)
                } else {
                    remove(item, animate = true)
                }
            }
            items.add(item)
            stack.addView(capsule, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { if (stack.childCount > 0) topMargin = dp(6) })

            capsule.alpha = 0f
            capsule.translationY = -dp(16).toFloat()
            capsule.animate().alpha(1f).translationY(0f).setDuration(220L)
                .setInterpolator(DecelerateInterpolator()).start()
            val readMs = (kind.durationMs + text.length * 25L).coerceAtMost(7_000L)
            main.postDelayed(dismiss, readMs)
            return true
        }

        private fun remove(item: Item, animate: Boolean) {
            if (!items.remove(item)) return
            main.removeCallbacks(item.dismiss)
            val finish = {
                stack.removeView(item.view)
                // The next capsule now sits first: no gap above it.
                (stack.getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.topMargin = 0
                    stack.getChildAt(0).layoutParams = it
                }
                if (items.isEmpty()) detach()
            }
            if (animate) {
                item.view.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(160L)
                    .withEndAction { finish() }.start()
            } else {
                finish()
            }
        }

        private fun attach(activity: Activity): Boolean {
            if (attached) return true
            if (activity.window?.decorView?.windowToken == null) return false
            // Below the screen header (status bar + the 56dp title bar + a gap),
            // not on top of the title and back button.
            val top = statusBarHeight(activity) + dp(HEADER_OFFSET_DP)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = top
                title = "Floater"
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            return runCatching { windowManager.addView(stack, params) }
                .onSuccess { attached = true }
                .isSuccess
        }

        fun detach() {
            items.toList().forEach { main.removeCallbacks(it.dismiss) }
            items.clear()
            stack.removeAllViews()
            if (attached) {
                attached = false
                runCatching { windowManager.removeViewImmediate(stack) }
            }
            activityRef.get()?.let { act -> if (hosts[act] === this) hosts.remove(act) }
        }

        private fun statusBarHeight(activity: Activity): Int {
            val decor = activity.window?.decorView
            val fromInsets = decor?.let { ViewCompat.getRootWindowInsets(it) }
                ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
                ?.top
            if (fromInsets != null && fromInsets > 0) return fromInsets
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) activity.resources.getDimensionPixelSize(id) else dp(24)
        }

        private fun dp(value: Int): Int = (value * density).toInt()
    }

    private fun resolveColor(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else {
            0xFF475467.toInt()
        }
    }

    /** A soft tint of the role colour over the screen's surface (light and dark). */
    private fun capsuleFill(context: Context, color: Int): Int {
        val surface = resolveColor(context, R.attr.colorSurfacePrimary)
        return ColorUtils.blendARGB(surface, color, 0.12f)
    }
}
