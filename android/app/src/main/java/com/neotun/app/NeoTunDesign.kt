package com.neotun.app

import android.content.Context
import android.graphics.drawable.GradientDrawable

/** Shared visual tokens for NeoTUN Android UI. */
internal object NeoTunDesign {
    private var theme = "dark"

    var BACKGROUND = 0xFF080A12.toInt(); private set
    var NAVIGATION = 0xFF0D101B.toInt(); private set
    var SURFACE = 0xFF131725.toInt(); private set
    var SURFACE_RAISED = 0xFF1B2031.toInt(); private set
    var SURFACE_INPUT = 0xFF0E1220.toInt(); private set
    var BORDER = 0xFF292F45.toInt(); private set
    var BORDER_ACCENT = 0xFF49416F.toInt(); private set
    var BRAND_VIOLET = 0xFF8068FF.toInt(); private set
    var BRAND_VIOLET_LIGHT = 0xFFB9ACFF.toInt(); private set
    var BRAND_BLUE = 0xFF5B68F2.toInt(); private set
    var BRAND_SOFT = 0xFF25213F.toInt(); private set
    var NAV_SELECTED = 0xFF211D38.toInt(); private set
    var TEXT_PRIMARY = 0xFFF7F8FF.toInt(); private set
    var TEXT_SECONDARY = 0xFFB1B8CD.toInt(); private set
    var TEXT_MUTED = 0xFF858EA8.toInt(); private set
    var SUCCESS = 0xFF65E7B0.toInt(); private set
    var SUCCESS_SURFACE = 0xFF12362F.toInt(); private set
    var DANGER = 0xFFFFB5C3.toInt(); private set
    var DANGER_SURFACE = 0xFF321C29.toInt(); private set

    fun apply(mode: String) {
        theme = mode.takeIf { it in setOf("dark", "light", "oled") } ?: "dark"
        when (theme) {
            "light" -> {
                BACKGROUND = 0xFFF4F5FA.toInt()
                NAVIGATION = 0xFFFFFFFF.toInt()
                SURFACE = 0xFFFFFFFF.toInt()
                SURFACE_RAISED = 0xFFE9EBF5.toInt()
                SURFACE_INPUT = 0xFFF0F1F8.toInt()
                BORDER = 0xFFD9DCEC.toInt()
                BORDER_ACCENT = 0xFFC9C0F4.toInt()
                BRAND_VIOLET = 0xFF6850E8.toInt()
                BRAND_VIOLET_LIGHT = 0xFF5D48CE.toInt()
                BRAND_BLUE = 0xFF455BE8.toInt()
                BRAND_SOFT = 0xFFEAE6FF.toInt()
                NAV_SELECTED = 0xFFEAE6FF.toInt()
                TEXT_PRIMARY = 0xFF191A28.toInt()
                TEXT_SECONDARY = 0xFF50566B.toInt()
                TEXT_MUTED = 0xFF747B91.toInt()
                SUCCESS = 0xFF147B55.toInt()
                SUCCESS_SURFACE = 0xFFDDF6EA.toInt()
                DANGER = 0xFFAD3151.toInt()
                DANGER_SURFACE = 0xFFFFE8EE.toInt()
            }
            "oled" -> {
                BACKGROUND = 0xFF000000.toInt()
                NAVIGATION = 0xFF050509.toInt()
                SURFACE = 0xFF09090F.toInt()
                SURFACE_RAISED = 0xFF11111B.toInt()
                SURFACE_INPUT = 0xFF05050A.toInt()
                BORDER = 0xFF242435.toInt()
                BORDER_ACCENT = 0xFF443A71.toInt()
                BRAND_VIOLET = 0xFF8068FF.toInt()
                BRAND_VIOLET_LIGHT = 0xFFB9ACFF.toInt()
                BRAND_BLUE = 0xFF5B68F2.toInt()
                BRAND_SOFT = 0xFF171327.toInt()
                NAV_SELECTED = 0xFF171327.toInt()
                TEXT_PRIMARY = 0xFFF7F8FF.toInt()
                TEXT_SECONDARY = 0xFFB1B8CD.toInt()
                TEXT_MUTED = 0xFF858EA8.toInt()
                SUCCESS = 0xFF65E7B0.toInt()
                SUCCESS_SURFACE = 0xFF09251D.toInt()
                DANGER = 0xFFFFB5C3.toInt()
                DANGER_SURFACE = 0xFF26101A.toInt()
            }
            else -> {
                BACKGROUND = 0xFF080A12.toInt()
                NAVIGATION = 0xFF0D101B.toInt()
                SURFACE = 0xFF131725.toInt()
                SURFACE_RAISED = 0xFF1B2031.toInt()
                SURFACE_INPUT = 0xFF0E1220.toInt()
                BORDER = 0xFF292F45.toInt()
                BORDER_ACCENT = 0xFF49416F.toInt()
                BRAND_VIOLET = 0xFF8068FF.toInt()
                BRAND_VIOLET_LIGHT = 0xFFB9ACFF.toInt()
                BRAND_BLUE = 0xFF5B68F2.toInt()
                BRAND_SOFT = 0xFF25213F.toInt()
                NAV_SELECTED = 0xFF211D38.toInt()
                TEXT_PRIMARY = 0xFFF7F8FF.toInt()
                TEXT_SECONDARY = 0xFFB1B8CD.toInt()
                TEXT_MUTED = 0xFF858EA8.toInt()
                SUCCESS = 0xFF65E7B0.toInt()
                SUCCESS_SURFACE = 0xFF12362F.toInt()
                DANGER = 0xFFFFB5C3.toInt()
                DANGER_SURFACE = 0xFF321C29.toInt()
            }
        }
    }

    fun apply(context: Context) {
        apply(context.getSharedPreferences("neotun_ui", Context.MODE_PRIVATE)
            .getString("appearance_theme", "dark") ?: "dark")
    }

    fun color(value: Int): Int = value
    fun gradient(start: Int = BRAND_VIOLET, end: Int = BRAND_BLUE) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
}
