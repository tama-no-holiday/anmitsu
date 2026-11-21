package dev.astra.ime

import android.content.Context
import android.content.SharedPreferences

/** Every color the keyboard draws; views take one of these and hold no color of their own. */
internal class KeyboardColors(
    /** Keyboard, palette grid, candidate panel and the safe area under the keys. */
    val background: Int,
    val key: Int,
    val functionKey: Int,
    val pressed: Int,
    val shadow: Int,
    val highlight: Int,
    /** Enlarged preview bubble of the key being pressed. */
    val accent: Int,
    val onAccent: Int,
    val text: Int,
    /** Small flick hints on the keys. */
    val hint: Int,
    /** First candidate and the strip buttons (▼, 直す, layout toggle). */
    val primary: Int,
    /** Candidate or unit currently shown, selected palette tab. */
    val shown: Int,
    val onShown: Int,
    val repairRow: Int,
    val tabText: Int,
    val scrollbar: Int,
    val ripple: Int,
    /** Dark navigation bar buttons are needed on this background. */
    val lightNavigation: Boolean,
)

/** The stored choice. [WHITE] is the look of every version before 0.1.16 and stays the default. */
internal enum class KeyboardTheme(val id: String, val label: Int, val colors: KeyboardColors) {
    WHITE(
        "white", R.string.setup_theme_white,
        KeyboardColors(
            background = 0xFFE8EAED.toInt(), key = 0xFFFFFFFF.toInt(), functionKey = 0xFFD3D7DC.toInt(),
            pressed = 0xFFBDC1C6.toInt(), shadow = 0xFFC4C8CC.toInt(), highlight = 0xFFBBDEFB.toInt(),
            accent = 0xFF1565C0.toInt(), onAccent = 0xFFFFFFFF.toInt(), text = 0xFF202124.toInt(), hint = 0xFF555C61.toInt(),
            primary = 0xFF0D47A1.toInt(), shown = 0xFFD2E3FC.toInt(), onShown = 0xFF0D47A1.toInt(),
            repairRow = 0xFFE8EAED.toInt(), tabText = 0xFF5F6368.toInt(), scrollbar = 0x66000000, ripple = 0x1F000000,
            lightNavigation = true,
        ),
    ),
    GRAY(
        "gray", R.string.setup_theme_gray,
        KeyboardColors(
            background = 0xFFB8BCC2.toInt(), key = 0xFFE1E3E6.toInt(), functionKey = 0xFFC9CCD1.toInt(),
            pressed = 0xFFA3A8AE.toInt(), shadow = 0xFF8F949A.toInt(), highlight = 0xFFA9CBEF.toInt(),
            accent = 0xFF0D47A1.toInt(), onAccent = 0xFFFFFFFF.toInt(), text = 0xFF17181A.toInt(), hint = 0xFF44494E.toInt(),
            primary = 0xFF0A2F6E.toInt(), shown = 0xFFC5D6F0.toInt(), onShown = 0xFF0A2F6E.toInt(),
            repairRow = 0xFFC9CCD1.toInt(), tabText = 0xFF303438.toInt(), scrollbar = 0x88000000.toInt(), ripple = 0x33000000,
            lightNavigation = true,
        ),
    ),
    DARK(
        "dark", R.string.setup_theme_dark,
        KeyboardColors(
            background = 0xFF1B1C1F.toInt(), key = 0xFF3C4043.toInt(), functionKey = 0xFF2A2C30.toInt(),
            pressed = 0xFF5F6368.toInt(), shadow = 0xFF0E0F10.toInt(), highlight = 0xFF1F4E8C.toInt(),
            accent = 0xFF8AB4F8.toInt(), onAccent = 0xFF0B1E3A.toInt(), text = 0xFFE8EAED.toInt(), hint = 0xFFBDC1C6.toInt(),
            primary = 0xFF8AB4F8.toInt(), shown = 0xFF1F4E8C.toInt(), onShown = 0xFFE8EAED.toInt(),
            repairRow = 0xFF2A2C30.toInt(), tabText = 0xFFBDC1C6.toInt(), scrollbar = 0x88FFFFFF.toInt(), ripple = 0x33FFFFFF,
            lightNavigation = false,
        ),
    ),
    /** Dark reddish bean base from the mark's azuki, cream keys' text and gold selection. */
    AZUKI(
        "azuki", R.string.setup_theme_azuki,
        KeyboardColors(
            background = 0xFF3B1A22.toInt(), key = 0xFF6B2D3A.toInt(), functionKey = 0xFF522230.toInt(),
            pressed = 0xFF823F4E.toInt(), shadow = 0xFF1E0C11.toInt(), highlight = 0xFF7A5A1F.toInt(),
            accent = 0xFFD9B76E.toInt(), onAccent = 0xFF3B1A22.toInt(), text = 0xFFFAF7F2.toInt(), hint = 0xFFE9DCD0.toInt(),
            primary = 0xFFE8CD8F.toInt(), shown = 0xFFD9B76E.toInt(), onShown = 0xFF3B1A22.toInt(),
            repairRow = 0xFF522230.toInt(), tabText = 0xFFE9DCD0.toInt(), scrollbar = 0x88FAF7F2.toInt(), ripple = 0x33FAF7F2,
            lightNavigation = false,
        ),
    ),
    /** Warm cream with azuki ink; gold and the mark's green only mark what is highlighted or selected. */
    ANMITSU(
        "anmitsu", R.string.setup_theme_anmitsu,
        KeyboardColors(
            background = 0xFFF1E9DD.toInt(), key = 0xFFFFFDF9.toInt(), functionKey = 0xFFE6DCCD.toInt(),
            pressed = 0xFFD9CBB8.toInt(), shadow = 0xFFCDBFAC.toInt(), highlight = 0xFFEAD9AC.toInt(),
            accent = 0xFF6B2D3A.toInt(), onAccent = 0xFFFAF7F2.toInt(), text = 0xFF3A1F27.toInt(), hint = 0xFF5E4449.toInt(),
            primary = 0xFF6B2D3A.toInt(), shown = 0xFF9DB5A1.toInt(), onShown = 0xFF3A1F27.toInt(),
            repairRow = 0xFFE6DCCD.toInt(), tabText = 0xFF5E4449.toInt(), scrollbar = 0x666B2D3A, ripple = 0x1F6B2D3A,
            lightNavigation = true,
        ),
    );

    companion object {
        /** Anything but a known id (nothing stored yet, a later version's theme) is [WHITE]. */
        fun fromId(id: String?): KeyboardTheme = entries.firstOrNull { it.id == id } ?: WHITE
    }
}

/** The keyboard color choice, stored in private preferences; never any typed text. */
internal object ThemeSettings {
    private const val PREFS = "astra_theme"
    private const val THEME = "theme"

    fun theme(context: Context): KeyboardTheme = KeyboardTheme.fromId(prefs(context).getString(THEME, null))

    fun setTheme(context: Context, theme: KeyboardTheme) {
        prefs(context).edit().putString(THEME, theme.id).apply()
    }

    /** The IME uses this to repaint at once while the setup screen and the keyboard are both on screen. */
    fun listen(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
