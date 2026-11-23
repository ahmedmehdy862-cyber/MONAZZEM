package com.focusstudy.poc

/**
 * One shared source for the clearance between the floating + button, the bottom dock and the
 * scrollable content. Previously three unrelated hard-coded values let long task cards slip under
 * the FAB and the navigation bar.
 */
internal object LayoutSpec {
    const val DOCK_HEIGHT_DP = 76f
    const val DOCK_BOTTOM_DP = 10f
    const val FAB_SIZE_DP = 58f
    const val GAP_DP = 16f

    /** Distance from the screen bottom to the FAB's bottom edge. */
    val FAB_BOTTOM_DP: Float = DOCK_HEIGHT_DP + DOCK_BOTTOM_DP + GAP_DP

    /** Bottom padding for scrollable content so the last card clears the FAB completely. */
    val CONTENT_BOTTOM_DP: Float = FAB_BOTTOM_DP + FAB_SIZE_DP + GAP_DP
}
