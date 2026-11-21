package dev.astra.ime

import kotlin.math.max
import kotlin.math.min

/**
 * Heights of the expanded candidate panel, in pixels. Pure, so every screen shape can be tested on the JVM.
 *
 * [screen] is the window height, [topInset] and [bottomInset] the bars over it, [chrome] what stays above the panel
 * (candidate strip, repair row) and [keysRoom] the height of the keys the panel replaces. Below API 35 the display height
 * may already exclude a bar; subtracting it again only leaves the host a little more room.
 */
internal class PanelSizing(
    private val screen: Int,
    topInset: Int,
    bottomInset: Int,
    private val chrome: Int,
    private val keysRoom: Int,
    private val density: Float,
) {
    private val usable = max(0, screen - topInset - bottomInset)

    /** The panel may not grow past this: the host keeps room for the line being composed. */
    private val limit = (min(usable - dp(HOST_MIN_DP), (usable * MAX_USABLE).toInt()) - chrome).coerceAtLeast(0)

    /** The two two-row groups: the keys' room, or more where the rows would be cramped, as far as the host allows. */
    val normal: Int = max(keysRoom, min(dp(NORMAL_DP), max(limit, min(dp(NORMAL_MIN_DP), (screen * SHORT_SCREEN_MAX).toInt()))))

    /**
     * One group with its header. The group is about half the usable height, but never past [limit] or [GROUP_MAX_DP]
     * (a large display gains nothing from more rows), and never smaller than the panel it grew from.
     */
    val focused: Int = max(normal, min(dp(HEADER_DP) + min(usable / 2, dp(GROUP_MAX_DP)), limit))

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        /** A header is a touch target: the same height as the candidate rows and the strip. */
        const val HEADER_DP = 44
        /** Two headers and two groups of two 44dp rows. */
        const val NORMAL_DP = 2 * HEADER_DP + 4 * 44
        /** Two headers and two groups of two 32dp rows, which still hold one line of candidate text. */
        const val NORMAL_MIN_DP = 2 * HEADER_DP + 4 * 32
        /** On a short screen the minimum above gives way at this share of the screen. */
        const val SHORT_SCREEN_MAX = 0.6f
        /** Room left to the host below which nothing grows further, and the share of the usable height the IME may take. */
        const val HOST_MIN_DP = 96
        const val MAX_USABLE = 0.75f
        const val GROUP_MAX_DP = 480
        /** Status bar height assumed when the framework delivers no top inset. */
        const val MIN_TOP_DP = 24

        const val EXPAND_ICON = "⤢"
        /** Not every system font has [EXPAND_ICON]; this one is on every Android font set. */
        const val EXPAND_ICON_FALLBACK = "↕"

        fun expandIcon(hasGlyph: (String) -> Boolean): String = if (hasGlyph(EXPAND_ICON)) EXPAND_ICON else EXPAND_ICON_FALLBACK
    }
}
