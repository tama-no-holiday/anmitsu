package dev.astra.ime

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.astra.ime.core.*
import dev.astra.ime.oss.Word

/** Background of the focused repair unit inside the host editor's text, which no keyboard theme applies to. */
private const val SHOWN_BG = 0xFFD2E3FC.toInt()

/** Palette category index of the 履歴 tab. */
private const val HISTORY_TAB = -1

/** Composing writes remembered while waiting for the editor's report (see AstraImeService.tapProven). */
private const val MAX_UNCONFIRMED = 64

/** View tags of the repair controls (also used by the JVM UI tests). */
/** A segment button (文節) in the strip, shown after 変換 or ▼ and while a segment is selected. */
internal const val TAG_SEGMENT = "segment"
internal const val TAG_SEGMENT_DIVIDER = "segment-divider"
/** Strip button switching the English/number screen between 12 keys and full rows. */
internal const val TAG_LAYOUT_TOGGLE = "layout-toggle"
/** Palette area (category tabs + scrolling grid) above the palette's bottom key row. */
internal const val TAG_PALETTE = "palette"
internal const val TAG_PALETTE_TABS = "palette-tabs"
/** Candidate chips of the composed English word. */
internal const val TAG_ENGLISH_CANDIDATE = "english-candidate"
/** Row of the candidates, ▼, 直す and the layout toggle; gone when none of them has anything to show. */
internal const val TAG_STRIP_ROW = "strip-row"
/** One-line clipboard preview; a tap pastes the current clip. */
internal const val TAG_CLIP_PREVIEW = "clip-preview"
/** Expanded candidate panel and, inside it, its two groups and their tappable headers (title, then expand/shrink). */
internal const val TAG_PANEL = "candidate-panel"
internal const val TAG_PANEL_TOP = "panel-top"
internal const val TAG_PANEL_EXACT = "panel-exact"
internal const val TAG_PANEL_TOP_HEADER = "panel-top-header"
internal const val TAG_PANEL_EXACT_HEADER = "panel-exact-header"

/** The panel group holding the whole panel; the other one is hidden. */
private enum class PanelGroup { TOP, EXACT }

/**
 * Trial 12-key flick IME. Composition state lives in [InputSession] (main thread only);
 * conversion runs on [ConversionWorker] with immutable tokens. Nothing is persisted or logged,
 * and committed text is never rewritten: candidates are committed only by explicit Enter, Space
 * or candidate taps (変換 only changes what the composition shows). The arrows move a caret inside the composition
 * (0.1.34) and commit nothing.
 *
 * Repair mode (直す in the candidate strip) edits one unit of the uncommitted composition at a time
 * ([RepairSession]); it never commits. 完了 keeps the repaired units as a fixed head of the composition and
 * live typing continues after them; Enter commits everything as shown. A tap inside the composition in the host
 * editor opens repair on the unit under the tap (or moves the focus there) when the editor's selection report
 * proves it hit the text we show; otherwise the tap is ignored and 直す remains (see [onUpdateSelection]).
 *
 * While nothing is composed the strip shows only the clipboard preview ([ClipboardPreview]), or goes away with no
 * clip. A tap on the preview pastes the clip as it is at that moment and, if the editor accepted it, hides the preview
 * for that clip ([ConsumedClip]); the system clipboard is never changed and pasted text is never learned.
 *
 * Ranking context: the field is asked for the text before the cursor once per composition, off the main thread with a
 * 100 ms limit ([askField], [HostContext]); otherwise EditorInfo's snapshot and our own commits ([CommittedContext]).
 * Open only so JVM tests can substitute the editor connection and the clipboard; members stay final.
 */
open class AstraImeService : InputMethodService(), KeyboardView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val session = InputSession()
    private lateinit var worker: ConversionWorker

    private var inputRoot: LinearLayout? = null
    private var keyboard: KeyboardView? = null
    private var stripBar: LinearLayout? = null
    private var strip: LinearLayout? = null
    private var stripScroll: HorizontalScrollView? = null
    private var expandButton: TextView? = null
    private var panel: LinearLayout? = null
    private var panelTop: CandidateSectionView? = null
    private var panelExact: CandidateSectionView? = null
    private var panelTopHeader: LinearLayout? = null
    private var panelExactHeader: LinearLayout? = null
    /** The candidate panel replaces the keys while open; any edit or commit closes it. */
    private var expanded = false
    /** Non-null: this group alone fills an enlarged panel, as a vertical grid. Closing the panel always clears it. */
    private var panelFocus: PanelGroup? = null
    /** Height of the keys (and palette) the open panel replaced. */
    private var panelKeysRoom = 0
    /** Identifies the open panel's lookup; a result for any other one is stale. Reset whenever the panel closes. */
    private var panelRequest: Any? = null
    private var panelData: ExactPanel? = null
    /** What each group lists now, in drawing order; a tap picks from these. */
    private var topCandidates: List<Candidate> = emptyList()
    private var exactCandidates: List<Candidate> = emptyList()
    /** Latest top inset of the input window, for the panel height. */
    private var topInset = 0
    private val expandIcon by lazy { android.graphics.Paint().let { p -> PanelSizing.expandIcon { p.hasGlyph(it) } } }

    /** Live conversion: the composing text shows candidate [shownIndex] (-1: the raw kana). */
    private var live = true
    private var shownIndex = -1
    /**
     * What we wrote into the composing span since the editor last reported one of those writes, oldest first.
     * Only a tap proven against the current [displayed] text may focus a unit ([tapProven]). Memory only, for the
     * current composition (cleared with [displayed]); never logged.
     */
    private val unconfirmed = ArrayList<String>()
    /** The editor reported a span of the current [displayed] length after our latest write. */
    private var latestConfirmed = false
    /** Too many writes without a report: taps are not trusted until the composition ends. */
    private var unconfirmedOverflow = false

    /** Text currently in the composing span; differs from the raw kana while a conversion is shown. */
    private var displayed = ""
        set(value) {
            field = value
            if (value.isEmpty()) {
                forgetWrites()
                asked = null // the composition it was asked for is over
            }
        }
    /**
     * What is known of the committed text before the cursor: left context for the zenz judge and nothing else. Never
     * stored or learned from, and never mixed with what the composing span shows.
     */
    private val shadow = CommittedContext()
    /** The field allows context (EditorPolicy.allowsCommittedContext), checked at editor start. */
    private var contextAllowed = false
    private val contextSwitch = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!DebugSettings.committedContext(this)) {
            shadow.clear()
            asked = null
        } else if (contextAllowed) {
            shadow.resume()
        }
    }
    /**
     * Asks the field for the text before the cursor on the first key of a composition ([askField]). Internal so tests can
     * give it a clock; the asking itself is [hostReads].
     */
    internal var hostContext = HostContext()
    /** The editor belongs to this process (the settings try box): its connection is not a remote one, see [askField]. */
    private var ownProcess = false
    /** Bumped by every field start and every commit: an answer is about the field and the text it was read in. */
    private var fieldStarts = 0L
    private var commits = 0L
    /** What the field answered for the open composition, and when. Memory only, dropped with the composition. */
    private class Asked(val text: String, val fieldStart: Long, val commits: Long, val generation: Long)
    private var asked: Asked? = null
    /** Raw kana and surface of the last shown conversion, kept while typing extends that reading. */
    private var lastShownRaw = ""
    private var lastShownSurface = ""

    /** Non-null in repair mode. The session is untouched until 完了/確定, so 取消 restores it as it was. */
    private var repair: RepairSession? = null
    /**
     * 変換 or ▼ revealed the segment buttons of what the composition shows (until the next edit); a tap selects that
     * segment (a [RepairSession] without its own buttons). See [renderSegments].
     */
    private var segmentsShown = false

    private var layoutButton: TextView? = null
    private var paletteBox: LinearLayout? = null
    private var paletteTabs: LinearLayout? = null
    private var paletteGrid: PaletteView? = null
    /** Selected category of each palette, [HISTORY_TAB] for its 履歴; the selection itself is memory only. */
    private var symbolCategory = 0
    private var emojiCategory = 0
    /** The 履歴 as it was when its tab was shown: a tap there does not reorder what is under the finger. */
    private var shownRecent: List<String> = emptyList()
    /**
     * Palette entries tapped (emoji palette or not) and not yet committed: they count as used only when a commit
     * that contains them succeeds ([recordEntries]); a composition cancelled, dropped or deleted forgets them.
     */
    private val pendingEntries = ArrayList<Pair<Boolean, String>>()
    /** Category entries the device font can draw, computed once per category on first display. */
    private val renderable = HashMap<Palettes.Category, List<String>>()

    private var mode = KeyboardMode.KANA
    /** Last text screen (かな, English or numbers); a palette's back key returns to it. */
    private var textMode = KeyboardMode.KANA
    /** The text screen (かな or English) numbers were opened from; their mode key goes back to it. */
    private var numberReturn = KeyboardMode.KANA
    /** Plain text field: digits typed on the English keys compose and convert ([EditorPolicy.composesDigits]). */
    private var composesDigits = false
    /** Text field: anything typed from the English side composes, even a first symbol ([EditorPolicy.composesSymbols]). */
    private var composesSymbols = false
    private var shift = Shift.OFF
    private var enterLabel = "⏎"
    /** Token of the last successful apply; the only token allowed for choose(). */
    private var appliedToken: SessionToken? = null
    /** Dictionary words of each applied candidate (same order), for learning the explicit choice. */
    private var appliedWords: List<List<Word>> = emptyList()

    private lateinit var clipboard: ClipboardPreview
    private var colors = KeyboardTheme.WHITE.colors
    private val themeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyTheme()
        applyLayout(keepGesture = true)
        render()
    }

    private lateinit var learning: LearningStore
    /**
     * [fieldLearning]: the editor allows on-device history (EditorPolicy.allowsLearning).
     * [personalized]: it is also switched on; history is then both recorded and used. Read each time, not at editor
     * start: the settings screen has a try box, which does not restart when the switch beside it changes.
     */
    private var fieldLearning = false
    private val personalized: Boolean get() = fieldLearning && Learning.enabled(this)
    /** [composingState] the current rows were built for. */
    private var layoutState = 0
    private var password = false

    /**
     * Editor selection from EditorInfo/onUpdateSelection, -1 when unknown. Never read from the
     * field. Mostly "range vs collapsed" drives decisions: our own edits always leave a collapsed
     * cursor, whose offset is an estimate until the editor reports it.
     */
    private var selStart = -1
    private var selEnd = -1
    /** Estimated offset where the current composition starts, -1 when unknown. */
    private var composeStart = -1

    /**
     * English composition started by a letter on the English screens: the word, and the symbols, digits and palette
     * entries typed after it from any screen ([EnglishWord]). Never at the same time as a kana composition: letters typed
     * while kana is composed join the kana as before, and a kana key takes the English text over as the start of the kana
     * composition. Memory only; nothing about English typing is stored or learned.
     */
    private val english = EnglishWord()
    private lateinit var englishLookup: EnglishLookup
    /** Bumped by every change of the English letters, commit, drop or new editor; older lookups are discarded. */
    private var englishGen = 0L
    private var englishStart = -1
    /**
     * Index results by lower-cased word for the words of the open English composition ([WholeCandidates.lookups]). Memory
     * only, dropped with the composition.
     */
    private val englishResults = HashMap<String, dev.astra.ime.oss.EnglishLexicon.Result>()
    /** Internal for tests: the words looked up for the composition as shown, comma-joined, once all have answered. */
    internal var englishResultKey = ""
        private set
    /**
     * Index results by lower-cased word for the English part a selected segment or the composing caret scopes
     * ([MixedSegment]). Memory only, dropped with the segment selection; [segmentGen] drops answers asked before that.
     */
    private val segmentResults = HashMap<String, dev.astra.ime.oss.EnglishLexicon.Result>()
    private var segmentGen = 0L
    /** What the composition showed when the arrows put a caret into its kana; shown again if they leave it untouched. */
    private var caretEntry = ""
    /** What our writes should report, in every field, so that a late report does not end what was written since. */
    private val ownReports = OwnReports()

    override fun onCreate() {
        super.onCreate()
        // Its own Paint, used only on the conversion thread: emoji newer than the device font are not offered.
        val glyphs = android.graphics.Paint()
        worker = ConversionWorker(assets, main, java.io.File(noBackupFilesDir, "zenz"), ossVerifiedStamp()) { glyphs.hasGlyph(it) }
        learning = Learning.store(this)
        englishLookup = EnglishLookup(main) { EnglishLookup.fromAssets(assets) }.also { it.preload() }
        clipboard = ClipboardPreview(clipSource(), ConsumedClip.process) { if (!session.hasComposition && english.isEmpty) render() }
        ThemeSettings.listen(this, themeListener)
        DebugSettings.listen(this, contextSwitch)
        worker.load {
            // Hide any identity candidates; request real ones with a fresh token.
            appliedToken = null
            collapse()
            render()
            if (repair != null) requestRepairCandidates() else requestConversion()
        }
    }

    /** The OSS lexicon is hashed once per installed APK; an update changes lastUpdateTime and hashes again. */
    private fun ossVerifiedStamp(): VerifiedStamp? = try {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        VerifiedStamp(java.io.File(noBackupFilesDir, "oss/verified-lexicon"), "$version:${info.lastUpdateTime}")
    } catch (e: Exception) {
        null // hash on every load
    }

    /** Internal for tests: synthetic clips instead of the device clipboard. */
    internal open fun clipSource(): ClipSource = SystemClipSource(this)

    /** Internal for tests: lets them wait for the converter now that no load status is shown. */
    internal val converterReady: Boolean get() = worker.isReady

    /** Internal for tests: the selected segment or composing caret, to inspect its boundaries and candidate scope. */
    internal val repairForTests: RepairSession? get() = repair

    /** Internal for tests: installs a scorer and a small dictionary, see [ConversionWorker.useForTests]. */
    internal val conversionWorker: ConversionWorker get() = worker

    override fun onDestroy() {
        clipboard.hide()
        ThemeSettings.unlisten(this, themeListener)
        DebugSettings.unlisten(this, contextSwitch)
        shadow.clear()
        asked = null
        hostContext.close()
        worker.shutdown()
        englishLookup.shutdown()
        main.removeCallbacksAndMessages(null)
        clearGestures()
        abandonRepair()
        session.finishInput()
        appliedToken = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Bottom/side safe area is root padding outside KeyboardView, so no key sits under OS buttons.
        ImeInsets.install(root) {
            topInset = it.top
            if (expanded) sizePanel()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val expand = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { toggleExpanded() }
        }
        val toggle = TextView(this).apply {
            tag = TAG_LAYOUT_TOGGLE
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = (48 * d).toInt()
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            visibility = View.GONE
            setOnClickListener { toggleLayout() }
        }
        val stripRow = LinearLayout(this).apply {
            tag = TAG_STRIP_ROW
            orientation = LinearLayout.HORIZONTAL
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(expand, LinearLayout.LayoutParams((40 * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(stripRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()))
        val topHeader = panelHeader(TAG_PANEL_TOP_HEADER, R.string.panel_prediction, PanelGroup.TOP)
        val exactHeader = panelHeader(TAG_PANEL_EXACT_HEADER, R.string.panel_exact_title, PanelGroup.EXACT)
        val topSection = CandidateSectionView(this, twoRows = true).apply {
            tag = TAG_PANEL_TOP
            emptyLabel = getString(R.string.panel_prediction_none)
            contentDescription = getString(R.string.panel_prediction)
            onPick = { pickFromPanel(topCandidates, it) }
        }
        val exactSection = CandidateSectionView(this, twoRows = true).apply {
            tag = TAG_PANEL_EXACT
            contentDescription = getString(R.string.panel_exact_title)
            onPick = { pickFromPanel(exactCandidates, it) }
        }
        val headerHeight = (PanelSizing.HEADER_DP * d).toInt()
        val panelView = LinearLayout(this).apply {
            tag = TAG_PANEL
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(topHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight))
            addView(topSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(exactHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight))
            addView(exactSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(panelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        // Palettes: category tabs and the scrolling grid; the fixed bottom row is the KeyboardView below.
        val tabs = LinearLayout(this).apply {
            tag = TAG_PALETTE_TABS
            orientation = LinearLayout.HORIZONTAL
        }
        val grid = PaletteView(this).apply { onPick = { onPaletteEntry(it) } }
        val box = LinearLayout(this).apply {
            tag = TAG_PALETTE
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(HorizontalScrollView(this@AstraImeService).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * d).toInt()))
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val kb = KeyboardView(this).also { it.listener = this }
        root.addView(kb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clearGestures()
        inputRoot = root
        keyboard = kb
        stripBar = stripRow
        strip = row
        stripScroll = scroll
        expandButton = expand
        panel = panelView
        panelTop = topSection
        panelExact = exactSection
        panelTopHeader = topHeader
        panelExactHeader = exactHeader
        layoutButton = toggle
        paletteBox = box
        paletteTabs = tabs
        paletteGrid = grid
        expanded = false
        panelFocus = null
        panelRequest = null
        panelData = null
        applyTheme()
        applyLayout()
        render()
        return root
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // New editor (or restart): previous tokens, candidates, gestures and the clip preview become invalid.
        clipboard.hide()
        worker.cancel()
        // Never writes text: a restart may follow an app-side replacement (even one of the same length, leaving the
        // cursor where our composition ended), and EditorInfo cannot prove the old span still exists. The editor's
        // visible text is kept as-is; a restart only ends the composing state. A new editor is not touched at all.
        if (restarting) currentInputConnection?.finishComposingText()
        pendingEntries.clear()
        resetEnglish()
        abandonRepair()
        shownIndex = -1
        displayed = ""
        lastShownRaw = ""
        // Only passwords and raw editors type directly; private and URL fields still convert.
        session.startInput(EditorPolicy.isDirectInput(attribute))
        // The debug-only OSS switch is applied when an editor starts; its load never blocks :core.
        worker.setOssEnabled(DebugSettings.ossEnabled(this)) {
            DebugSettings.recordDictionaryLoad(this, worker.ossState)
            if (!session.hasComposition) render()
        }
        worker.setZenzEnabled(DebugSettings.zenzEnabled(this)) { if (!session.hasComposition) render() }
        // No-suggestion and address fields: no typo correction. Live conversion follows the global switch.
        worker.correctionEnabled = EditorPolicy.allowsTypoCorrection(attribute) && DebugSettings.correctionEnabled(this)
        live = EditorPolicy.allowsLiveConversion(attribute) && DebugSettings.liveConversion(this)
        // Private fields neither record nor use history, whatever the global learning switch says.
        fieldLearning = EditorPolicy.allowsLearning(attribute)
        // Separate from learning: it is kept nowhere, so the learning switch does not govern it.
        contextAllowed = EditorPolicy.allowsCommittedContext(attribute)
        val repeated = shadow.start(attribute, contextAllowed && DebugSettings.committedContext(this))
        fieldStarts++
        asked = null
        // A restart is the same field: a field that was slow stays unasked.
        if (!restarting) hostContext.newField()
        ownProcess = attribute.packageName == packageName
        collapse()
        appliedToken = null
        shift = Shift.OFF
        password = EditorPolicy.isPassword(attribute.inputType)
        // A configuration change repeats the EditorInfo the field started with: same field, so its reports still come,
        // and old offsets, so what we tracked since stands. Unknown stays unknown until the editor reports.
        if (!repeated) {
            trackSelection(attribute.initialSelStart, attribute.initialSelEnd)
            ownReports.startAt(selStart, selEnd)
        }
        if (selStart >= 0) ownReports.expect(selStart, selEnd)
        composeStart = -1
        mode = EditorPolicy.initialMode(attribute)
        textMode = mode
        numberReturn = KeyboardMode.KANA
        composesDigits = EditorPolicy.composesDigits(attribute)
        composesSymbols = EditorPolicy.composesSymbols(attribute)
        enterLabel = EditorPolicy.enterLabel(attribute)
        clearGestures()
        applyLayout()
        render()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyTheme()
        applyLayout()
        // Direct-input fields (passwords, raw editors) never see the clipboard.
        if (session.isActive && !session.isSensitive) clipboard.show() else clipboard.hide()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clipboard.hide()
        clearGestures()
        // Keyboard hidden while the editor stays active: leave the text as typed, stop composing.
        if (!finishingInput) dropComposition()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        clipboard.hide()
        worker.cancel()
        shadow.clear()
        fieldStarts++
        asked = null
        ownReports.clear()
        pendingEntries.clear()
        if (session.hasComposition) {
            // Leaving the editor never commits a conversion the user did not confirm; repair edits are kept as kana.
            restoreRawDisplay()
            currentInputConnection?.finishComposingText()
        }
        finishEnglish(redraw = false)
        abandonRepair()
        collapse()
        session.finishInput()
        appliedToken = null
        shift = Shift.OFF
        trackSelection(-1, -1)
        clearGestures()
        render()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val continues = ownReports.continues(oldSelStart, oldSelEnd)
        if (!continues && ownReports.overtaken(newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
            // Most likely an old report of ours, but it could be a move, and context before the wrong cursor is worse than none.
            shadow.invalidate()
            asked = null
            return
        }
        val ours = ownReports.take(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!ours) ownReports.adopt(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Ours with a newer write still to be reported: it says where the editor was, and we have written past that.
        val late = ours && continues && ownReports.writesAhead
        if (!late) trackSelection(newSelStart, newSelEnd)
        shadow.report(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val collapsed = newSelStart == newSelEnd
        // Our composing caret ([placeCaret]): exactly the report we expect, inside the span. Not a tap, not a move away.
        val ownCaret = ours && collapsed && candidatesStart >= 0 && newSelStart in candidatesStart..candidatesEnd
        if (!english.isEmpty) {
            englishSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd, late, ownCaret)
            return
        }
        if (!session.hasComposition) return
        // A reported span counts as ours only where it starts at our known start (unknown: adopted from the echo).
        val sameStart = candidatesStart >= 0 && (composeStart < 0 || candidatesStart == composeStart)
        // Our own composing updates always leave a collapsed cursor at the composing end, or where we put the caret.
        // Echoes may lag or be coalesced, so the end is not compared with what is shown; never infer ownership from an
        // echo count.
        val own = sameStart && collapsed && (newSelEnd == candidatesEnd || ownCaret)
        // The raw kana is written back only into a span matching our known start and current length. Any other
        // case (no span, an unrelated span, unknown start) ends composition without writing: setComposingText
        // would otherwise replace someone else's text or insert the kana at the new cursor. Offsets are estimates
        // and the span's text is never read, so this is a best-effort guard, not proof the text is unchanged.
        val spanKept = composeStart >= 0 && candidatesStart == composeStart && candidatesEnd == composeStart + displayed.length
        when {
            own -> {
                composeStart = candidatesStart
                confirmWrite(candidatesEnd - candidatesStart)
            }
            // A caret strictly inside the span we show (the user tapped the composition): it is kept, and a proven
            // tap focuses that unit. The start and end offsets are left to the cases above and below.
            spanKept && collapsed && newSelStart > candidatesStart && newSelStart < candidatesEnd -> {
                // An identical report (old == new) is a duplicate, not a move.
                if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) tapInside(newSelStart - candidatesStart)
            }
            late -> Unit
            else -> dropComposition(spanKept)
        }
    }

    /**
     * An own report of a span of [length]. Reports may lag, be coalesced or repeat, and only carry offsets, so one
     * cannot be matched to a write by counting. It came from some write of that length no
     * earlier than the first such entry of [unconfirmed]; everything before that entry is superseded.
     */
    private fun confirmWrite(length: Int) {
        val first = unconfirmed.indexOfFirst { it.length == length }
        if (first < 0) return // a write from before the last report, or not one of ours
        unconfirmed.subList(0, first).clear()
        if (length == displayed.length) latestConfirmed = true
    }

    /**
     * A tap at surface [offset] belongs to the text we show now only if the editor reported our latest write and no
     * other text of the same length could still be in the span (e.g. 変換 swapping 雨 for 飴): the report cannot
     * tell same-length texts apart. Otherwise the tap is ignored and 直す remains.
     */
    private fun tapProven(): Boolean =
        latestConfirmed && !unconfirmedOverflow && unconfirmed.none { it.length == displayed.length && it != displayed }

    private fun forgetWrites() {
        unconfirmed.clear()
        latestConfirmed = false
        unconfirmedOverflow = false
    }

    /**
     * The user tapped inside the composition at surface [offset] (strictly inside, see [onUpdateSelection]). Opens
     * repair focused on the unit under the tap, or moves the focus in repair mode. Nothing else changes: the text
     * stays as shown (only the highlight moves), no candidate is chosen and nothing is committed. Any doubt (unproven
     * tap, pending result, no reliable units) does nothing: the composition stays and 直す remains.
     */
    private fun tapInside(offset: Int) {
        if (!tapProven() || !session.isActive || session.isSensitive) return
        val r = repair
        if (r == null) {
            enterRepair(at = offset)
            return
        }
        if (r.surface != displayed) return
        val unit = Alignment.unitAt(r.units, offset) ?: return
        val focused = r.focus
        if (r.place(unit, caretFor(r.units, unit, offset))) {
            if (unit != focused) afterRepairChange(request = true)
        }
    }

    /**
     * Caret in unit [unit]'s kana for a tap at surface [offset]: strictly inside the unit it is where the tap was
     * ([Alignment.typedOffset]); on the unit's first character, which only selects it, the unit's end.
     */
    private fun caretFor(units: List<RepairUnit>, unit: Int, offset: Int): Int {
        val inUnit = offset - units.take(unit).sumOf { it.surface.length }
        val u = units[unit]
        return if (inUnit <= 0) u.typed.length else Alignment.typedOffset(u, inUnit)
    }

    private fun trackSelection(start: Int, end: Int) {
        if (start < 0 || end < 0) {
            selStart = -1
            selEnd = -1
        } else {
            selStart = minOf(start, end)
            selEnd = maxOf(start, end)
        }
    }

    /** After our own edit: collapsed at [offset] if the selection was known (offset < 0: unknown). */
    private fun expectCursor(offset: Int) {
        if (selStart >= 0) trackSelection(offset, offset)
    }

    // --- Key input ---------------------------------------------------------------------------

    override fun onFlick(key: FlickKey, direction: FlickDirection) {
        if (!session.isActive) return
        val r = repair
        // 小゛゜ while a segment is selected acts on the kana before its caret, never on another segment.
        if (r != null && key == FlickKey.MODIFIER) {
            if (r.toggleBefore { FlickLayout.toggle(it) }) afterRepairChange(request = true)
            return
        }
        if (key == FlickKey.MODIFIER) {
            toggleLast()
            return
        }
        val kana = FlickLayout.kana(key, direction) ?: return
        if (insertInSegment(kana)) return
        val ic = currentInputConnection ?: return
        if (!english.isEmpty && !english.caretAtEnd && !session.isSensitive) {
            // Moved into an English composition: the kana goes in at its caret as typed, nothing after it is lost.
            insertEnglish(ic, kana)
            return
        }
        if (session.isActive && !session.isSensitive) {
            // A kana after an English word: the word, as shown, starts the kana composition in its own span
            // (Android で converts as one), instead of being committed by the first kana.
            val word = takeEnglish()
            val starting = !session.hasComposition
            // A new composition replaces any selection, starting at its first offset.
            if (starting) composeStart = word?.second ?: selStart
            // After an English word our own span is already in the field, and the answer would contain it: not asked.
            if (starting) askField(ic, ownSpan = word != null)
            if (word != null) session.type(word.first)
            if (session.type(kana)) afterEdit(ic)
        } else {
            commitEnglish()
            commitDirect(ic, kana)
        }
    }

    /**
     * Typing while a segment is selected goes in at that segment's caret: the segment stays selected, the caret moves
     * past [text], the other segments keep what they show and nothing commits; the segment converts again and an older
     * result for it is dropped ([RepairSession.apply]). At the very end of the last segment that is where the composition
     * ends: the segments are kept as shown and [text] starts a new tail (false: the caller types it), so a word chosen
     * there is not converted again. With no segment selected also false.
     */
    private fun insertInSegment(text: String): Boolean {
        val r = repair ?: return false
        val atEnd = r.focus == r.units.lastIndex && r.cursor == r.units[r.focus].typed.length
        if (atEnd || !r.insert(text)) {
            finishRepair()
            return false
        }
        afterRepairChange(request = true)
        return true
    }

    /**
     * Ends the English word without committing it: its letters as shown and the start of its composing span, which
     * the kana composition then takes over (the next composing write replaces the span). Null without a word.
     */
    private fun takeEnglish(): Pair<String, Int>? {
        if (english.isEmpty) return null
        val word = english.shown
        val start = englishStart
        resetEnglish()
        return word to start
    }

    override fun onText(text: String) {
        if (!session.isActive) return
        val ic = currentInputConnection ?: return
        // A selected segment takes any text the composition would (letters, punctuation, palette entries) at its caret.
        if (insertInSegment(text)) Unit else when {
            // ASCII never starts a kana composition, but joins an existing one so no edit is lost.
            session.hasComposition && !session.isSensitive -> if (session.type(text)) afterEdit(ic)
            // An English composition takes letters into its word and anything else (punctuation, digits, palette entries,
            // from whichever screen) at its caret: nothing is committed until Enter, Space or a candidate.
            !english.isEmpty -> if (mode == KeyboardMode.ASCII && EnglishWord.isLetter(text)) typeEnglish(ic, text[0]) else insertEnglish(ic, text)
            // English 12-key down flicks in a text field compose digits, so 1 converts to １ ① 一 Ⅰ … (Supplement). The
            // number screen composes through the English branch below instead.
            composesDigits && mode == KeyboardMode.ASCII && !session.isSensitive && text.length == 1 && text[0] in '0'..'9' -> {
                composeStart = selStart
                askField(ic, ownSpan = false)
                if (session.type(text)) afterEdit(ic)
            }
            // English screens: letters build an uncommitted word with candidates (never in passwords/raw editors).
            mode == KeyboardMode.ASCII && !session.isSensitive && EnglishWord.isLetter(text) -> typeEnglish(ic, text[0])
            // Typed from the English side (its keys, or numbers/palettes opened from them), even a first symbol starts an
            // English composition, so ( converts to （ 「 【 … and the editor gets nothing until a confirm. The number screens
            // (12-key and rows) do so from either side: 1 offers １ ① 一 …, ( offers （ 「 … (number fields keep typing at once).
            composesSymbols && !session.isSensitive && (englishOrigin() || mode == KeyboardMode.SYMBOLS) -> insertEnglish(ic, text)
            // Anything else with nothing composed (punctuation, digits, palette entries) is committed as typed.
            else -> commitDirect(ic, text)
        }
        // One capital, then lower case again; caps lock stays until the shift key is tapped.
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            applyLayout()
        }
    }

    /**
     * Whether the screen in use belongs to the English side: the English keys, numbers opened from them, or a palette opened
     * from either. Reset with every field ([onStartInput]: the initial screen, numbers going back to かな).
     */
    private fun englishOrigin(): Boolean {
        val text = if (mode.isPalette) textMode else mode
        return text == KeyboardMode.ASCII || (text == KeyboardMode.SYMBOLS && numberReturn == KeyboardMode.ASCII)
    }

    /** A palette entry: typed like any text; remembered for the 履歴 only where history is recorded at all. */
    private fun onPaletteEntry(entry: String) {
        if (!session.isActive) return
        if (personalized && mode.isPalette) pendingEntries += (mode == KeyboardMode.EMOJI) to entry
        onText(entry)
    }

    /** A commit of [text] succeeded: the pending palette entries it contains were used; the others were not. */
    private fun recordEntries(text: String) {
        if (pendingEntries.isEmpty()) return
        if (personalized) for ((emoji, entry) in pendingEntries) if (text.contains(entry)) learning.useEntry(emoji, entry)
        pendingEntries.clear()
    }

    override fun onAction(action: Int) {
        if (!session.isActive) return
        if (repair != null && repairAction(action)) return
        when (action) {
            Keyboards.BACKSPACE -> backspace()
            // Gboard/iOS: while composing kana the space key reads 変換 and shows the next candidate.
            Keyboards.SPACE -> if (mode == KeyboardMode.KANA && session.hasComposition) convertNext() else space()
            Keyboards.CONVERT -> convertNext()
            Keyboards.CURSOR_LEFT, Keyboards.CURSOR_RIGHT, Keyboards.CURSOR_UP, Keyboards.CURSOR_DOWN -> arrow(action)
            Keyboards.ENTER -> enter()
            Keyboards.SWITCH_IME -> switchIme()
            Keyboards.HIDE -> requestHideSelf(0)
            Keyboards.TO_KANA -> setMode(KeyboardMode.KANA)
            Keyboards.TO_ASCII -> setMode(KeyboardMode.ASCII)
            // Screens never commit: an English composition stays, and what the new screen types joins it.
            Keyboards.TO_SYMBOLS -> setMode(KeyboardMode.SYMBOLS)
            Keyboards.TO_JSYMBOLS -> setMode(KeyboardMode.JAPANESE_SYMBOLS)
            Keyboards.TO_EMOJI -> setMode(KeyboardMode.EMOJI)
            Keyboards.TO_TEXT -> setMode(textMode)
            Keyboards.MODE_CYCLE -> setMode(Keyboards.cycleNext(if (mode.isPalette) textMode else mode, numberReturn))
            Keyboards.SHIFT -> when {
                english.isEmpty -> {
                    shift = shift.next()
                    applyLayout()
                }
                // Caps lock is left as without a composition; the text stays as it is.
                shift == Shift.LOCK -> {
                    shift = Shift.OFF
                    applyLayout(keepGesture = true)
                }
                // Otherwise only the last character's case, if it is a letter: never earlier ones, never a search back.
                english.toggleLastCase() -> showEnglish()
            }
        }
    }

    /**
     * Toggles only the owned composition. Direct-typing fields are never read, so they get a
     * no-op (Japanese password entry is unsupported in this trial; ASCII is the default there).
     */
    private fun toggleLast() {
        if (!session.hasComposition) return
        val ic = currentInputConnection ?: return
        if (session.toggleLast()) afterEdit(ic)
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (!english.isEmpty) {
            when {
                // The caret at the start of the composition: nothing before it is ours to delete, nothing commits.
                !english.backspace() -> Unit
                !english.isEmpty -> englishChanged(ic)
                else -> {
                    clearSpan(ic, englishStart) // last entry deleted
                    resetEnglish()
                    applyLayout(keepGesture = true)
                    render()
                }
            }
            return
        }
        if (session.hasComposition) {
            if (session.backspace()) afterEdit(ic)
            return
        }
        // Decided from the tracked selection only: getSelectedText can return null or time out.
        val raw = currentInputEditorInfo?.inputType == InputType.TYPE_NULL
        if (!raw && selStart >= 0 && selStart != selEnd) {
            ic.commitText("", 1) // deletes the selection
            expectCursor(selStart)
            ownReports.expect(selStart, selStart)
            shadow.invalidate()
        } else {
            sendDelete()
        }
    }

    /**
     * The editor itself deletes its selection or the preceding character. Only it knows the whole character before
     * the cursor without the field being read: a code point count would split flags, ZWJ emoji and variation
     * selectors. How much went away is therefore unknown until its report.
     */
    private fun sendDelete() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        trackSelection(-1, -1)
        ownReports.expectUnknown()
        shadow.deleting() // how much went away is not known until the report
    }

    private fun enter() {
        // Explicit press: first confirm the composition (English or kana) as shown, the next press acts. The key reads 確定.
        if (!english.isEmpty) {
            commitEnglish()
            return
        }
        if (session.hasComposition) {
            confirmShown()
            return
        }
        val ic = currentInputConnection ?: return
        val action = EditorPolicy.editorAction(currentInputEditorInfo)
        if (action != null) {
            ic.performEditorAction(action)
            shadow.invalidate() // a search or a send: the field may well be cleared
        } else {
            sendKeyChar('\n')
            trackSelection(-1, -1) // the editor decides what Enter does; wait for its report
            ownReports.expectUnknown()
            shadow.newline()
        }
    }

    /**
     * Always one literal space for the current screen ([Keyboards.spaceFor]), never a candidate cycle or editor
     * action. A composition is first confirmed as shown; if that could not end it, nothing is typed over the span.
     * On the かな screen a composition never gets here: the key reads 変換 there.
     */
    private fun space() {
        commitEnglish()
        if (session.hasComposition) {
            confirmShown()
            if (session.hasComposition) return
        }
        currentInputConnection?.let { commitDirect(it, Keyboards.spaceFor(mode, session.isSensitive)) }
    }

    /**
     * ◀ ▶ ▲ ▼ (a tap, a flick in any of the four directions or a held repeat: all arrive here) with no segment selected.
     * While something of ours is composed they move the composing caret and never commit (0.1.34): an English composition
     * by entry ([englishArrow]), a kana composition by character of its reading ([kanaArrow]). ▲ and ▼ go to the start
     * and end of the composition: the keyboard has no line geometry of the editor, so it cannot tell where a line above
     * or below would be (fallback documented in reports/mixed-composition-caret-0.1.34.md). At either end the key does
     * nothing. With nothing composed the editor moves its own cursor as before.
     */
    private fun arrow(action: Int) {
        if (!english.isEmpty) {
            englishArrow(action)
            return
        }
        if (session.hasComposition && !session.isSensitive) {
            kanaArrow(action)
            return
        }
        val key = when (action) {
            Keyboards.CURSOR_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            Keyboards.CURSOR_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            Keyboards.CURSOR_UP -> KeyEvent.KEYCODE_DPAD_UP
            else -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        val vertical = action == Keyboards.CURSOR_UP || action == Keyboards.CURSOR_DOWN
        if (!vertical || EditorPolicy.allowsVerticalKeys(currentInputEditorInfo)) moveCursor(key)
    }

    /**
     * Cursor key with nothing composed: the editor moves its own cursor. No field text is read here and no offset is
     * computed, so surrogate pairs and committed text are left to the editor, as is the visual position of DPAD_UP/DOWN
     * on wrapped or multi-line text. DPAD_UP/DOWN only where the editor can use them ([EditorPolicy.allowsVerticalKeys]):
     * a single-line field has no line to go to, and the framework would move focus to a neighboring view instead.
     */
    private fun moveCursor(keyCode: Int) {
        worker.cancel()
        appliedToken = null
        composeStart = -1
        if (currentInputConnection == null) return
        sendDownUpKeyEvents(keyCode)
        trackSelection(-1, -1) // the editor decides where the cursor lands; wait for its report
        ownReports.expectUnknown()
        shadow.moving()
    }

    /**
     * Puts the editor's caret [offset] units into our composing span of [length] at [start], right after a composing
     * write (which left it at the span's end). Only offsets: nothing is read, nothing waits for the editor
     * (`setSelection` is one-way like every write). The report it causes is expected, so it is not taken for a tap or a
     * move away ([onUpdateSelection]). With the span's start unknown the editor's caret stays at the end; the caret
     * that typing uses is still ours.
     */
    private fun placeCaret(ic: InputConnection, start: Int, offset: Int, length: Int) {
        if (start < 0 || offset !in 0..length) return
        if (ic.setSelection(start + offset, start + offset)) {
            ownReports.expect(start + offset, start + offset, start, start + length)
            expectCursor(start + offset)
        }
    }

    /** An arrow in the English composition: its caret moves one entry (▲ ▼: to its start, its end). */
    private fun englishArrow(action: Int) {
        val moved = when (action) {
            Keyboards.CURSOR_LEFT -> english.moveLeft()
            Keyboards.CURSOR_RIGHT -> english.moveRight()
            Keyboards.CURSOR_UP -> english.moveToStart()
            else -> english.moveToEnd()
        }
        if (!moved) return
        currentInputConnection?.let { placeCaret(it, englishStart, english.caretOffset, english.shown.length) }
        requestEnglishLookups() // the words nearest the caret come first
        applyLayout(keepGesture = true) // the case key names the letter before the caret
        render()
    }

    /**
     * An arrow in a kana composition with no segment selected: the caret goes into what is shown, split into its
     * segments ([shownUnits]); from then on typing, ⌫ and 小゛゜ act there ([RepairSession.caretMode]). Before a result
     * has an alignment the reading is one kana segment. ▶ ▼ at the end do nothing.
     */
    private fun kanaArrow(action: Int) {
        if (!session.isActive || session.isSensitive || !session.hasComposition) return
        if (action == Keyboards.CURSOR_RIGHT || action == Keyboards.CURSOR_DOWN) return // already at the end
        val tail = session.composingText
        val units = if (tail.isEmpty() || appliedToken != null) shownUnits()
        else session.headUnits + RepairUnit(tail, tail, tail, emptyList(), emptyList())
        if (units.isEmpty()) return
        val r = RepairSession(units).also { it.caretMode = true }
        if (!(if (action == Keyboards.CURSOR_UP) r.caretToStart() else r.caretLeft())) return
        caretEntry = displayed
        collapse()
        worker.cancel()
        segmentsShown = false
        repair = r
        applyLayout(keepGesture = true) // a held ◀ keeps repeating
        afterRepairChange(request = true)
    }

    /**
     * An arrow with the caret in a kana composition. Back at the end with nothing edited or chosen, the composition is
     * as it was before the first arrow: the conversion it showed, typing continuing it.
     */
    private fun caretArrow(r: RepairSession, action: Int) {
        val revision = r.revision
        val moved = when (action) {
            Keyboards.CURSOR_LEFT -> r.caretLeft()
            Keyboards.CURSOR_RIGHT -> r.caretRight()
            Keyboards.CURSOR_UP -> r.caretToStart()
            else -> r.caretToEnd()
        }
        if (!moved) return
        if (r.caretAtEnd && !r.canUndo) {
            leaveCaret()
            return
        }
        // A new scope (another segment, or another boundary in this one) has no candidates yet.
        afterRepairChange(request = r.revision != revision)
    }

    /** Leaves an untouched caret: the session was never changed, so what it showed is shown again, caret at the end. */
    private fun leaveCaret() {
        abandonRepair()
        worker.cancel()
        currentInputConnection?.let { ic -> if (caretEntry.isNotEmpty()) setComposing(ic, caretEntry) }
        caretEntry = ""
        applyLayout(keepGesture = true)
        render()
        if (appliedToken == null) requestConversion() // the result the arrow cancelled
    }

    /** Ends any key or palette gesture in progress; nothing pending is emitted. */
    private fun clearGestures() {
        keyboard?.clearGesture()
        paletteGrid?.cancelGesture()
    }

    private fun switchIme() {
        clearGestures()
        val switched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToNextInputMethod(false)
        if (!switched) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun setMode(next: KeyboardMode) {
        // The composition is kept as-is across modes.
        val from = if (mode.isPalette) textMode else mode
        if (next == KeyboardMode.SYMBOLS && (from == KeyboardMode.KANA || from == KeyboardMode.ASCII)) numberReturn = from
        if (next.isPalette && next != mode) openPalette(next == KeyboardMode.EMOJI)
        mode = next
        if (!next.isPalette) textMode = next
        shift = Shift.OFF
        applyLayout()
        render() // the layout toggle depends on the mode
    }

    /** Strip button on the English and number screens: 12 keys ⇄ full rows. Only the layout choice is stored. */
    private fun toggleLayout() {
        when (mode) {
            KeyboardMode.ASCII -> LayoutSettings.setEnglish12Key(this, !LayoutSettings.english12Key(this))
            KeyboardMode.SYMBOLS -> LayoutSettings.setNumeric12Key(this, !LayoutSettings.numeric12Key(this))
            else -> return
        }
        applyLayout()
        render()
    }

    // --- Composition and candidates ----------------------------------------------------------

    /**
     * Commits over the selection, or over the composition when [from] is [composeStart]. False: the connection refused
     * it (no longer valid); nothing moved. True only means the editor was handed the text.
     */
    private fun commitDirect(ic: InputConnection, text: String, from: Int = selStart): Boolean {
        if (!ic.commitText(text, 1)) {
            shadow.invalidate()
            pendingEntries.clear()
            return false
        }
        expectCursor(if (from < 0) -1 else from + text.length)
        wroteCommit(from, text.length)
        shadow.committed(text, from)
        recordEntries(text)
        return true
    }

    /** Removes the emptied composing span that started at [from]. */
    private fun clearSpan(ic: InputConnection, from: Int) {
        pendingEntries.clear() // whatever was inserted was deleted again
        if (ic.commitText("", 1)) {
            wroteCommit(from, 0)
            shadow.committed("", from)
        } else {
            shadow.invalidate()
        }
        expectCursor(from)
    }

    private fun wroteCommit(from: Int, length: Int) {
        commits++
        if (from < 0) ownReports.expectUnknown() else ownReports.expect(from + length, from + length)
    }

    /** A composing write of [length] units at [from] was accepted. */
    private fun wroteComposing(from: Int, length: Int) {
        if (from < 0) ownReports.expectUnknown() else ownReports.expect(from + length, from + length, from, from + length)
        shadow.composing(from, length)
    }

    /**
     * Preview tap: inserts the clip as it is now (never the preview, never a remembered copy) exactly once, as plain
     * text, after our own composition is confirmed, and hides the preview only if the commit was accepted. With no
     * usable clip left nothing at all happens, and the preview catches up. A second tap finds the clip used. Goes
     * straight to the editor: it is not typed text, so it is never learned or composed.
     */
    private fun pasteClipboard() {
        if (!session.isActive || session.isSensitive) return
        val ic = currentInputConnection ?: return
        val clip = clipboard.current()?.takeIf { ClipPolicy.preview(it.text) != null }
        if (clip == null) {
            clipboard.refresh()
            return
        }
        commitEnglish()
        if (session.hasComposition) {
            confirmShown()
            if (session.hasComposition) return
        }
        worker.cancel()
        appliedToken = null
        composeStart = -1
        if (commitDirect(ic, clip.text.toString())) clipboard.markPasted(clip)
        shadow.forget() // a paste is not typed text, and context must not run across it
        render()
    }

    /**
     * What ranking may see: the settings screen has a try box, so its switch can change with this composition open. The
     * field's answer for this composition while nothing has been committed, forgotten or restarted since; otherwise the
     * shadow, which is empty whenever it is not sure.
     */
    private fun rankingContext(): String {
        if (!DebugSettings.committedContext(this) || !worker.judges) return ""
        val a = asked
        val current = a != null && a.fieldStart == fieldStarts && a.commits == commits && a.generation == shadow.generation
        return if (current) a!!.text else shadow.text
    }

    /**
     * The first key of a composition, before anything of it is written: asks the field for what precedes the cursor
     * ([HostContext], never on this thread; waits at most 100 ms). Nothing of ours is composing then: the first composing
     * write goes out only after the answer or the cutoff, and an answer that misses the cutoff is dropped. That our earlier
     * commits are in the answer rests on the framework, not on a documented InputConnection contract: oneway calls on one
     * binder keep their order (IBinder docs) and the editor's side runs them on one handler (AOSP), see
     * reports/context-read-0.1.30.md. A selection is not asked for: BaseInputConnection answers what precedes its start,
     * which the composition then replaces; other editors may differ.
     *
     * Not asked where context is not allowed ([EditorPolicy.allowsCommittedContext]), with the switch off, without the
     * judge, while [ownSpan] (an English word of ours) is in the field, or in an editor of this process: there the call
     * is not a one-way IPC, and the editor runs on the very thread that waits. Not answered in time: the shadow, or nothing.
     */
    private fun askField(ic: InputConnection, ownSpan: Boolean) {
        asked = null
        if (ownSpan || ownProcess || !contextAllowed || session.isSensitive || !hostReads()) return
        if (!DebugSettings.committedContext(this) || !worker.isReady || !worker.judges) return
        val answer = hostContext.ask { ic.getTextBeforeCursor(HostContext.READ_UNITS, 0) }
        if (answer.outcome == HostContext.Outcome.TEXT) asked = Asked(answer.text, fieldStarts, commits, shadow.generation)
    }

    /** Internal for tests: whether the field may be asked at all. Tests of the shadow alone turn it off. */
    internal open fun hostReads(): Boolean = true

    /** After any session edit: invalidate old work, mirror composition, clear candidates. */
    private fun afterEdit(ic: InputConnection) {
        worker.cancel()
        appliedToken = null
        shownIndex = -1
        segmentsShown = false // segments are for what was shown before this edit
        collapse()
        val text = session.composingText
        val head = session.headSurface
        if (!session.hasComposition) {
            clearSpan(ic, composeStart)
            displayed = ""
            lastShownRaw = ""
        } else {
            // While typing extends the last converted reading, keep that conversion and append the new kana
            // until the next result arrives, so the composition does not flicker back to kana.
            val extends = live && lastShownRaw.isNotEmpty() && text.length > lastShownRaw.length && text.startsWith(lastShownRaw)
            setComposing(ic, head + (if (extends) lastShownSurface + text.substring(lastShownRaw.length) else text))
            if (!extends) lastShownRaw = ""
        }
        render()
        requestConversion()
    }

    private fun requestConversion() {
        if (!session.isActive || session.isSensitive || session.composingText.isEmpty()) return
        val token = session.request() ?: return
        if (worker.isReady) {
            val committed = rankingContext()
            val generation = shadow.generation
            val head = session.headSurface
            worker.submit(token, if (personalized) learning.snapshot() else null, committed + head, committed.isNotEmpty(), head) { t, result, words ->
                // Ranked with text forgotten since: not shown, and the same reading runs again without it.
                if (generation == shadow.generation) onResult(t, result, words) else requestConversion()
            }
        } else {
            onResult(token, listOf(Candidate(token.text, token.text, 0.0)), listOf(emptyList()))
        }
    }

    private fun onResult(token: SessionToken, result: List<Candidate>, words: List<List<Word>>) {
        if (repair != null) return // a selected segment owns the composing span; the session result stays unused
        if (session.apply(token, result)) {
            appliedToken = token
            appliedWords = words
            shownIndex = -1
            if (live) show(0)
            render()
        }
    }

    /** Live conversion: shows candidate [index] after any repaired head. Nothing is committed. */
    private fun show(index: Int) {
        val ic = currentInputConnection ?: return
        val surface = session.candidates.getOrNull(index)?.surface ?: return
        shownIndex = index
        lastShownRaw = session.composingText
        lastShownSurface = surface
        setComposing(ic, session.headSurface + surface)
    }

    private fun setComposing(ic: InputConnection, text: CharSequence) {
        if (ic.setComposingText(text, 1)) wroteComposing(composeStart, text.length) else shadow.invalidate()
        displayed = text.toString()
        // Every write leaves the caret at the span's end and must be reported before a tap is trusted again.
        latestConfirmed = false
        if (displayed.isNotEmpty() && !unconfirmedOverflow) {
            if (unconfirmed.size < MAX_UNCONFIRMED) {
                unconfirmed += displayed
            } else {
                unconfirmedOverflow = true
                unconfirmed.clear()
            }
        }
        expectCursor(if (composeStart < 0) -1 else composeStart + text.length)
    }

    /**
     * 変換, live conversion on or off: the next candidate (wrapping, raw included) replaces the shown one, and the
     * segment buttons of what is shown appear. Nothing is committed. With only kept segments composed it selects the
     * last one. No-op without a composition or while results are pending.
     */
    private fun convertNext() {
        if (session.composingText.isEmpty()) {
            if (session.hasComposition) enterRepair()
            return
        }
        val count = session.candidates.size
        if (appliedToken == null || count == 0) return
        segmentsShown = true
        val head = session.headSurface
        var next = (shownIndex + 1) % count
        // Skip a candidate identical to what is already shown (e.g. the raw kana with live off).
        if (count > 1 && head + session.candidates[next].surface == displayed) next = (next + 1) % count
        show(next)
        render()
    }

    /**
     * Puts the typed kana back into the composing span before it is finished without a choice. In repair mode
     * this is the repair's current reading, edits included (call before [abandonRepair]); nothing is learned.
     */
    private fun restoreRawDisplay() {
        val raw = repair?.typed ?: session.typedText
        if (raw.isNotEmpty() && displayed != raw) currentInputConnection?.setComposingText(raw, 1)
        shownIndex = -1
        displayed = ""
        lastShownRaw = ""
    }

    /**
     * Enter/Space: commits what the composition shows — the candidate shown by live
     * conversion or by 変換 (also with live off), otherwise the kana, after any repaired head.
     */
    private fun confirmShown() {
        val token = appliedToken
        if (token != null && shownIndex >= 0) choose(token, shownIndex)
        else confirmRaw(displayed.takeIf { it.isNotEmpty() })
    }

    // A pending request can still show a converted prefix + new kana. Explicit confirmation
    // preserves that visible surface; the raw chip calls this without an override.
    private fun confirmRaw(visibleSurface: String? = null) {
        val ic = currentInputConnection ?: return
        val typed = session.typedText
        val head = session.headUnits
        val pureHead = session.composingText.isEmpty()
        val text = session.commitRaw() ?: return
        val committed = visibleSurface ?: text
        // Only a composition made entirely of repaired units is learned here: a raw or pending tail has no
        // reliable word alignment, and plain kana commits were never learned.
        if (head.isNotEmpty() && pureHead && committed == text) learnCommit(typed, committed, head, emptyList(), emptyList(), chosenInSegments(head))
        worker.cancel()
        appliedToken = null
        shownIndex = -1
        displayed = ""
        lastShownRaw = ""
        collapse()
        commitDirect(ic, committed, composeStart)
        render()
    }

    private fun choose(token: SessionToken, index: Int) {
        val chosen = session.candidates.getOrNull(index)
        finishChoice(token, chosen, appliedWords.getOrNull(index).orEmpty()) { session.choose(token, index) }
    }

    /** Commits [choice] through [commit] (null: stale, nothing happens) and records it as an explicit choice. */
    private fun finishChoice(token: SessionToken, chosen: Candidate?, chosenWords: List<Word>, commit: () -> String?) {
        val ic = currentInputConnection ?: return
        if (token !== appliedToken) return
        val defaultWords = appliedWords.firstOrNull().orEmpty()
        val head = session.headUnits
        val headTyped = session.headTyped
        // Explicit: something other than what was ranked first (by 変換, a tap or the panel), or a segment's choice.
        // Taking the first candidate, by 確定 or by tapping it, only accepts the ranking.
        val explicit = chosen != null && (chosen.surface != session.candidates.firstOrNull()?.surface || chosenInSegments(head))
        val text = commit() ?: return
        if (chosen != null) {
            // Predictions carry the longer reading they complete; everything else is learned for what was typed.
            val prediction = CandidateGroups.classify(token.text, chosen) == CandidateClass.PREDICTION
            learnCommit(headTyped + if (prediction) chosen.reading else token.text, text, head, chosenWords, defaultWords, explicit)
        }
        appliedToken = null
        shownIndex = -1
        displayed = ""
        lastShownRaw = ""
        collapse()
        worker.cancel()
        commitDirect(ic, text, composeStart)
        render()
    }

    /** Word by word from its alignment, so a late exact word is learned like any other dictionary word. */
    private fun wordsOf(c: Candidate): List<Word> =
        c.segments.orEmpty().filter { it.kind == SegmentKind.WORD }.map { Word(it.reading, it.surface) }

    /**
     * Tap in the expanded panel. Candidates of the ranked list go the usual way; the others (late exact words,
     * completions) are outside the session's capped list and carry their own alignment. In repair mode the focused
     * unit takes the choice and is locked; nothing commits there. The panel closes on every edit, so [list] is current.
     */
    private fun pickFromPanel(list: List<Candidate>, index: Int) {
        if (!expanded) return
        val c = list.getOrNull(index) ?: return
        // English: drawn for the composition (the panel closes on every change of it), committed like a strip chip.
        if (!english.isEmpty) {
            if (c.reading == english.shown) commitEnglish(c.surface)
            return
        }
        val r = repair
        if (r != null) {
            if (r.choose(c)) {
                collapse()
                afterRepairChange(request = r.hasUnconverted)
            }
            return
        }
        val token = appliedToken ?: return
        if (c.surface == token.text) {
            confirmRaw()
            return
        }
        val listed = session.candidates.indexOfFirst { it.surface == c.surface }
        if (listed >= 0) choose(token, listed)
        else finishChoice(token, c, wordsOf(c)) { session.choose(token, c) }
    }

    /**
     * Records an explicit final commit (never a preview, an intermediate repair choice or a cancelled repair).
     * Repaired [head] units add their current words; their pre-repair words count as defaults.
     */
    private fun learnCommit(reading: String, surface: String, head: List<RepairUnit>, words: List<Word>, defaults: List<Word>, explicit: Boolean) {
        if (!personalized) return
        fun wordsOf(segments: List<Segment>) = segments.filter { it.kind == SegmentKind.WORD }.map { Word(it.reading, it.surface) }
        learning.learn(reading, surface, head.flatMap { wordsOf(it.segments) } + words, head.flatMap { wordsOf(it.defaults) } + defaults, explicit)
    }

    /**
     * Whether a segment carries the user's own choice: chosen in segment selection (locked) and showing a word the default
     * conversion did not have, or no word alignment at all (katakana, kana). Selecting, cycling, resizing or editing alone is not.
     */
    private fun chosenInSegments(head: List<RepairUnit>): Boolean = head.any { u ->
        val defaults = u.defaults.filter { it.kind == SegmentKind.WORD }.map { it.reading to it.surface }.toSet()
        val words = u.segments.filter { it.kind == SegmentKind.WORD }
        u.locked && (words.isEmpty() || words.any { (it.reading to it.surface) !in defaults })
    }

    /**
     * Ends composition without moving or committing text at a new cursor position. With [spanKept] false the editor
     * reported no composing span, so nothing is written (the kana would be inserted at the new cursor).
     */
    private fun dropComposition(spanKept: Boolean = true) {
        pendingEntries.clear() // left as typed, never committed by the user
        finishEnglish(redraw = false)
        worker.cancel()
        appliedToken = null
        collapse()
        if (session.hasComposition) {
            if (spanKept) restoreRawDisplay()
            currentInputConnection?.finishComposingText()
        }
        abandonRepair()
        shownIndex = -1
        displayed = ""
        lastShownRaw = ""
        session.onSelectionMoved()
        shadow.invalidate()
        render()
    }

    // --- English words -----------------------------------------------------------------------

    private fun typeEnglish(ic: InputConnection, letter: Char) {
        if (english.isEmpty) englishStart = selStart // the word replaces any selection, starting at its first offset
        english.type(letter)
        englishChanged(ic)
    }

    /** Not a letter, typed into (or starting) the English composition: kept as typed after it, nothing committed. */
    private fun insertEnglish(ic: InputConnection, text: String) {
        if (english.isEmpty) englishStart = selStart // as for a first letter: replaces any selection
        english.insert(text)
        englishChanged(ic)
    }

    /** The composition changed: show it, drop what the panel showed and look its words up off the main thread. */
    private fun englishChanged(ic: InputConnection) {
        englishGen++
        englishResultKey = ""
        collapse()
        writeEnglish(ic)
        requestEnglishLookups()
        applyLayout(keepGesture = true)
        render()
    }

    /**
     * Looks up the composition's words that have no result yet, nearest the caret first, in one job ([EnglishLookup]).
     * A result is the index's answer for a word, the same for every composition, so one already held is not asked again.
     */
    private fun requestEnglishLookups() {
        val needed = WholeCandidates.lookups(english)
        val missing = needed.filter { it !in englishResults }
        if (missing.isEmpty()) englishResultKey = needed.joinToString(",")
        else englishLookup.lookup(missing, englishGen, ::onEnglishResult)
    }

    /** The case key changed one letter: the same lookup results apply (keys are case-insensitive); nothing is committed. */
    private fun showEnglish() {
        currentInputConnection?.let { writeEnglish(it) }
        applyLayout(keepGesture = true)
        render()
    }

    /** Writes the composition, then puts the editor's caret where ours is ([placeCaret]) unless that is the end. */
    private fun writeEnglish(ic: InputConnection) {
        val shown = english.shown
        if (ic.setComposingText(shown, 1)) wroteComposing(englishStart, shown.length) else shadow.invalidate()
        expectCursor(if (englishStart < 0) -1 else englishStart + shown.length)
        if (!english.caretAtEnd) placeCaret(ic, englishStart, english.caretOffset, shown.length)
    }

    private fun onEnglishResult(gen: Long, results: Map<String, dev.astra.ime.oss.EnglishLexicon.Result>) {
        // Another edit, a commit, a drop or a new editor since the request: the result is stale (an edit asks again).
        if (gen != englishGen || english.isEmpty) return
        englishResults.putAll(results)
        val needed = WholeCandidates.lookups(english)
        if (needed.all { it in englishResults }) englishResultKey = needed.joinToString(",")
        render()
    }

    /**
     * Candidates for the whole composition, its source text first ([WholeCandidates]). Built once per state of the
     * composition (text, caret, lookups held, field), not per render: the strip and the panel share it.
     */
    private fun englishCandidates(): WholeCandidates.Result {
        val key = listOf(englishGen, english.shown, english.caret, englishResults.size, composesDigits, worker.isReady)
        englishCandidateCache?.let { (k, v) -> if (k == key) return v }
        return WholeCandidates.of(
            english, { englishResults[it] },
            { worker.symbolAlternatives(it, numbers = composesDigits) },
            widths = composesDigits,
        ).also { englishCandidateCache = key to it }
    }

    private var englishCandidateCache: Pair<List<Any>, WholeCandidates.Result>? = null

    /**
     * Commits [text] (default: the composition as shown) over the English composition, exactly once. Enter, Space
     * and a paste call this; a candidate commits exactly itself (the whole composition in another form), without a space.
     */
    private fun commitEnglish(text: String? = null) {
        if (english.isEmpty) return
        val committed = text ?: english.shown
        val start = englishStart
        resetEnglish()
        collapse()
        currentInputConnection?.let { commitDirect(it, committed, start) }
        applyLayout(keepGesture = true)
        render()
    }

    /** Ends the English composition leaving the shown letters in the field (cursor moved, keyboard hidden, ...). */
    private fun finishEnglish(redraw: Boolean = true) {
        if (english.isEmpty) return
        pendingEntries.clear()
        resetEnglish()
        currentInputConnection?.finishComposingText()
        if (redraw) {
            applyLayout(keepGesture = true)
            render()
        }
    }

    /** Forgets the English word without touching the editor. */
    private fun resetEnglish() {
        english.clear()
        englishGen++
        englishStart = -1
        englishResults.clear()
        englishResultKey = ""
    }

    /**
     * Editor report while an English word is composed. Our own composing writes leave a collapsed cursor at the end
     * of a span starting at [englishStart]. A [late] report of one of our earlier writes is ignored. Anything else
     * means the cursor or the text moved: the composition ends with the shown letters left in place (never rewritten,
     * never committed as another candidate).
     */
    private fun englishSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int, late: Boolean, ownCaret: Boolean) {
        val collapsed = newSelStart == newSelEnd
        // ownCaret: the caret where placeCaret put it, inside our span.
        val own = candidatesStart >= 0 && (englishStart < 0 || candidatesStart == englishStart) && collapsed &&
            (newSelEnd == candidatesEnd || ownCaret)
        if (own) englishStart = candidatesStart else if (!late) finishEnglish()
    }

    // --- Segments (文節) ------------------------------------------------------------------------

    private fun canRepair(): Boolean =
        repair == null && session.isActive && !session.isSensitive && session.hasComposition &&
            (session.composingText.isEmpty() || appliedToken != null)

    /**
     * What the composition shows, split into segments (units aligned with the typed kana): repaired head units, then
     * the tail by the shown candidate's alignment (the first candidate's boundaries over the kana with live off).
     * Without a reliable alignment the tail is one segment.
     */
    private fun shownUnits(): List<RepairUnit> {
        val tail = session.composingText
        val candidates = session.candidates
        val defaults = candidates.firstOrNull()?.let { Alignment.checked(tail, it.surface, it.segments) }.orEmpty()
        val tailUnits = when {
            tail.isEmpty() -> emptyList()
            shownIndex >= 0 -> Alignment.units(tail, candidates.getOrNull(shownIndex), defaults)
            else -> Alignment.units(tail, candidates.firstOrNull(), defaults, kana = true)
        }
        return session.headUnits + tailUnits
    }

    /**
     * Selects a segment: [unit] (a segment button), the one under surface offset [at] (a tap in the composition; only
     * if the segments spell exactly what the span shows), else the last one. An open panel stays open for it.
     */
    private fun enterRepair(at: Int = -1, unit: Int = -1) {
        if (!canRepair()) return
        val units = shownUnits()
        if (units.isEmpty()) return
        val focus = when {
            unit >= 0 -> unit.takeIf { it in units.indices } ?: return
            at < 0 -> units.lastIndex
            units.joinToString("") { it.surface } != displayed -> return
            else -> Alignment.unitAt(units, at) ?: return
        }
        val keepPanel = expanded
        collapse()
        worker.cancel()
        segmentsShown = false
        repair = RepairSession(units, focus).also { if (at >= 0) it.place(focus, caretFor(units, focus, at)) }
        clearGestures()
        applyLayout()
        afterRepairChange(request = true)
        if (keepPanel) toggleExpanded()
    }

    /**
     * Keys while a segment is selected. ◀ ▶ shrink or extend it; 変換 cycles its candidates. Any other key keeps every
     * segment as shown ([finishRepair]) and then does what it does to any composition (false: the normal handler runs).
     */
    private fun repairAction(action: Int): Boolean {
        val r = repair ?: return false
        if (r.caretMode) when (action) {
            // The arrows move the composing caret (0.1.34); a selected segment's arrows resize it, as before.
            Keyboards.CURSOR_LEFT, Keyboards.CURSOR_RIGHT, Keyboards.CURSOR_UP, Keyboards.CURSOR_DOWN -> {
                caretArrow(r, action)
                return true
            }
            // Another screen or the case key keeps the caret where it is: what is typed there goes in at it.
            Keyboards.TO_KANA, Keyboards.TO_ASCII, Keyboards.TO_SYMBOLS, Keyboards.TO_JSYMBOLS, Keyboards.TO_EMOJI,
            Keyboards.TO_TEXT, Keyboards.MODE_CYCLE, Keyboards.SHIFT -> return false
        }
        when (action) {
            Keyboards.CURSOR_LEFT -> if (r.resize(extend = false)) afterRepairChange(request = true)
            Keyboards.CURSOR_RIGHT -> if (r.resize(extend = true)) afterRepairChange(request = true)
            // Moving to another line would have to commit; a selected segment commits only with 確定.
            Keyboards.CURSOR_UP, Keyboards.CURSOR_DOWN -> Unit
            Keyboards.CONVERT -> if (r.next()) afterRepairChange(request = r.hasUnconverted)
            // ⌫ edits the selected segment's kana before its caret, never another segment or committed text.
            Keyboards.BACKSPACE -> if (r.deleteBackward()) {
                if (r.typed.isEmpty()) finishRepair() else afterRepairChange(request = true)
            }
            Keyboards.SPACE -> if (mode == KeyboardMode.KANA) {
                if (r.next()) afterRepairChange(request = r.hasUnconverted)
            } else {
                finishRepair()
                return false
            }
            Keyboards.ENTER -> {
                finishRepair()
                if (session.hasComposition) confirmShown()
            }
            else -> {
                finishRepair()
                return false
            }
        }
        return true
    }

    /**
     * Mirrors the selected segment into the composing span (highlighted) and the strip. A new segment or reading needs
     * new candidates, and an open panel looks them up for the segment now selected.
     */
    private fun afterRepairChange(request: Boolean) {
        val r = repair ?: return
        currentInputConnection?.let { ic ->
            // With the composing caret, a word holding it shows its kana (RepairSession.shownUnits); otherwise r.surface.
            val text = SpannableString(r.shownSurface)
            val range = r.shownFocusRange
            if (!range.isEmpty()) text.setSpan(BackgroundColorSpan(SHOWN_BG), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setComposing(ic, text)
            if (r.caretMode) placeCaret(ic, composeStart, r.shownCaret, text.length)
        }
        if (request && expanded) requestPanel(r.scopeTyped)
        render()
        if (request) requestRepairCandidates()
    }

    private fun requestRepairCandidates() {
        val r = repair ?: return
        worker.cancel()
        val request = r.request() ?: return
        requestSegmentLookups(request.text)
        val learned = if (personalized) learning.snapshot() else null
        val committed = rankingContext()
        val left = committed + request.context
        val external = committed.isNotEmpty()
        val generation = shadow.generation
        when {
            !worker.isReady -> onRepairResult(r, request, listOf(Candidate(request.text, request.text, 0.0)), emptyList())
            // After a resize the other changed segment converts in the same job.
            request.neighbor >= 0 -> worker.submitParts(listOf(request.text, request.neighborText), learned, left, external) { lists ->
                if (generation == shadow.generation) onRepairResult(r, request, lists[0], lists[1]) else requestRepairCandidates()
            }
            else -> worker.submitText(request.text, learned, left, arrange = false, external = external) { result, _ ->
                if (generation == shadow.generation) onRepairResult(r, request, result, emptyList()) else requestRepairCandidates()
            }
        }
    }

    private fun onRepairResult(r: RepairSession, request: RepairRequest, result: List<Candidate>, neighbor: List<Candidate>) {
        // A result for an earlier selection, segment or revision is rejected by RepairSession.apply.
        if (repair === r && r.apply(request, result, preview = live, neighborResult = neighbor)) {
            supplementSegment(r)
            afterRepairChange(request = false)
        }
    }

    /**
     * An English part as the scope (m-1 of m-1グランプリ): its words go to the English index, off the main thread, as for
     * an English composition. A word already answered is not asked again.
     */
    private fun requestSegmentLookups(scope: String) {
        val word = MixedSegment.word(scope) ?: return
        val missing = WholeCandidates.lookups(word).filter { it !in segmentResults }
        if (missing.isNotEmpty()) englishLookup.lookup(missing, ++segmentGen, ::onSegmentLookup)
    }

    private fun onSegmentLookup(gen: Long, results: Map<String, dev.astra.ime.oss.EnglishLexicon.Result>) {
        // Asked before the selection ended or a newer scope asked: a later lookup covers what is still missing.
        if (gen != segmentGen) return
        val r = repair ?: return
        segmentResults.putAll(results)
        if (supplementSegment(r)) render()
    }

    /**
     * Adds the scope's English forms ([MixedSegment]) after the converter's candidates, once those are for the scope and
     * the index has answered for its words (so the forms come in [WholeCandidates]' order, not by arrival). The same forms
     * whatever the screen; widths and number forms only where an English composition gets them ([composesDigits]).
     */
    private fun supplementSegment(r: RepairSession): Boolean {
        val scope = r.scopeTyped
        val word = MixedSegment.word(scope) ?: return false
        if (WholeCandidates.lookups(word).any { it !in segmentResults }) return false
        val forms = MixedSegment.candidates(scope, { segmentResults[it] }, { worker.symbolAlternatives(it, numbers = composesDigits) }, composesDigits)
        return r.supplement(scope, forms)
    }

    /** Keeps every segment as shown as the fixed head of the composition; typing continues after them. */
    private fun finishRepair() {
        val r = repair ?: return
        abandonRepair()
        worker.cancel()
        session.replaceComposition(r.finish())
        appliedToken = null
        shownIndex = -1
        lastShownRaw = ""
        currentInputConnection?.let { ic ->
            if (session.hasComposition) {
                setComposing(ic, session.headSurface)
            } else {
                clearSpan(ic, composeStart) // every unit's reading was deleted
                displayed = ""
            }
        }
        applyLayout()
        render()
    }

    /** Drops segment state without touching the editor (callers restore or finish the composing span). */
    private fun abandonRepair() {
        segmentsShown = false
        segmentGen++
        segmentResults.clear()
        if (repair == null) return
        collapse()
        repair = null
    }


    // --- Views -------------------------------------------------------------------------------

    /** Applies the stored theme to what no [render] redraws; chips and tabs take [colors] when they are built. */
    private fun applyTheme() {
        colors = ThemeSettings.theme(this).colors
        inputRoot?.setBackgroundColor(colors.background)
        panel?.setBackgroundColor(colors.background)
        panelTop?.colors = colors
        panelExact?.colors = colors
        for (header in listOf(panelTopHeader, panelExactHeader)) {
            if (header == null) continue
            header.background = RippleDrawable(ColorStateList.valueOf(colors.ripple), null, ColorDrawable(Color.WHITE))
            (header.getChildAt(0) as TextView).setTextColor(colors.hint)
            (header.getChildAt(1) as TextView).setTextColor(colors.primary)
        }
        paletteBox?.setBackgroundColor(colors.background)
        for (button in listOf(expandButton, layoutButton)) button?.setTextColor(colors.primary)
        keyboard?.colors = colors
        paletteGrid?.colors = colors
        styleNavigationBar()
    }

    /** Older versions draw this color behind the buttons; edge-to-edge ones show the root's background there. */
    @Suppress("DEPRECATION")
    private fun styleNavigationBar() {
        val w = window?.window ?: return
        w.navigationBarColor = colors.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (colors.lightNavigation) light else 0, light)
        } else {
            val decor = w.decorView
            val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decor.systemUiVisibility = if (colors.lightNavigation) decor.systemUiVisibility or flag else decor.systemUiVisibility and flag.inv()
        }
    }

    /** What the layout's labels depend on: kana composing (変換), anything of ours composing (確定). */
    private fun composingState(): Int =
        (if (session.hasComposition || repair != null) 1 else 0) + (if (!english.isEmpty) 2 else 0)

    private fun layoutRows(): List<List<Key>> {
        layoutState = composingState()
        val back = when (textMode) {
            KeyboardMode.ASCII -> "abc"
            KeyboardMode.SYMBOLS -> "123"
            else -> "あ"
        }
        // On every screen Enter reads 確定 while something of ours is composed: it then only confirms.
        val enter = if (layoutState != 0) Keyboards.CONFIRM else enterLabel
        // While composing English the case key names what it does to the last letter (caps lock keeps its own label).
        val wordCase = if (english.isEmpty || shift == Shift.LOCK) null else english.caseLabel()
        return when (mode) {
            KeyboardMode.KANA -> Keyboards.kana(enter, composing = layoutState and 1 != 0)
            KeyboardMode.ASCII ->
                if (LayoutSettings.english12Key(this)) Keyboards.english12(shift, enter, wordCase)
                else Keyboards.letters(shift, enter, wordCase)
            KeyboardMode.SYMBOLS ->
                if (LayoutSettings.numeric12Key(this)) Keyboards.numeric12(enter, numberReturn)
                else Keyboards.symbols(enter, numberReturn)
            KeyboardMode.JAPANESE_SYMBOLS -> Keyboards.paletteBar(back, emoji = false, enter = enter)
            KeyboardMode.EMOJI -> Keyboards.paletteBar(back, emoji = true, enter = enter)
        }
    }

    private fun applyLayout(keepGesture: Boolean = false) {
        val kb = keyboard ?: return
        val rows = layoutRows()
        // While typing a word only the shift label changes, so a held key (⌫ repeat) keeps going.
        if (keepGesture) kb.updateRows(rows) else kb.setRows(rows)
        kb.collectSamples = session.isActive && !session.isSensitive && fieldLearning
        kb.showPreview = !password
        showPalette()
    }

    /** Shows the palette for the current mode (tabs + entries of the selected category), or hides it. */
    private fun showPalette() {
        val box = paletteBox ?: return
        val grid = paletteGrid ?: return
        val tabs = paletteTabs ?: return
        if (!mode.isPalette) {
            if (box.visibility != View.GONE) {
                box.visibility = View.GONE
                grid.setItems(emptyList())
                tabs.removeAllViews()
            }
            return
        }
        val emoji = mode == KeyboardMode.EMOJI
        val categories = if (emoji) Palettes.emoji else Palettes.symbols
        // 履歴 exists only where history is recorded and used: not with learning off, not in private or password fields.
        val history = personalized
        val stored = if (emoji) emojiCategory else symbolCategory
        val selected = if (stored == HISTORY_TAB && history) HISTORY_TAB else stored.coerceIn(categories.indices)
        tabs.removeAllViews()
        val d = resources.displayMetrics.density
        val labels = (if (history) listOf(HISTORY_TAB to Palettes.HISTORY_LABEL) else emptyList()) + categories.mapIndexed { i, c -> i to c.label }
        for ((i, label) in labels) {
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = (48 * d).toInt()
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                setTextColor(if (i == selected) colors.onShown else colors.tabText)
                isSelected = i == selected
                if (i == selected) setBackgroundColor(colors.shown)
                setOnClickListener { selectCategory(emoji, i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        grid.textSizeSp = if (emoji) 26f else 22f
        if (selected == HISTORY_TAB) {
            grid.emptyLabel = getString(R.string.palette_history_empty)
            grid.setItems(shownRecent)
        } else {
            val category = categories[selected]
            grid.emptyLabel = ""
            grid.setItems(renderable.getOrPut(category) { android.graphics.Paint().let { p -> Palettes.renderable(category.items) { p.hasGlyph(it) } } })
        }
        box.visibility = if (expanded) View.GONE else View.VISIBLE
    }

    private fun selectCategory(emoji: Boolean, index: Int) {
        if (mode != (if (emoji) KeyboardMode.EMOJI else KeyboardMode.JAPANESE_SYMBOLS)) return
        if (index == HISTORY_TAB) shownRecent = recentFor(emoji)
        if (emoji) emojiCategory = index else symbolCategory = index
        showPalette()
    }

    /** The 履歴 to show now, drawable entries only; empty where history is not used. */
    private fun recentFor(emoji: Boolean): List<String> {
        if (!personalized) return emptyList()
        val recent = learning.recent(emoji)
        if (recent.isEmpty()) return recent
        return android.graphics.Paint().let { p -> Palettes.renderable(recent) { p.hasGlyph(it) } }
    }

    /** A palette opens on its 履歴 when that has entries; otherwise on the category last shown (the first after an empty 履歴). */
    private fun openPalette(emoji: Boolean) {
        shownRecent = recentFor(emoji)
        val stored = if (emoji) emojiCategory else symbolCategory
        val index = if (shownRecent.isNotEmpty()) HISTORY_TAB else if (stored == HISTORY_TAB) 0 else stored
        if (emoji) emojiCategory = index else symbolCategory = index
    }

    private fun render() {
        // 空白/変換 and Enter/確定 labels follow the composition without interrupting a held key (e.g. ⌫ repeat).
        if (layoutState != composingState()) keyboard?.updateRows(layoutRows())
        val row = strip ?: return
        renderStrip(row)
        // Hidden only when nothing in it acts: candidates, segments, ▼ and the layout toggle each keep it on screen.
        val needed = row.childCount > 0 || layoutButton?.visibility == View.VISIBLE || !expandButton?.text.isNullOrEmpty()
        stripBar?.visibility = if (needed) View.VISIBLE else View.GONE
    }

    private fun renderStrip(row: LinearLayout) {
        row.removeAllViews()
        stripScroll?.scrollTo(0, 0)
        layoutButton?.let { b ->
            // English/number screens, while nothing is composed: the explicit 12 keys ⇄ full rows switch.
            val twelveKey = when (mode) {
                KeyboardMode.ASCII -> LayoutSettings.english12Key(this)
                KeyboardMode.SYMBOLS -> LayoutSettings.numeric12Key(this)
                else -> null
            }
            val show = twelveKey != null && repair == null && !session.hasComposition && english.isEmpty
            b.visibility = if (show) View.VISIBLE else View.GONE
            if (twelveKey != null) b.text = getString(if (twelveKey) R.string.layout_to_full else R.string.layout_to_12key)
        }
        val r = repair
        if (r != null) {
            expandButton?.text = if (expanded) "▲" else "▼"
            renderRepair(r, row)
            return
        }
        if (!english.isEmpty) {
            renderEnglish(row)
            return
        }
        val raw = session.composingText
        val token = appliedToken
        expandButton?.text = when {
            expanded -> "▲"
            token != null && session.candidates.size > 1 -> "▼"
            else -> ""
        }
        if (!session.hasComposition) {
            clipboard.preview?.let { row.addView(clipChip(it)) }
            return
        }
        val editor = session.editorId
        val revision = session.revision
        val fresh = { session.isActive && session.editorId == editor && session.revision == revision }
        if (raw.isEmpty()) {
            // Only kept segments are composed: their buttons select one again; the last chip commits them as shown.
            renderSegments(row, session.headUnits, -1) { i -> if (fresh()) enterRepair(unit = i) }
            row.addView(chip(session.headSurface, primary = true, shown = false, wrap = false) { if (fresh()) confirmRaw() })
            return
        }
        // Once a conversion is shown (live conversion or 変換), or after ▼, the segment buttons replace the raw chip
        // (the raw kana stays among the candidates). Users found needing ▼ first to reach them a bug.
        val segments = token != null && (segmentsShown || shownIndex >= 0)
        if (segments) {
            renderSegments(row, shownUnits(), -1) { i -> if (fresh()) enterRepair(unit = i) }
        } else {
            row.addView(chip(raw, primary = true, shown = false, wrap = false) { if (fresh()) confirmRaw() })
        }
        if (token == null) {
            if (worker.isReady) row.addView(status(getString(R.string.status_converting)))
            return
        }
        // Open, the panel below shows all of it (one group enlarged or both); the strip keeps the segments and ▲.
        if (expanded) {
            renderPanel(raw, session.candidates, session.candidates.getOrNull(shownIndex)?.surface)
            return
        }
        session.candidates.forEachIndexed { i, c ->
            if (segments || c.surface != raw) row.addView(chip(c.surface, primary = false, shown = i == shownIndex, wrap = false) { choose(token, i) })
        }
    }


   /**
     * Fills both panel groups for [typed]. Before the lookup answers the ranked list alone fills them; it is then only
     * extended, because the list is recomputed from the same inputs plus [panelData], and each group stays where it was
     * scrolled. Nothing here counts: list sizes are not shown.
     */
    private fun renderPanel(typed: String, ranked: List<Candidate>, shown: String?) {
        val data = panelData
        topCandidates = CandidateGroups.top(typed, ranked, data?.extras.orEmpty())
        exactCandidates = CandidateGroups.exact(typed, data?.exact.orEmpty(), ranked)
        val completed = data != null
        panelTop?.setItems(topCandidates.map { it.surface }, topCandidates.indexOfFirst { it.surface == shown }, keepScroll = completed)
        panelExact?.setItems(exactCandidates.map { it.surface }, exactCandidates.indexOfFirst { it.surface == shown }, primaryIndex = 0, keepScroll = completed)
        // One title whether the list holds whole-reading words or one-word replacements (users asked for no sub-label).
        (panelExactHeader?.getChildAt(0) as? TextView)?.text =
            getString(if (data == null) R.string.panel_exact_loading else R.string.panel_exact_title)
    }

    /**
     * English composition: candidates for the whole composition ([WholeCandidates]), what was typed first (what
     * Space/Enter commit); with nothing to offer (an emoji alone) one chip with what is shown.
     * A tap commits exactly that candidate, without a space, and only while the composition it was drawn for is current.
     * ▼ opens the panel with all of them, as for kana.
     */
    private fun renderEnglish(row: LinearLayout) {
        val gen = englishGen
        val shown = english.shown
        val current = { session.isActive && englishGen == gen && english.shown == shown }
        val candidates = englishCandidates().list
        expandButton?.text = when {
            expanded -> "▲"
            candidates.size > 1 -> "▼"
            else -> ""
        }
        val strip = if (expanded) candidates.take(1) else candidates
        strip.forEachIndexed { i, c ->
            row.addView(chip(c, primary = i == 0, shown = false, wrap = false) { if (current()) commitEnglish(c) }.apply {
                tag = TAG_ENGLISH_CANDIDATE
            })
        }
        if (candidates.isEmpty()) {
            row.addView(chip(shown, primary = true, shown = false, wrap = false) { if (current()) commitEnglish() }.apply {
                tag = TAG_ENGLISH_CANDIDATE
            })
        }
        if (expanded) renderEnglishPanel()
    }

    /**
     * The open panel for the English word: completions under 予測・補正, spellings and case forms under 変換, every
     * candidate of the strip in one of them. Built from the same lookup as the strip; any change of the composition
     * closes the panel, so it never shows another word's (or kana) candidates.
     */
    private fun renderEnglishPanel() {
        val all = englishCandidates()
        val shown = english.shown
        topCandidates = all.list.filter { it in all.predictions }.map { Candidate(shown, it, 0.0) }
        exactCandidates = all.list.filter { it !in all.predictions }.map { Candidate(shown, it, 0.0) }
        panelTop?.setItems(topCandidates.map { it.surface }, -1, keepScroll = true)
        panelExact?.setItems(exactCandidates.map { it.surface }, -1, primaryIndex = 0, keepScroll = true)
        (panelExactHeader?.getChildAt(0) as? TextView)?.text = getString(R.string.panel_exact_title)
    }

    /**
     * A segment selected: every segment as a button (the selected one marked; a tap selects another), then the selected
     * segment's candidates (a tap shows that one; nothing is committed). With the panel open it lists them instead.
     */
    private fun renderRepair(r: RepairSession, row: LinearLayout) {
        val revision = r.revision
        val current = { repair === r && r.revision == revision }
        renderSegments(row, r.shownUnits, r.focus) { i ->
            if (current()) {
                // A segment button selects that segment: its arrows resize it again, the composing caret is over.
                val wasCaret = r.caretMode
                val unit = r.unitOfShown(i) // a part of a split at the caret selects its whole segment
                r.caretMode = false
                if (r.focus(unit) || wasCaret) afterRepairChange(request = true)
            }
        }
        when {
            r.candidates.isEmpty() -> if (worker.isReady) row.addView(status(getString(R.string.status_converting)))
            expanded -> renderPanel(r.scopeTyped, r.candidates, r.scopeSurface)
            else -> r.candidates.forEachIndexed { i, c ->
                row.addView(chip(c.surface, primary = false, shown = i == r.candidateIndex, wrap = false) {
                    // A choice at the caret may leave the other part of a cut word to convert again.
                    if (current() && r.choose(i)) afterRepairChange(request = r.hasUnconverted)
                })
            }
        }
    }

    /** Segment buttons, then a thin divider before whatever candidates follow. [selected] -1: none selected yet. */
    private fun renderSegments(row: LinearLayout, units: List<RepairUnit>, selected: Int, onTap: (Int) -> Unit) {
        units.forEachIndexed { i, u ->
            row.addView(chip(u.surface.ifEmpty { u.typed }, primary = i != selected, shown = i == selected, wrap = false) { onTap(i) }.apply {
                tag = TAG_SEGMENT
                contentDescription = getString(R.string.segment_button, u.surface.ifEmpty { u.typed })
            })
        }
        val d = resources.displayMetrics.density
        row.addView(TextView(this).apply {
            tag = TAG_SEGMENT_DIVIDER
            setBackgroundColor(colors.hint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams((1 * d).toInt().coerceAtLeast(1), (24 * d).toInt()).apply { setMargins((6 * d).toInt(), 0, (6 * d).toInt(), 0) })
    }

    private fun toggleExpanded() {
        if (expanded) {
            collapse()
            render()
            return
        }
        val kb = keyboard ?: return
        val p = panel ?: return
        val r = repair
        val word = !english.isEmpty
        // A selected segment always has a panel (its lookup fills it); otherwise only a result with alternatives.
        if (word && englishCandidates().list.size <= 1) return
        if (!word && r == null && (appliedToken == null || session.candidates.size <= 1)) return
        // ▼ also reveals the segment buttons above the panel.
        if (r == null && !word) segmentsShown = true
        // Over a palette the panel takes the palette's room as well (PanelSizing starts from it).
        val box = paletteBox?.takeIf { it.visibility == View.VISIBLE }
        val boxHeight = box?.let { b -> b.height.takeIf { it > 0 } ?: b.measuredHeight } ?: 0
        panelKeysRoom = (kb.height.takeIf { it > 0 } ?: kb.measuredHeight) + boxHeight
        box?.visibility = View.GONE
        paletteGrid?.cancelGesture()
        p.visibility = View.VISIBLE
        kb.clearGesture()
        kb.visibility = View.GONE
        expanded = true
        showPanelFocus()
        // The English panel lists the lookup the strip already has: nothing to ask the converter.
        if (!word) requestPanel(if (r != null) r.scopeTyped else session.composingText)
        render()
    }

    /**
     * Header tap: the group takes the panel, or gives it back. Only the layout changes: no lookup, no edit, no commit, and
     * the strip and the composition stay as they are. A click left over from a closed panel does nothing.
     */
    private fun togglePanelFocus(group: PanelGroup) {
        if (!expanded) return
        panelFocus = if (panelFocus == group) null else group
        showPanelFocus()
    }

    /** Puts the panel's views and height in the state of [panelFocus]; a press in progress ends without a pick. */
    private fun showPanelFocus() {
        val focus = panelFocus
        val top = panelTop ?: return
        val exact = panelExact ?: return
        top.cancelGesture()
        exact.cancelGesture()
        top.twoRows = focus != PanelGroup.TOP
        exact.twoRows = focus != PanelGroup.EXACT
        val hideTop = if (focus == PanelGroup.EXACT) View.GONE else View.VISIBLE
        val hideExact = if (focus == PanelGroup.TOP) View.GONE else View.VISIBLE
        top.visibility = hideTop
        panelTopHeader?.visibility = hideTop
        exact.visibility = hideExact
        panelExactHeader?.visibility = hideExact
        labelHeader(panelTopHeader, R.string.panel_prediction, focus == PanelGroup.TOP)
        labelHeader(panelExactHeader, R.string.panel_exact_title, focus == PanelGroup.EXACT)
        sizePanel()
    }

    private fun labelHeader(header: LinearLayout?, title: Int, focused: Boolean) {
        if (header == null) return
        (header.getChildAt(1) as TextView).text = if (focused) getString(R.string.panel_shrink) else expandIcon
        header.contentDescription = getString(if (focused) R.string.panel_shrink_group else R.string.panel_expand_group, getString(title))
    }

    /** See [PanelSizing]: the panel stops short of the composition, whatever the orientation or the bars. */
    private fun sizePanel() {
        val p = panel ?: return
        val d = resources.displayMetrics.density
        val chrome = (44 * d).toInt() * (if (repair != null) 2 else 1)
        val sizing = PanelSizing(
            resources.displayMetrics.heightPixels,
            maxOf(topInset, (PanelSizing.MIN_TOP_DP * d).toInt()),
            inputRoot?.paddingBottom ?: 0,
            chrome,
            panelKeysRoom,
            d,
        )
        val wanted = if (panelFocus != null) sizing.focused else sizing.normal
        if (p.layoutParams.height != wanted) p.layoutParams = p.layoutParams.apply { height = wanted }
    }

    /** Asks for everything the ranked list does not hold; one explicit lookup per opening, off the main thread. */
    private fun requestPanel(text: String) {
        val request = Any()
        panelRequest = request
        panelData = null
        if (!worker.isReady) {
            panelData = ExactPanel(emptyList(), 0, emptyList())
            return
        }
        lookupPanel(text) { data ->
            if (expanded && panelRequest === request) {
                panelData = data
                render()
            }
        }
    }

    /** Internal for tests: a homophone list of any length instead of the fixed test lexicon. */
    internal open fun lookupPanel(text: String, onResult: (ExactPanel) -> Unit) {
        worker.submitExact(text, if (personalized) learning.snapshot() else null, onResult)
    }

    /** Group header: one touch target over its full width, the title on the left and expand or 縮小 on the right. */
    private fun panelHeader(viewTag: String, title: Int, group: PanelGroup): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            tag = viewTag
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            addView(TextView(context).apply {
                text = getString(title)
                textSize = 13f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                textSize = 18f
                isSingleLine = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { togglePanelFocus(group) }
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        panelFocus = null
        showPanelFocus()
        panelRequest = null
        panelData = null
        panel?.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
        if (mode.isPalette) paletteBox?.visibility = View.VISIBLE
    }

    /**
     * Strip chips stay on one line (long ones are cut, the panel shows them in full).
     * [shown] marks the candidate currently displayed by live conversion.
     */
    private fun chip(label: String, primary: Boolean, shown: Boolean, wrap: Boolean, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        val pad = (14 * d).toInt()
        return TextView(this).apply {
            text = label
            textSize = 18f
            if (wrap) {
                minHeight = (44 * d).toInt()
                setPadding(pad, (8 * d).toInt(), pad, (8 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            } else {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
                gravity = Gravity.CENTER
                setPadding(pad, 0, pad, 0)
            }
            setTextColor(if (primary && !shown) colors.primary else if (shown) colors.onShown else colors.text)
            isSelected = shown // also read by accessibility services
            if (shown) setBackgroundColor(colors.shown)
            else background = RippleDrawable(ColorStateList.valueOf(colors.ripple), null, ColorDrawable(Color.WHITE))
            setOnClickListener { onClick() }
            layoutParams = if (wrap) {
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    }

    /** The bounded preview only: neither the view nor its accessibility label ever holds the whole clip. */
    private fun clipChip(preview: String): TextView = chip(preview, primary = false, shown = false, wrap = false) {
        pasteClipboard()
    }.apply {
        tag = TAG_CLIP_PREVIEW
        textSize = 14f
        minWidth = (48 * resources.displayMetrics.density).toInt()
        isSaveEnabled = false
        contentDescription = getString(R.string.clip_paste, preview)
    }

    private fun status(message: String): TextView {
        val pad = (14 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = message
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            setTextColor(colors.hint)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
}
