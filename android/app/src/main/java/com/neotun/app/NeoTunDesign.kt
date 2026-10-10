package com.neotun.app

/** Shared visual tokens for NeoTUN native clients. */
internal object NeoTunDesign {
    const val BACKGROUND = 0xFF080A12.toInt()
    const val NAVIGATION = 0xFF0D101B.toInt()
    const val SURFACE = 0xFF131725.toInt()
    const val SURFACE_RAISED = 0xFF1B2031.toInt()
    const val SURFACE_INPUT = 0xFF0E1220.toInt()
    const val BORDER = 0xFF292F45.toInt()
    const val BORDER_ACCENT = 0xFF49416F.toInt()
    const val BRAND_VIOLET = 0xFF8068FF.toInt()
    const val BRAND_VIOLET_LIGHT = 0xFFB9ACFF.toInt()
    const val BRAND_BLUE = 0xFF5B68F2.toInt()
    const val BRAND_SOFT = 0xFF25213F.toInt()
    const val NAV_SELECTED = 0xFF211D38.toInt()
    const val TEXT_PRIMARY = 0xFFF7F8FF.toInt()
    const val TEXT_SECONDARY = 0xFFB1B8CD.toInt()
    const val TEXT_MUTED = 0xFF858EA8.toInt()
    const val SUCCESS = 0xFF65E7B0.toInt()
    const val SUCCESS_SURFACE = 0xFF12362F.toInt()
    const val DANGER = 0xFFFFB5C3.toInt()
    const val DANGER_SURFACE = 0xFF321C29.toInt()

    fun color(value: Int): Int = value
    fun gradient(start: Int = BRAND_VIOLET, end: Int = BRAND_BLUE) =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(start, end)
        )
}
