package com.neotun.app

import android.content.Context
import android.graphics.drawable.GradientDrawable

/** Shared visual tokens for NeoTUN Android UI. */
internal object NeoTunDesign {
    private var theme = "dark"

    var BACKGROUND = 0xFF071018.toInt(); private set
    var NAVIGATION = 0xFF0B1722.toInt(); private set
    var SURFACE = 0xFF10202D.toInt(); private set
    var SURFACE_RAISED = 0xFF19303E.toInt(); private set
    var SURFACE_INPUT = 0xFF0A1924.toInt(); private set
    var BORDER = 0xFF27404D.toInt(); private set
    var BORDER_ACCENT = 0xFF2E7880.toInt(); private set
    var BRAND_VIOLET = 0xFF18B6A4.toInt(); private set
    var BRAND_VIOLET_LIGHT = 0xFF78E8DA.toInt(); private set
    var BRAND_BLUE = 0xFF4389F5.toInt(); private set
    var BRAND_SOFT = 0xFF12323A.toInt(); private set
    var NAV_SELECTED = 0xFF12323A.toInt(); private set
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
                BORDER_ACCENT = 0xFF9BDDD7.toInt()
                BRAND_VIOLET = 0xFF078D83.toInt()
                BRAND_VIOLET_LIGHT = 0xFF087C78.toInt()
                BRAND_BLUE = 0xFF3478E5.toInt()
                BRAND_SOFT = 0xFFD9F6F2.toInt()
                NAV_SELECTED = 0xFFD9F6F2.toInt()
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
                BORDER_ACCENT = 0xFF276B70.toInt()
                BRAND_VIOLET = 0xFF18B6A4.toInt()
                BRAND_VIOLET_LIGHT = 0xFF78E8DA.toInt()
                BRAND_BLUE = 0xFF4389F5.toInt()
                BRAND_SOFT = 0xFF091B21.toInt()
                NAV_SELECTED = 0xFF091B21.toInt()
                TEXT_PRIMARY = 0xFFF7F8FF.toInt()
                TEXT_SECONDARY = 0xFFB1B8CD.toInt()
                TEXT_MUTED = 0xFF858EA8.toInt()
                SUCCESS = 0xFF65E7B0.toInt()
                SUCCESS_SURFACE = 0xFF09251D.toInt()
                DANGER = 0xFFFFB5C3.toInt()
                DANGER_SURFACE = 0xFF26101A.toInt()
            }
            else -> {
                BACKGROUND = 0xFF071018.toInt()
                NAVIGATION = 0xFF0B1722.toInt()
                SURFACE = 0xFF10202D.toInt()
                SURFACE_RAISED = 0xFF19303E.toInt()
                SURFACE_INPUT = 0xFF0A1924.toInt()
                BORDER = 0xFF27404D.toInt()
                BORDER_ACCENT = 0xFF2E7880.toInt()
                BRAND_VIOLET = 0xFF18B6A4.toInt()
                BRAND_VIOLET_LIGHT = 0xFF78E8DA.toInt()
                BRAND_BLUE = 0xFF4389F5.toInt()
                BRAND_SOFT = 0xFF12323A.toInt()
                NAV_SELECTED = 0xFF12323A.toInt()
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

    /** Rounded surfaces used by the connection, server and settings cards. */
    fun rounded(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 0f,
        density: Float = 1f
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        if (strokeColor != null && strokeWidthDp > 0f) {
            setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun cardDrawable(density: Float, raised: Boolean = false, strokeAccent: Boolean = false): GradientDrawable =
        rounded(
            color = if (raised) SURFACE_RAISED else SURFACE,
            radiusDp = 18f,
            strokeColor = if (strokeAccent) BORDER_ACCENT else BORDER,
            strokeWidthDp = 1f,
            density = density
        )

    fun connectionCardDrawable(density: Float, isRunning: Boolean): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (isRunning) intArrayOf(SUCCESS_SURFACE, SURFACE)
            else intArrayOf(BRAND_SOFT, SURFACE)
        ).apply {
            cornerRadius = 24f * density
            setStroke((1f * density).toInt().coerceAtLeast(1), if (isRunning) SUCCESS else BORDER_ACCENT)
        }

    fun connectButtonDrawable(density: Float, isRunning: Boolean): GradientDrawable =
        if (isRunning) {
            rounded(SUCCESS_SURFACE, 16f, SUCCESS, 1f, density)
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(BRAND_VIOLET, BRAND_BLUE)
            ).apply { cornerRadius = 16f * density }
        }

    fun pingColor(ping: String): Int {
        val value = ping.filter { it.isDigit() }.toIntOrNull() ?: return TEXT_MUTED
        return when {
            value <= 0 -> TEXT_MUTED
            value < 80 -> SUCCESS
            value < 150 -> BRAND_VIOLET_LIGHT
            value < 300 -> 0xFFFFCA7A.toInt()
            else -> DANGER
        }
    }
}
