package dev.astra.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app screen: getting started (enable, switch, try), how to use it in illustrated sections, settings, privacy and
 * licenses. The pictures are the real key layouts drawn by [KeyboardView] in the chosen theme and strips drawn like the
 * keyboard's, repainted when the theme changes; they do not react to touch. The try box is never saved.
 */
class SetupActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var dictionaryProblem: TextView
    private val pictureRepaints = ArrayList<(KeyboardColors) -> Unit>()
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        column.addView(TextView(this).apply {
            setText(R.string.setup_title)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TITLE)
        })
        column.addView(body(getString(R.string.setup_tagline)).apply { setPadding(0, dp(4), 0, dp(12)) })

        column.addView(card(R.string.setup_section_start) { c ->
            status = body("").apply { setTypeface(typeface, Typeface.BOLD) }
            c.addView(status)
            dictionaryProblem = body(getString(R.string.setup_dictionary_failed)).apply { setTextColor(WARNING) }
            c.addView(dictionaryProblem)
            c.addView(Button(this).apply {
                setText(R.string.setup_enable)
                setOnClickListener { openImeSettings() }
            })
            c.addView(Button(this).apply {
                setText(R.string.setup_switch)
                setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
            })
            c.addView(note(getString(R.string.setup_try_label)).apply { setPadding(0, dp(12), 0, 0) })
            c.addView(EditText(this).apply {
                isSaveEnabled = false // the try box is never restored or persisted
                setHint(R.string.setup_try_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            })
        })

        column.addView(heading(R.string.setup_section_guide))
        column.addView(note(getString(R.string.guide_pictures_note)).apply { setPadding(dp(4), 0, 0, dp(8)) })
        val colors = ThemeSettings.theme(this).colors
        column.addView(card(R.string.guide_kana_title) { c ->
            c.addView(keyboardPicture(Keyboards.kana("⏎"), colors))
            c.addView(points(R.array.guide_kana_items))
        })
        column.addView(card(R.string.guide_convert_title) { c ->
            c.addView(stripPicture(colors, GuideExamples.segments(GuideExamples.CONVERT) + listOf(
                Chip.DIVIDER to "", Chip.SHOWN to GuideExamples.surface(GuideExamples.CONVERT), Chip.CANDIDATE to "白玉餡蜜",
                Chip.CANDIDATE to GuideExamples.reading(GuideExamples.CONVERT),
            )))
            c.addView(points(R.array.guide_convert_items))
        })
        column.addView(card(R.string.guide_segments_title) { c ->
            c.addView(stripPicture(colors, GuideExamples.segments(GuideExamples.SELECTED, selected = 1) + listOf(
                Chip.DIVIDER to "", Chip.SHOWN to "あんみつ", Chip.CANDIDATE to "餡蜜", Chip.CANDIDATE to "アンミツ",
            )))
            c.addView(points(R.array.guide_segments_items))
        })
        column.addView(card(R.string.guide_symbols_title) { c ->
            c.addView(stripPicture(colors, listOf(
                Chip.SEGMENT to "括弧", Chip.DIVIDER to "", Chip.SHOWN to "括弧", Chip.CANDIDATE to "カッコ", Chip.CANDIDATE to "かっこ",
                Chip.CANDIDATE to "（）", Chip.CANDIDATE to "「」", Chip.CANDIDATE to "【】",
            )))
            c.addView(points(R.array.guide_symbols_items))
        })
        column.addView(card(R.string.guide_english_title) { c ->
            c.addView(keyboardPicture(Keyboards.english12(Shift.OFF, "⏎"), colors))
            c.addView(points(R.array.guide_english_items))
        })
        column.addView(card(R.string.guide_more_title) { c -> c.addView(points(R.array.guide_more_items)) })

        column.addView(heading(R.string.setup_section_settings))
        column.addView(card(null) { c ->
            c.addView(toggle(R.string.setup_live_toggle, DebugSettings.liveConversion(this)) { DebugSettings.setLiveConversion(this, it) })
            c.addView(note(getString(R.string.setup_live_note)))
            c.addView(toggle(R.string.setup_correction_toggle, DebugSettings.correctionEnabled(this)) { DebugSettings.setCorrectionEnabled(this, it) })
            c.addView(note(getString(R.string.setup_correction_note)))
            c.addView(toggle(R.string.setup_zenz_toggle, DebugSettings.zenzEnabled(this)) { DebugSettings.setZenzEnabled(this, it) })
            c.addView(note(getString(R.string.setup_zenz_note)))
            c.addView(toggle(R.string.setup_context_toggle, DebugSettings.committedContext(this)) { DebugSettings.setCommittedContext(this, it) })
            c.addView(note(getString(R.string.setup_context_note)))
        })
        column.addView(card(R.string.setup_layout_title) { c ->
            c.addView(toggle(R.string.setup_english_12key, LayoutSettings.english12Key(this)) { LayoutSettings.setEnglish12Key(this, it) })
            c.addView(toggle(R.string.setup_numeric_12key, LayoutSettings.numeric12Key(this)) { LayoutSettings.setNumeric12Key(this, it) })
        })
        column.addView(card(R.string.setup_theme_title) { c -> c.addView(themeChoice()) })
        column.addView(card(null) { c -> addLearningSection(c) })

        column.addView(heading(R.string.setup_section_privacy))
        column.addView(card(null) { c -> c.addView(points(R.array.setup_privacy_items)) })
        column.addView(card(R.string.setup_section_licenses) { c ->
            c.addView(note(getString(R.string.setup_licenses_note)))
            c.addView(Button(this).apply {
                setText(R.string.setup_licenses_open)
                setOnClickListener { showLicenses() }
            })
        })
        column.addView(note(getString(R.string.setup_version, versionName())).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(PAGE)
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus() // the IME picker returns focus without onResume
    }

    // --- Building blocks -------------------------------------------------------------------------

    private fun heading(title: Int) = TextView(this).apply {
        setText(title)
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TITLE)
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    /** A rounded section with an optional [title]; [fill] adds its content. */
    private fun card(title: Int?, fill: (LinearLayout) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 16 * density
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        if (title != null) addView(TextView(context).apply {
            setText(title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TITLE)
            setPadding(0, 0, 0, dp(8))
        })
        fill(this)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(BODY)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun note(text: String) = body(text).apply {
        textSize = 13f
        setTextColor(NOTE)
    }

    /** One point per line, each with a bullet and a hanging indent. */
    private fun points(items: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
        for (item in resources.getStringArray(items)) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(body("•").apply { setPadding(0, dp(4), dp(8), dp(4)) })
                addView(body(item), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        setText(label)
        textSize = 15f
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    /** The real key layout [rows], drawn as the keyboard draws it; touches do nothing. */
    private fun keyboardPicture(rows: List<List<Key>>, colors: KeyboardColors) = KeyboardView(this).apply {
        tag = TAG_GUIDE_PICTURE
        this.colors = colors
        showPreview = false
        setRows(rows)
        interactive = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = GradientDrawable().apply { cornerRadius = 12 * density; setColor(colors.background) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        }
        pictureRepaints += { c ->
            this.colors = c
            (background as GradientDrawable).setColor(c.background)
        }
    }

    /** A candidate strip as the keyboard shows it (same colors and sizes), for the pictures only. */
    private fun stripPicture(colors: KeyboardColors, chips: List<Pair<Chip, String>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        fillStrip(row, colors, chips)
        return HorizontalScrollView(this).apply {
            tag = TAG_GUIDE_PICTURE
            isHorizontalScrollBarEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = GradientDrawable().apply { cornerRadius = 12 * density; setColor(colors.background) }
            setPadding(0, dp(6), 0, dp(6))
            addView(row)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
            pictureRepaints += { c ->
                (background as GradientDrawable).setColor(c.background)
                fillStrip(row, c, chips)
            }
        }
    }

    private fun fillStrip(row: LinearLayout, colors: KeyboardColors, chips: List<Pair<Chip, String>>) {
        row.removeAllViews()
        for ((kind, label) in chips) {
            if (kind == Chip.DIVIDER) {
                row.addView(View(this).apply { setBackgroundColor(colors.hint) },
                    LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), dp(22)).apply { setMargins(dp(6), 0, dp(6), 0) })
                continue
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                val selected = kind == Chip.SHOWN || kind == Chip.SELECTED
                setTextColor(if (selected) colors.onShown else if (kind == Chip.SEGMENT) colors.primary else colors.text)
                if (selected) background = GradientDrawable().apply { cornerRadius = 8 * density; setColor(colors.shown) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
    }

    /** One radio button per theme, each with a swatch: a key on the keyboard's background. */
    private fun themeChoice(): RadioGroup {
        val current = ThemeSettings.theme(this)
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            for (theme in KeyboardTheme.entries) {
                addView(RadioButton(this@SetupActivity).apply {
                    id = View.generateViewId()
                    tag = theme
                    setText(theme.label)
                    textSize = 15f
                    isChecked = theme == current
                    compoundDrawablePadding = dp(12)
                    setCompoundDrawablesRelative(null, null, swatch(theme.colors), null)
                    setOnClickListener {
                        ThemeSettings.setTheme(this@SetupActivity, theme)
                        pictureRepaints.forEach { repaint -> repaint(theme.colors) }
                    }
                })
            }
        }
    }

    /** A key on the keyboard's background with a stroke of the theme's accent, so the two cream themes are told apart. */
    private fun swatch(colors: KeyboardColors): LayerDrawable {
        val key = GradientDrawable().apply {
            setColor(colors.key)
            setStroke(dp(6), colors.background)
            cornerRadius = 6 * density
        }
        val accent = GradientDrawable().apply {
            setColor(colors.primary)
            cornerRadius = 2 * density
        }
        return LayerDrawable(arrayOf(key, accent)).apply {
            setLayerInset(1, dp(18), dp(12), dp(18), dp(12))
            setBounds(0, 0, dp(56), dp(28))
        }
    }

    /** Learning switch, erase button and the registered-word list (reading in hiragana -> any surface). */
    private fun addLearningSection(column: LinearLayout) {
        val store = Learning.store(this)
        val history = note("")
        fun refreshHistory() {
            history.text = getString(R.string.setup_learning_count, store.historySize)
        }
        column.addView(toggle(R.string.setup_learning_toggle, Learning.enabled(this)) { Learning.setEnabled(this, it) })
        column.addView(note(getString(R.string.setup_learning_note)))
        refreshHistory()
        column.addView(history)
        column.addView(Button(this).apply {
            setText(R.string.setup_learning_clear)
            setOnClickListener {
                store.clearHistory()
                refreshHistory()
            }
        })

        column.addView(TextView(this).apply {
            setText(R.string.setup_register_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TITLE)
            setPadding(0, dp(16), 0, 0)
        })
        column.addView(note(getString(R.string.setup_register_note)))
        val reading = EditText(this).apply {
            isSaveEnabled = false
            setHint(R.string.setup_register_reading)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val surface = EditText(this).apply {
            isSaveEnabled = false
            setHint(R.string.setup_register_surface)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val message = note("")
        fun refreshList() {
            list.removeAllViews()
            for ((r, s) in store.registeredWords()) {
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(body(getString(R.string.setup_register_item, r, s)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Button(this@SetupActivity).apply {
                        setText(R.string.setup_register_delete)
                        setOnClickListener {
                            store.unregister(r, s)
                            refreshList()
                        }
                    })
                })
            }
        }
        column.addView(reading)
        column.addView(surface)
        column.addView(Button(this).apply {
            setText(R.string.setup_register_add)
            setOnClickListener {
                // Katakana readings are accepted and stored as hiragana, as the converter reads them.
                val r = reading.text.toString().trim().map { if (it.code in 0x30A1..0x30F6) (it.code - 0x60).toChar() else it }.joinToString("")
                if (store.register(r, surface.text.toString().trim())) {
                    reading.text.clear()
                    surface.text.clear()
                    message.text = ""
                    refreshList()
                } else {
                    message.setText(R.string.setup_register_invalid)
                }
            }
        })
        column.addView(message)
        column.addView(list)
        refreshList()
    }

    /** Every bundled notice (assets/licenses, the overview first) in one scrolling dialog. */
    private fun showLicenses() {
        val names = assets.list(LICENSES).orEmpty().sortedWith(compareBy({ it != OVERVIEW }, { it }))
        val text = names.joinToString("\n\n") { name ->
            val content = try {
                assets.open("$LICENSES/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: java.io.IOException) {
                ""
            }
            "━━ $name ━━\n$content"
        }
        val view = ScrollView(this).apply {
            addView(TextView(this@SetupActivity).apply {
                this.text = text
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_section_licenses)
            .setView(view)
            .setPositiveButton(R.string.setup_close, null)
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // No settings screen available; the status text still guides the user.
        }
    }

    private fun refreshStatus() {
        dictionaryProblem.visibility = if (DebugSettings.dictionaryFailed(this)) View.VISIBLE else View.GONE
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val currentPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            imm?.currentInputMethodInfo?.packageName
        } else {
            runCatching {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            }.getOrNull()?.substringBefore('/')
        }
        val (text, color) = when {
            enabled && currentPackage == packageName -> R.string.setup_status_selected to OK
            enabled -> R.string.setup_status_enabled to BODY
            else -> R.string.setup_status_disabled to WARNING
        }
        status.setText(text)
        status.setTextColor(color)
    }

    private companion object {
        const val LICENSES = "licenses"
        const val OVERVIEW = "THIRD-PARTY.md"
        val PAGE = 0xFFF1F3F4.toInt()
        val TITLE = 0xFF202124.toInt()
        val BODY = 0xFF3C4043.toInt()
        val NOTE = 0xFF5F6368.toInt()
        val WARNING = 0xFFB3261E.toInt()
        val OK = 0xFF137333.toInt()
    }
}

/** The key layouts and strips drawn as pictures in the guide (read by tests). */
internal const val TAG_GUIDE_PICTURE = "guide-picture"

internal enum class Chip { SHOWN, CANDIDATE, SEGMENT, SELECTED, DIVIDER }

/**
 * Words of the guide's strip pictures, drawn by hand and never asked of the converter. Each is (reading, surface) per
 * segment, so a picture's buttons always add up to its shown candidate and its kana.
 */
internal object GuideExamples {
    val CONVERT = listOf("しらたま" to "白玉", "あんみつ" to "あんみつ")
    val SELECTED = listOf("まっちゃ" to "抹茶", "あんみつ" to "あんみつ")

    fun reading(parts: List<Pair<String, String>>) = parts.joinToString("") { it.first }
    fun surface(parts: List<Pair<String, String>>) = parts.joinToString("") { it.second }

    fun segments(parts: List<Pair<String, String>>, selected: Int = -1) =
        parts.mapIndexed { i, (_, s) -> (if (i == selected) Chip.SELECTED else Chip.SEGMENT) to s }
}
