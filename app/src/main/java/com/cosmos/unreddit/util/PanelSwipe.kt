package com.cosmos.unreddit.util

/**
 * Side of the screen a horizontal panel swipe points to.
 *
 * The panels are ordered from left to right (Home, Saved, Subscriptions, Settings), so [LEFT]
 * means "go to the previous panel" and [RIGHT] "go to the next one".
 */
enum class PanelSwipe {
    LEFT, RIGHT
}

/**
 * Implemented by the fragments of the main panels to handle a swipe before it changes the panel.
 */
interface PanelSwipeListener {

    /**
     * @return true when the swipe was consumed by the fragment and must not change the panel.
     */
    fun onPanelSwipe(swipe: PanelSwipe): Boolean
}
