package com.manjugroups.m_connect.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Horizontal swipe paging for the root tabs, layered onto the existing manual
 * `FragmentTransaction` show/hide shell rather than replacing it with a
 * ViewPager2 — the four root fragments are long-lived and their state is held
 * by the FragmentManager, which a pager adapter would fight.
 *
 * Paging is *connected*: the incoming page is brought on-screen at the start
 * of the drag and both pages move with the finger, so the gesture reads as one
 * continuous strip. Dragging only the outgoing page and swapping on release
 * leaves a blank gap where the next page should be.
 *
 * Only the drag lives here. Preparing the neighbouring page and performing the
 * actual tab switch are delegated to [Callbacks], so nothing about fragment
 * lifecycle is encoded in this view.
 *
 * Gesture arbitration is the delicate part. Root pages contain horizontal
 * carousels and chat rows, so a drag is only claimed when no scrollable under
 * the finger can consume it in that direction — the same recursive hit-test
 * ViewPager uses.
 */
class SwipePagerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private companion object {
        /** Fraction of width past which a release commits the switch. */
        const val COMMIT_FRACTION = 0.26f

        /** …or this flick speed, in dp/s, regardless of distance. */
        const val COMMIT_VELOCITY_DP = 900f

        /** Drag past a dead end (first/last tab) is damped, not blocked. */
        const val OVERSCROLL_RESISTANCE = 0.32f

        const val SETTLE_MS = 200L
        const val COMMIT_MS = 220L
    }

    interface Callbacks {
        /** False while a detail screen is pushed, or mid-switch. */
        fun canSwipe(): Boolean

        /** True if there is a tab in [direction] (-1 previous, +1 next). */
        fun hasTabInDirection(direction: Int): Boolean

        /** The visible page's view, which the drag moves. */
        fun currentPageView(): View?

        /**
         * Bring the page in [direction] on-screen, parked off to the side, and
         * return its view — or null if it can't be prepared.
         */
        fun prepareNeighbour(direction: Int): View?

        /** Put a prepared neighbour back to hidden after an abandoned drag. */
        fun releaseNeighbour(view: View)

        /** Continuous drag feedback so the nav thumb can track the page. */
        fun onSwipeProgress(fraction: Float, direction: Int)

        /**
         * The release animation is starting and will run for [durationMs].
         * The nav thumb should finish its travel over the same window, so it
         * lands with the page instead of animating afterwards against the
         * fragment transaction's layout pass.
         */
        fun onSwipeCommitStarted(direction: Int, durationMs: Long)

        /** Perform the switch. Both pages are already in their final spots. */
        fun onSwipeCommit(direction: Int)

        fun onSwipeSettled()
    }

    var callbacks: Callbacks? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animating = false
    private var velocityTracker: android.view.VelocityTracker? = null

    /** Pages participating in the in-flight drag. */
    private var currentPage: View? = null
    private var neighbourPage: View? = null
    private var activeDirection = 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (animating || callbacks?.canSwipe() != true) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) <= touchSlop || abs(dx) <= abs(dy) * 1.2f) return false
                // Defer to any horizontally scrollable view under the finger
                // that still has room to move the way the drag is going.
                if (canScroll(this, false, -dx.toInt(), ev.x.toInt(), ev.y.toInt())) {
                    return false
                }
                val page = callbacks?.currentPageView() ?: return false
                currentPage = page
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                dragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (animating) return false
        velocityTracker?.addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val raw = ev.x - downX
                val direction = if (raw < 0) 1 else -1
                val allowed = callbacks?.hasTabInDirection(direction) == true
                if (allowed) attachPages(direction)

                val dx = if (allowed) raw else raw * OVERSCROLL_RESISTANCE
                currentPage?.translationX = dx
                if (allowed) {
                    // Parked one full width away in the drag direction, so it
                    // reaches 0 exactly as the outgoing page clears the screen.
                    neighbourPage?.translationX = direction * width + dx
                    if (width > 0) {
                        callbacks?.onSwipeProgress(
                            (abs(dx) / width).coerceIn(0f, 1f),
                            direction,
                        )
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                val dx = ev.x - downX
                val direction = if (dx < 0) 1 else -1
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.xVelocity ?: 0f
                releaseTracker()
                dragging = false

                val flicked = abs(velocity) > COMMIT_VELOCITY_DP * density &&
                    // Flick must agree with the direction already dragged.
                    (velocity < 0) == (direction == 1)
                val dragged = width > 0 && abs(dx) > width * COMMIT_FRACTION
                val ready = callbacks?.hasTabInDirection(direction) == true &&
                    direction == activeDirection && neighbourPage != null

                if (ready && (flicked || dragged)) commit(direction) else settleBack()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                if (dragging) {
                    dragging = false
                    settleBack()
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Resolve the pages for [direction], swapping the neighbour if the drag
     * reversed across the origin mid-gesture.
     */
    private fun attachPages(direction: Int) {
        if (direction == activeDirection && neighbourPage != null) return
        neighbourPage?.let { stale ->
            stale.translationX = 0f
            callbacks?.releaseNeighbour(stale)
        }
        neighbourPage = null
        activeDirection = direction
        if (currentPage == null) currentPage = callbacks?.currentPageView()
        neighbourPage = callbacks?.prepareNeighbour(direction)
        neighbourPage?.translationX = direction * width.toFloat()
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Slide the pair to their final positions, then swap tabs underneath. */
    private fun commit(direction: Int) {
        animating = true
        val w = width.toFloat()
        val outgoing = currentPage
        val incoming = neighbourPage

        callbacks?.onSwipeCommitStarted(direction, COMMIT_MS)

        outgoing?.animate()
            ?.translationX(-direction * w)
            ?.setDuration(COMMIT_MS)
            ?.setInterpolator(DecelerateInterpolator(1.4f))
            ?.start()

        incoming?.animate()
            ?.translationX(0f)
            ?.setDuration(COMMIT_MS)
            ?.setInterpolator(DecelerateInterpolator(1.4f))
            ?.withEndAction {
                // The switch happens with both pages already where they belong,
                // so the show/hide transaction is invisible.
                callbacks?.onSwipeCommit(direction)
                resetPages()
                animating = false
                callbacks?.onSwipeSettled()
            }
            ?.start()

        if (incoming == null) {
            resetPages()
            animating = false
        }
    }

    private fun settleBack() {
        animating = true
        val w = width.toFloat()
        val direction = activeDirection
        val incoming = neighbourPage

        currentPage?.animate()
            ?.translationX(0f)
            ?.setDuration(SETTLE_MS)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()

        if (incoming != null) {
            incoming.animate()
                .translationX(direction * w)
                .setDuration(SETTLE_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    incoming.translationX = 0f
                    callbacks?.releaseNeighbour(incoming)
                    resetPages()
                    animating = false
                    callbacks?.onSwipeSettled()
                }
                .start()
        } else {
            postDelayed({
                resetPages()
                animating = false
                callbacks?.onSwipeSettled()
            }, SETTLE_MS)
        }
    }

    private fun resetPages() {
        currentPage?.translationX = 0f
        neighbourPage?.translationX = 0f
        currentPage = null
        neighbourPage = null
        activeDirection = 0
    }

    /**
     * Recursive hit-test for a horizontally scrollable view under (x, y) that
     * can still scroll by [dx]. Mirrors `ViewPager.canScroll`.
     */
    private fun canScroll(v: View, checkSelf: Boolean, dx: Int, x: Int, y: Int): Boolean {
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val cx = x + v.scrollX - child.left
                val cy = y + v.scrollY - child.top
                if (cx >= 0 && cx < child.width && cy >= 0 && cy < child.height &&
                    canScroll(child, true, dx, cx, cy)
                ) {
                    return true
                }
            }
        }
        return checkSelf && v.canScrollHorizontally(-dx)
    }
}
