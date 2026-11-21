package dev.astra.ime

import dev.astra.ime.core.Candidate
import dev.astra.ime.core.CandidateGroups
import dev.astra.ime.core.Segment
import dev.astra.ime.core.SegmentKind
import dev.astra.ime.oss.EditPenalties
import dev.astra.ime.oss.MappedDictionary
import dev.astra.ime.oss.OssConverter
import dev.astra.ime.oss.OssResources
import dev.astra.ime.oss.OssStatus
import dev.astra.ime.oss.Span
import dev.astra.ime.oss.UserWords
import dev.astra.ime.oss.Word
import dev.astra.ime.oss.zenz.ZenzRanking
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Experimental backend: Mozc dictionary_oss compiled by `:oss-converter` and converted with the pinned
 * kotlin-kana-kanji-converter primitives. The dictionary (`lexicon.bin`) is memory-mapped from the APK, so its pages
 * are file-backed and not Java heap; only the connection matrix is read onto the heap. Never the only path: any status
 * other than OK, or no loaded converter, falls back to the `:core` engine. Nothing is logged.
 */
internal object OssBackend {
    const val ASSET_DIR = "oss"

    /** Build-time verified hashes (`sha256  name` lines), copied from android/app/oss-assets.lock. */
    const val HASHES = "oss-assets.sha256"

    /** Outcome of a load attempt; no text is involved. */
    enum class LoadState { MISSING, READY, FAILED }

    class Loaded(val state: LoadState, val converter: OssConverter?, val millis: Long)

    /**
     * Loads the converter with every asset checked against [HASHES]. Call off the main thread. [open] returns a fresh
     * stream for a path below [ASSET_DIR] and throws if it is absent; [map] maps one read-only (the APK stores
     * `lexicon.bin` uncompressed). The mapped bytes are hashed before first use; with [verified], only once per
     * installed APK, since hashing reads every page and its cost grows with the dictionary.
     */
    fun load(
        open: (String) -> InputStream,
        exists: (String) -> Boolean,
        map: (String) -> ByteBuffer,
        verified: VerifiedStamp? = null,
    ): Loaded {
        if (!exists(HASHES)) return Loaded(LoadState.MISSING, null, 0)
        val started = System.nanoTime()
        return try {
            val expected = open(HASHES).use { parseHashes(it) }
            fun sha(name: String) = expected[name] ?: throw IOException("Unlisted OSS asset")
            val lexicon = map(MappedDictionary.FILE)
            val lexiconSha = sha(MappedDictionary.FILE)
            if (verified?.matches(lexiconSha) != true) {
                if (sha256(lexicon) != lexiconSha) throw IOException("OSS asset hash mismatch")
                verified?.record(lexiconSha)
            }
            val dictionary = MappedDictionary.open(
                lexicon,
                VerifyingInputStream(open(OssResources.CONNECTION), sha(OssResources.CONNECTION)),
                VerifyingInputStream(open(OssResources.MANIFEST), sha(OssResources.MANIFEST)),
            )
            Loaded(LoadState.READY, OssConverter(dictionary), (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            // Deliberately not logged; the caller keeps using :core.
            Loaded(LoadState.FAILED, null, 0)
        } catch (e: OutOfMemoryError) {
            Loaded(LoadState.FAILED, null, 0)
        }
    }

    /**
     * OSS candidates (best first, raw included) or null when the result must not be used and the caller
     * should fall back to `:core`: any status other than OK. With [correction], flick slips may be corrected
     * (literal result kept); with [zenz], the first [ZENZ_SCORED] candidates are reordered by
     * [ZenzRanking] over the whole composition. A zenz failure keeps the dictionary order.
     */
    fun candidatesOrNull(
        converter: OssConverter,
        text: String,
        limit: Int,
        correction: EditPenalties? = null,
        zenz: ZenzRanking.Scorer? = null,
    ): List<Candidate>? = convertOrNull(converter, text, limit, correction, zenz)?.candidates

    /** Candidates with the dictionary words of each one (for learning), in the same order. */
    class Converted(val candidates: List<Candidate>, val words: List<List<Word>>, val contextRanked: Boolean = false)

    /**
     * As [candidatesOrNull], with on-device learning: [user] adjusts the dictionary lattice and candidates that use
     * a learned word get [ZenzRanking.Weights.learned] in the zenz judge.
     */
    fun convertOrNull(
        converter: OssConverter,
        text: String,
        limit: Int,
        correction: EditPenalties? = null,
        zenz: ZenzRanking.Scorer? = null,
        user: UserWords? = null,
        /** Fixed surface before [text] (committed text, repaired units), given to zenz as left context only. */
        leftContext: String = "",
        /** Whether [leftContext] holds committed text from the editor; never inferred from it. */
        external: Boolean = false,
        /** Learned words that count when the model ranks with context: explicit choices and registered words. */
        strong: (Word) -> Boolean = { user?.hasBonus(it) == true },
    ): Converted? {
        val result = converter.convert(text, limit, correction, user)
        if (result.status != OssStatus.OK) return null
        val order = zenz?.let { scorer ->
            val head = ZenzRanking.Head(result.headLength, result.headSurface, result.headReading)
            val learned = { i: Int -> user != null && result.words[i].any { user.hasBonus(it) } }
            val strongLearned = { i: Int -> result.words[i].any(strong) }
            ZenzRanking.rerank(
                text, result.candidates, result.readings, ZENZ_SCORED, RANKING, head, learned = learned, leftContext = leftContext,
                external = external, strongLearned = strongLearned, scorer = scorer,
            )
        }
        // Whether the model, not the dictionary, ordered the exact-reading candidates (a failed score keeps the dictionary's).
        val contextRanked = order != null && ZenzRanking.contextRanks(RANKING, external, leftContext)
        val kept = ZenzRanking.keepExactLead(order ?: result.candidates.indices.toList(), result.readings, text, result.correctionLeads)
        return Converted(
            kept.mapIndexed { rank, i ->
                Candidate(result.readings[i], result.candidates[i], rank.toDouble(), result.spans[i]?.let(::segments))
            },
            kept.map { result.words[it] },
            contextRanked,
        )
    }


    /** Whether the text before the cursor argues against a learned [phrase] and for [leader]; see [ZenzRanking.contextOutweighs]. */
    fun contextOutweighs(zenz: ZenzRanking.Scorer, text: String, phrase: String, leader: String, leftContext: String, unitsOnly: String): Boolean =
        ZenzRanking.contextOutweighs(text, phrase, leader, leftContext, unitsOnly, RANKING, zenz)

    /** Lower panel data for [text]; see [ExactPanel]. Null with anything but a clean dictionary answer. */
    fun exactOrNull(
        converter: OssConverter,
        text: String,
        correction: EditPenalties?,
        user: UserWords?,
        cancelled: () -> Boolean = { false },
    ): ExactPanel? {
        val exact = converter.exactCandidates(text, user, cancelled) ?: return null
        if (exact.status != OssStatus.OK) return null
        val dictionary = exact.candidates.map { Candidate(it.reading, it.surface, it.cost.toDouble(), segments(it.spans)) }
        val suggestions = converter.convert(text, ConversionWorker.LIMIT, correction, user).suggestions
            .map { Candidate(it.reading, it.surface, 0.0, segments(it.spans)) }
        val completions = converter.completions(text, CandidateGroups.MAX_TOP).map { Candidate(it.reading, it.surface, 0.0) }
        return ExactPanel(dictionary, exact.wholeCount, suggestions + completions)
    }

    /** Converter spans as `:core` segments (same typed offsets); the app checks them with Alignment.checked. */
    internal fun segments(spans: List<Span>): List<Segment> = spans.map {
        val kind = when (it.kind) {
            Span.Kind.WORD -> SegmentKind.WORD
            Span.Kind.PASSTHROUGH -> SegmentKind.PASSTHROUGH
            Span.Kind.LITERAL -> SegmentKind.LITERAL
        }
        Segment(it.typedStart, it.typedEnd, it.reading, it.surface, kind)
    }

    /** Candidates scored by zenz per keystroke; bounds the per-keystroke cost on a phone. */
    const val ZENZ_SCORED = 8

    /**
     * The shipped weights. With meaningful committed context the model orders the exact-reading candidates
     * ([ZenzRanking.Weights.contextFirst], the user's decision of 2026-11-02, reports/ranking-learning-0.1.31.md); `margin2`
     * (data/context-holdout/protocol.md) remains only for context without a letter. The phrase limits come from
     * data/context-learning-diag.tsv, a small diagnostic set, not from that holdout.
     */
    private val RANKING = ZenzRanking.Weights(contextMargin = 2.0, phraseGap = 3.0, phraseShift = 1.5, contextFirst = true)

    internal fun sha256(buffer: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(buffer.duplicate().also { it.position(0) })
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun parseHashes(input: InputStream): Map<String, String> =
        input.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.trim().split(Regex("\\s+"))
            check(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) { "Malformed $HASHES" }
            parts[1] to parts[0]
        }
}

/**
 * Remembers that the bundled `lexicon.bin` with a given SHA-256 was hashed and matched for one installed APK
 * ([install], e.g. versionCode and lastUpdateTime). Assets change only with a new install or update, which Android
 * verifies by signature and which changes [install], so a match skips re-hashing. Stored in [file] (app-private, not
 * backed up); unreadable or unwritable means "not verified" and the hash runs again.
 */
internal class VerifiedStamp(private val file: File, private val install: String) {
    fun matches(sha: String): Boolean = try {
        file.isFile && file.readText(Charsets.UTF_8) == line(sha)
    } catch (e: IOException) {
        false
    }

    fun record(sha: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(line(sha), Charsets.UTF_8)
        } catch (e: IOException) {
            // Not recorded: the next load hashes again.
        }
    }

    private fun line(sha: String) = "$install $sha\n"
}

/**
 * Hashes everything read and, on close, the unread remainder too; throws from [close] when the whole
 * stream does not match [expectedSha256]. Readers close every stream (OssResources does), so a corrupted
 * asset fails the load instead of being used.
 */
internal class VerifyingInputStream(input: InputStream, private val expectedSha256: String) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var closed = false

    override fun read(): Int = super.read().also { if (it >= 0) digest.update(it.toByte()) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) digest.update(b, off, it) }

    /** Skipped bytes must still be hashed. */
    override fun skip(n: Long): Long {
        val buffer = ByteArray(8192)
        var left = n
        while (left > 0) {
            val read = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) break
            left -= read
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            val buffer = ByteArray(1 shl 16)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        } finally {
            super.close()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256) throw IOException("OSS asset hash mismatch")
    }
}
