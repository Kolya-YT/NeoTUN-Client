package com.neotun.app

import android.graphics.Color

/**
 * NeoTUN shared visual contract. Keep in sync with DESIGN_SYSTEM.md and Windows resources.
 * Platform-specific layout is allowed; brand colors and semantic states are not.
 */
internal object NeoTunDesign {
    const val BACKGROUND = 0xFF070910.toInt()
    const val NAVIGATION = 0xFF0C0F19.toInt()
    const val SURFACE = 0xFF121521.toInt()
    const val SURFACE_RAISED = 0xFF1C2033.toInt()
    const val SURFACE_INPUT = 0xFF0D111D.toInt()
    const val BORDER = 0xFF2A2E44.toInt()
    const val BRAND_VIOLET = 0xFF8769FF.toInt()
    const val BRAND_BLUE = 0xFF5B5BF1.toInt()
    const val BRAND_SOFT = 0xFF23203B.toInt()
    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFFA5ABC2.toInt()
    const val TEXT_MUTED = 0xFF8F96AD.toInt()
    const val SUCCESS = 0xFF5FE6A6.toInt()
    const val SUCCESS_SURFACE = 0xFF12372E.toInt()
    const val DANGER = 0xFFFFB1C0.toInt()
    const val DANGER_SURFACE = 0xFF311B27.toInt()

    fun color(value: Int): Int = value
    fun gradient(start: Int = BRAND_VIOLET, end: Int = BRAND_BLUE) =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(start, end)
        )
}
