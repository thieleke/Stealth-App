package com.cosmos.unreddit.util

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Detects the horizontal flings meant to move from one main panel to another.
 *
 * A fling is ignored when a view under the initial touch can still be scrolled horizontally in the
 * same direction, so the inner pagers and horizontal lists keep their own gestures and only report
 * a panel swipe once they reached their edge.
 *
 * @param root view the touch coordinates are resolved against, i.e. the activity content view
 * @param onSwipe called with the side the fling points to
 */
class PanelSwipeDetector(
    context: Context,
    private val root: View,
    private val onSwipe: (PanelSwipe) -> Unit
) {

    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val minDistance = MIN_DISTANCE_DP * context.resources.displayMetrics.density

    private val rootLocation = IntArray(2)

    private var velocityTracker: VelocityTracker? = null

    /** Down position, in window coordinates */
    private var downX: Float = 0F
    private var downY: Float = 0F

    /** Down position, in [root] coordinates */
    private var hitX: Float = 0F
    private var hitY: Float = 0F

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y

                root.getLocationInWindow(rootLocation)
                hitX = downX - rootLocation[0]
                hitY = downY - rootLocation[1]

                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
            }

            MotionEvent.ACTION_MOVE -> velocityTracker?.addMovement(event)

            MotionEvent.ACTION_UP -> {
                velocityTracker?.run {
                    addMovement(event)
                    computeCurrentVelocity(VELOCITY_UNITS, maxVelocity.toFloat())
                    onSwipeEnd(event, xVelocity, yVelocity)
                }
                stopTracking()
            }

            // A second finger means a scale or a drag gesture, not a panel swipe
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> stopTracking()
        }
    }

    private fun stopTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun onSwipeEnd(event: MotionEvent, velocityX: Float, velocityY: Float) {
        val distanceX = event.x - downX
        val distanceY = event.y - downY

        val isHorizontalFling = abs(distanceX) >= minDistance &&
                abs(distanceX) >= abs(distanceY) * DOMINANCE_FACTOR &&
                abs(velocityX) >= minVelocity &&
                abs(velocityX) >= abs(velocityY) * DOMINANCE_FACTOR

        if (!isHorizontalFling) return

        // A finger moving to the right scrolls the content towards its start
        val scrollDirection = if (distanceX > 0) -1 else 1

        if (canScrollHorizontally(root, hitX, hitY, scrollDirection)) return

        onSwipe(if (distanceX > 0) PanelSwipe.LEFT else PanelSwipe.RIGHT)
    }

    /**
     * @return true when [view] or one of its children under ([x], [y]) can be scrolled
     * horizontally towards [direction], negative being towards the start of the content.
     */
    private fun canScrollHorizontally(view: View, x: Float, y: Float, direction: Int): Boolean {
        // A pager that takes no user input keeps its pages in place, yet still reports its content
        // as scrollable, which would swallow the swipe
        if (view is ViewPager2 && !view.isUserInputEnabled) return false

        if (view is ViewGroup) {
            val childX = x + view.scrollX
            val childY = y + view.scrollY

            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)

                if (child.visibility != View.VISIBLE) continue

                if (childX >= child.left && childX < child.right &&
                    childY >= child.top && childY < child.bottom &&
                    canScrollHorizontally(
                        child,
                        childX - child.left,
                        childY - child.top,
                        direction
                    )
                ) {
                    return true
                }
            }
        }

        return view.canScrollHorizontally(direction)
    }

    companion object {
        private const val MIN_DISTANCE_DP = 64

        private const val DOMINANCE_FACTOR = 2

        /** Compute the velocities in pixels per second */
        private const val VELOCITY_UNITS = 1000
    }
}
