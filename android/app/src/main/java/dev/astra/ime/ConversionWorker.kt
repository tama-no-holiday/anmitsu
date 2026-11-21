package dev.astra.ime

import android.content.res.AssetManager
import android.os.Handler
import dev.astra.ime.core.Candidate
import dev.astra.ime.core.ConversionEngine
import dev.astra.ime.core.LexEntry
import dev.astra.ime.core.LiteralChoices
import dev.astra.ime.core.SessionToken
import dev.astra.ime.core.Supplement
import dev.astra.ime.oss.EditPenalties
import dev.astra.ime.oss.OssConverter
import dev.astra.ime.oss.Word
import dev.astra.ime.oss.zenz.ZenzNative
import dev.astra.ime.oss.zenz.ZenzRanking
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Single background thread with at most one waiting job per kind (latest request of that kind wins), so a panel
 * lookup never evicts a waiting conversion or the other way round.
 * Works only on immutable [SessionToken]s and never touches InputSession.
 * All public methods are called on the main thread; results are posted back to [main].
 *
 * The optional OSS converter loads on its own thread so `:core` keeps converting meanwhile; a conversion
 * uses it only when it was ready at submit time and returned OK, otherwise `:core`.
 */
internal class ConversionWorker(
    private val assets: AssetManager,
    private val main: Handler,
    /** Private directory for the zenz model and library copies; null disables zenz (e.g. in tests). */
    private val storage: File? = null,
    /** Skips re-hashing the mapped OSS lexicon once it was verified for this install; null hashes on every load. */
    private val ossVerified: VerifiedStamp? = null,
    /** Whether the device font draws an emoji; called on the conversion thread only. */
    private val emojiRenderable: (String) -> Boolean = { true },
) {

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { runnable -> Thread(runnable, "astra-conversion").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(), // only after shutdown: the queue itself has no bound
    )
    /** The waiting job of each kind, replaced by a newer one; main thread only. Finished ones are simply not found. */
    private var waitingConversion: Runnable? = null
    private var waitingExact: Runnable? = null
    private val ossLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "astra-oss-load").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    /** Separate from [generation]: an open panel's lookup must not invalidate, or be invalidated by, a finished conversion. */
    private val exactGeneration = AtomicLong()
    private var engine: ConversionEngine? = null // main thread only
    /** Symbols, numbers, emoticons, emoji and single kanji for a whole reading ([Supplement]); main thread only. */
    private var supplement: Supplement = Supplement.EMPTY
    private var closed = false // main thread only

    /** OSS backend and zenz state; main thread only. UNSUPPORTED: zenz only, this CPU cannot run its library. */
    enum class OssState { OFF, LOADING, READY, MISSING, UNSUPPORTED, FAILED }

    private var oss: OssConverter? = null // main thread only
    private var ossLoadGeneration = 0L // main thread only
    var ossState = OssState.OFF
        private set
    var ossLoadMillis = 0L
        private set

    /** zenz judge state; main thread only. Loaded on [ossLoader] after the OSS dictionary. */
    private var zenz: ZenzNative? = null // main thread only
    private var zenzLoadGeneration = 0L // main thread only
    var zenzState = OssState.OFF
        private set

    /** Tests only: a scorer and converter in place of the native model and the full dictionary; main thread only. */
    private var testScorer: ZenzRanking.Scorer? = null

    internal fun useForTests(converter: OssConverter, scorer: ZenzRanking.Scorer) {
        oss = converter
        testScorer = scorer
    }

    /** Whether a judge could use a left context now: without one, context goes unused. */
    val judges: Boolean get() = oss != null && scorer() != null

    private fun scorer(): ZenzRanking.Scorer? =
        testScorer ?: zenz?.let { native -> ZenzRanking.Scorer { context, reading, list -> native.score(reading, list, context) } }

    /** Flick-slip correction on the OSS path; main thread only. */
    var correctionEnabled = false

    var failed = false
        private set
    val isReady: Boolean get() = engine != null

    /**
     * Other forms of the punctuation [text] from the same table kana use ([Supplement]): other widths and Mozc's bracket
     * and mark groups, keeping opening/closing (`(` → （ 「 【 …, `()` → （） 「」 【】 …). Symbols only, never emoticons,
     * emoji or kanji. For a run of digits with [numbers], its number forms as kana get them (1 → １ ① 一 Ⅰ …, 12 →
     * １２ ⑫ 十二 …) and the table's symbols; without [numbers] (address fields) none. Empty before the table has loaded
     * or without one. Main thread only.
     */
    fun symbolAlternatives(text: String, numbers: Boolean = false): List<String> {
        val digits = text.isNotEmpty() && text.all { it in '0'..'9' }
        if (digits && !numbers) return emptyList()
        val kinds = if (digits) setOf(Supplement.Kind.NUMBER, Supplement.Kind.SYMBOL) else setOf(Supplement.Kind.SYMBOL)
        return supplement.lookup(text).filter { it.kind in kinds }.map { it.surface }
    }

    /**
     * Loads the lexicon off the main thread. Conversions are only submitted after the engine
     * is published, so a newer request can never discard or cancel this task.
     */
    fun load(onDone: () -> Unit) {
        executor.execute {
            val loaded = try {
                ConversionEngine(readLexicon())
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            // Optional: a missing or malformed table only means no supplementary candidates.
            val extra = try {
                assets.open(SUPPLEMENT).bufferedReader(Charsets.UTF_8).use(Supplement::parse)
            } catch (e: Exception) {
                Supplement.EMPTY
            } catch (e: OutOfMemoryError) {
                Supplement.EMPTY
            }
            main.post {
                if (closed) return@post
                engine = loaded
                supplement = extra
                failed = loaded == null
                onDone()
            }
        }
    }

    /**
     * Turns the experimental OSS backend on (loads once, off the main thread) or off (drops it so its
     * memory can be reclaimed). A load that finishes after being turned off is discarded.
     */
    fun setOssEnabled(enabled: Boolean, onChange: () -> Unit) {
        if (closed) return
        if (!enabled) {
            if (ossState == OssState.OFF) return
            ossLoadGeneration++
            // Queued conversions captured the old converter; drop their results too.
            generation.incrementAndGet()
            oss = null
            ossState = OssState.OFF
            onChange()
            return
        }
        // No automatic retry after FAILED/MISSING (e.g. OOM): turning the setting off and on retries.
        if (ossState != OssState.OFF) return
        val gen = ++ossLoadGeneration
        ossState = OssState.LOADING
        onChange()
        ossLoader.execute {
            val loaded = OssBackend.load(
                open = { name -> assets.open("${OssBackend.ASSET_DIR}/$name") },
                exists = { name -> assets.list(OssBackend.ASSET_DIR)?.contains(name) == true },
                // Stored uncompressed in the APK (noCompress), so the asset is a plain file range that can be mapped.
                map = { name ->
                    assets.openFd("${OssBackend.ASSET_DIR}/$name").use { fd ->
                        fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    }
                },
                verified = ossVerified,
            )
            main.post {
                if (closed || gen != ossLoadGeneration) return@post
                oss = loaded.converter
                ossLoadMillis = loaded.millis
                ossState = when (loaded.state) {
                    OssBackend.LoadState.READY -> OssState.READY
                    OssBackend.LoadState.MISSING -> OssState.MISSING
                    OssBackend.LoadState.FAILED -> OssState.FAILED
                }
                onChange()
            }
        }
    }

    /**
     * Turns the zenz judge on (checks the CPU, then copies/loads the model and library once, off the main thread) or off.
     * Turning it off closes the scorer on the loader thread; ZenzNative waits for a running score first.
     */
    fun setZenzEnabled(enabled: Boolean, onChange: () -> Unit) {
        val dir = storage ?: return
        if (closed) return
        if (!enabled) {
            if (zenzState == OssState.OFF) return
            zenzLoadGeneration++
            generation.incrementAndGet()
            zenz?.let { old -> closeOffMain(old::close) }
            zenz = null
            zenzState = OssState.OFF
            onChange()
            return
        }
        if (zenzState != OssState.OFF) return
        val gen = ++zenzLoadGeneration
        zenzState = OssState.LOADING
        onChange()
        ossLoader.execute {
            val loaded = ZenzBackend.load(
                open = { name -> assets.open("${ZenzBackend.ASSET_DIR}/$name") },
                exists = { name -> assets.list(ZenzBackend.ASSET_DIR)?.contains(name) == true },
                storage = dir,
            )
            main.post {
                if (closed || gen != zenzLoadGeneration) {
                    loaded.scorer?.let { s -> closeOffMain(s::close) }
                    return@post
                }
                zenz = loaded.scorer
                zenzState = when (loaded.state) {
                    ZenzBackend.LoadState.READY -> OssState.READY
                    ZenzBackend.LoadState.MISSING -> OssState.MISSING
                    ZenzBackend.LoadState.UNSUPPORTED -> OssState.UNSUPPORTED
                    ZenzBackend.LoadState.FAILED -> OssState.FAILED
                }
                onChange()
            }
        }
    }

    /** With the zenz judge the dictionary may offer bolder corrections; alone it stays conservative. */
    private fun correctionFor(judge: ZenzNative?): EditPenalties? = when {
        !correctionEnabled -> null
        judge != null -> EditPenalties()
        else -> EditPenalties().scaled(CONSERVATIVE_CORRECTION)
    }

    /**
     * Converts [token] off the main thread. [learning] (captured now, immutable) personalizes the OSS path; the
     * result carries each candidate's dictionary words (empty where unknown, e.g. the `:core` fallback).
     */
    fun submit(
        token: SessionToken,
        learning: LearningSnapshot? = null,
        /** Fixed surface before the token's text (committed text, repaired units); zenz context only. */
        leftContext: String = "",
        /** Whether [leftContext] holds committed text from the editor, not only repaired units. */
        external: Boolean = false,
        /** The part of [leftContext] that is not from the editor: what a learned phrase is compared against. */
        unitsOnly: String = "",
        onResult: (SessionToken, List<Candidate>, List<List<Word>>) -> Unit,
    ) {
        submitText(token.text, learning, leftContext, arrange = true, external = external, unitsOnly = unitsOnly) { candidates, words -> onResult(token, candidates, words) }
    }

    /**
     * Converts [text] like [submit] (same single latest-wins queue, so it supersedes any other request). Without
     * [arrange], learned phrases and predictions are not inserted: repair units need candidates for exactly [text].
     * The caller's own token must still reject stale results.
     */
    fun submitText(
        text: String,
        learning: LearningSnapshot?,
        leftContext: String,
        arrange: Boolean,
        external: Boolean = false,
        unitsOnly: String = "",
        onResult: (List<Candidate>, List<List<Word>>) -> Unit,
    ) {
        val convert = converter() ?: return
        val gen = generation.incrementAndGet()
        val stale = { generation.get() != gen || Thread.currentThread().isInterrupted }
        conversionJob {
            if (stale()) return@conversionJob
            val result = try {
                convert(text, learning, leftContext, external, unitsOnly, arrange, stale)
            } catch (e: Exception) {
                // Includes CancellationException. Deliberately not logged (may relate to input).
                return@conversionJob
            } catch (e: OutOfMemoryError) {
                // No result: the raw chip stays the only choice. Not logged, input not retained.
                return@conversionJob
            } catch (e: StackOverflowError) {
                return@conversionJob
            }
            main.post { if (!closed && generation.get() == gen) onResult(result.first, result.second) }
        }
    }

    private fun conversionJob(job: Runnable) {
        waitingConversion?.let(executor::remove)
        waitingConversion = job
        executor.execute(job)
    }

    private fun exactJob(job: Runnable) {
        waitingExact?.let(executor::remove)
        waitingExact = job
        executor.execute(job)
    }

    /**
     * 区切り: converts [parts] (the kana on each side of the caret) in one job on the same latest-wins queue, each exactly
     * as [submitText] without arranging, so neither part's request can supersede the other. Each part gets the first
     * candidates of the parts before it as zenz context. [onResult] gets one list per part, or nothing when stale.
     */
    fun submitParts(
        parts: List<String>,
        learning: LearningSnapshot?,
        leftContext: String,
        external: Boolean = false,
        onResult: (List<List<Candidate>>) -> Unit,
    ) {
        val convert = converter() ?: return
        val gen = generation.incrementAndGet()
        val stale = { generation.get() != gen || Thread.currentThread().isInterrupted }
        conversionJob {
            if (stale()) return@conversionJob
            val result = try {
                var context = leftContext
                parts.map { part ->
                    convert(part, learning, context, external, "", false, stale).first.also { context += it.firstOrNull()?.surface ?: part }
                }
            } catch (e: Exception) {
                return@conversionJob
            } catch (e: OutOfMemoryError) {
                return@conversionJob
            } catch (e: StackOverflowError) {
                return@conversionJob
            }
            main.post { if (!closed && generation.get() == gen) onResult(result) }
        }
    }

    /**
     * The conversion pipeline for one text with what is loaded now (captured on the main thread): OSS (or `:core`),
     * learning when [arrange]d, supplementary candidates, then the literal choices. Runs on the conversion thread.
     */
    private fun converter(): ((String, LearningSnapshot?, String, Boolean, String, Boolean, () -> Boolean) -> Pair<List<Candidate>, List<List<Word>>>)? {
        val current = engine ?: return null
        val ossConverter = oss
        val judge = scorer()
        val extra = supplement
        val correction = correctionFor(zenz)
        return { text, learning, leftContext, external, unitsOnly, arrange, stale ->
            // OSS and zenz cannot be cancelled mid-run; stale results are still dropped by the callers.
            val fromOss = ossConverter?.let {
                OssBackend.convertOrNull(it, text, LIMIT, correction, judge, learning?.user, leftContext, external, { w -> learning?.isStrong(w) == true })
            }
            val converted = fromOss?.candidates ?: current.convert(text, LIMIT, stale)
            val words = fromOss?.words ?: converted.map { emptyList() }
            val outweighed = { phrase: String, leader: String ->
                external && judge != null && !stale() && OssBackend.contextOutweighs(judge, text, phrase, leader, leftContext, unitsOnly)
            }
            val (arranged, arrangedWords) = learning?.takeIf { arrange }?.arrange(text, converted, words, fromOss?.contextRanked == true, outweighed) ?: (converted to words)
            val supplemented = extra.insert(text, arranged, emojiRenderable)
            val supplementedWords = supplemented.source.map { i -> if (i >= 0) arrangedWords.getOrElse(i) { emptyList() } else emptyList() }
            withLiteralChoices(text, supplemented.candidates, supplementedWords)
        }
    }

    /**
     * The expanded panel's data for [text], on the conversion thread: this is an explicit request, one trie lookup plus
     * the word list of that reading, never run per key press. A failed OSS answer falls back to the `:core` lexicon.
     * The caller rejects a result that no longer matches what it shows.
     */
    fun submitExact(text: String, learning: LearningSnapshot?, onResult: (ExactPanel) -> Unit) {
        val current = engine ?: return
        val ossConverter = oss
        val extra = supplement
        val correction = correctionFor(zenz)
        val gen = exactGeneration.incrementAndGet()
        val stale = { exactGeneration.get() != gen || Thread.currentThread().isInterrupted }
        exactJob {
            if (stale()) return@exactJob
            val result = try {
                val panel = ossConverter?.let { OssBackend.exactOrNull(it, text, correction, learning?.user, stale) }
                    ?: ExactPanel(current.exactEntries(text), current.exactEntries(text).size, emptyList())
                withSupplement(text, panel, extra.lookup(text, emojiRenderable))
            } catch (e: Exception) {
                return@exactJob // not logged; the panel keeps what the ranked list already gave it
            } catch (e: OutOfMemoryError) {
                return@exactJob
            } catch (e: StackOverflowError) {
                return@exactJob
            }
            main.post { if (!closed && exactGeneration.get() == gen) onResult(result) }
        }
    }

    /** Invalidates queued and running conversions; their results are dropped. */
    fun cancel() {
        generation.incrementAndGet()
        exactGeneration.incrementAndGet()
    }

    fun shutdown() {
        closed = true
        generation.incrementAndGet()
        exactGeneration.incrementAndGet()
        oss = null
        val judge = zenz
        zenz = null
        executor.shutdownNow()
        // Let the loader finish closing the scorer (it waits for a running score), then stop.
        judge?.let { s -> closeOffMain(s::close) }
        ossLoader.shutdown()
    }

    /** On the loader thread; a load that ends after [shutdown] finds it stopped and must still free the scorer. */
    internal fun closeOffMain(close: () -> Unit) {
        try {
            ossLoader.execute(close)
        } catch (e: RejectedExecutionException) {
            Thread(close, "astra-zenz-close").apply { isDaemon = true }.start()
        }
    }

    internal companion object {
        /**
         * InputSession.apply requires 1..20 results that include the raw surface. Also guarantees the katakana of the
         * typed text ([LiteralChoices]), reserving its slot inside the cap. Inserted choices carry no words.
         */
        fun withLiteralChoices(
            text: String,
            converted: List<Candidate>,
            words: List<List<Word>>,
        ): Pair<List<Candidate>, List<List<Word>>> {
            val ensured = LiteralChoices.ensure(text, converted, LIMIT)
            return ensured.candidates to ensured.source.map { i -> if (i >= 0) words.getOrElse(i) { emptyList() } else emptyList() }
        }

        /**
         * [items] (every supplementary candidate of the whole [text]) join the panel's whole-reading words, after the
         * dictionary's own and before any one-word replacements, so the exact list still holds all of them.
         */
        fun withSupplement(text: String, panel: ExactPanel, items: List<Supplement.Item>): ExactPanel {
            if (items.isEmpty()) return panel
            val present = panel.exact.mapTo(HashSet()) { it.surface }
            val added = items.filter { it.surface !in present }.map { Supplement.candidate(text, it) }
            val at = panel.wholeCount.coerceIn(0, panel.exact.size)
            return ExactPanel(panel.exact.subList(0, at) + added + panel.exact.subList(at, panel.exact.size), at + added.size, panel.extras)
        }

        const val LEXICON = "lexicon.tsv"
        const val SUPPLEMENT = "supplement/mozc-supplement.tsv"
        /** Matches InputSession's MAX_LIMIT so the expanded candidate panel can show every result. */
        const val LIMIT = 20

        /** Edit-penalty scale without zenz: few false corrections on the dev set (2/68), 4/15 slips fixed. */
        const val CONSERVATIVE_CORRECTION = 2.0
    }

    private fun readLexicon(): List<LexEntry> {
        val entries = ArrayList<LexEntry>()
        assets.open(LEXICON).bufferedReader(Charsets.UTF_8).use { reader ->
            var first = true
            reader.forEachLine { raw ->
                val line = if (first) raw.removePrefix("\uFEFF") else raw
                val header = first && line.startsWith("reading")
                first = false
                if (header || line.isBlank()) return@forEachLine
                val cols = line.split('\t')
                if (cols.size < 4 || cols[0].isEmpty() || cols[1].isEmpty()) return@forEachLine
                val cost = cols[2].toDoubleOrNull() ?: return@forEachLine
                entries.add(LexEntry(cols[0], cols[1], cost, cols[3]))
            }
        }
        return entries
    }
}

/**
 * Data for the expanded panel beyond the ranked list. [exact]: every spelling of the typed reading the lookup found, in
 * stable order, [wholeCount] of them one word for the entire reading. [extras]: bounded corrections and completions.
 */
internal class ExactPanel(val exact: List<Candidate>, val wholeCount: Int, val extras: List<Candidate>)
