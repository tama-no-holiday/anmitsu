package dev.astra.ime

import dev.astra.ime.core.Candidate
import dev.astra.ime.oss.AddedWord
import dev.astra.ime.oss.LearnedOrder
import dev.astra.ime.oss.UserWords
import dev.astra.ime.oss.Word
import java.io.File
import java.util.concurrent.Executor

/**
 * On-device conversion history and registered words (the user turned learning on; it is on by default and can be
 * turned off and erased in the setup screen). Only (reading, surface) pairs of conversions the user explicitly
 * committed are kept, in app-private no-backup storage; nothing is sent anywhere or logged, and sensitive or
 * no-learning editors are never recorded (the caller checks). Main thread only; files are written on [io].
 *
 * - words: dictionary words of committed conversions; the latest choice per reading gets the largest cost bonus,
 *   so 打った once chosen beats 売った next time (the zenz judge also gets a bonus for it).
 * - phrases: whole committed conversions, used as the first candidate for the same reading and as predictions
 *   for readings that start with what is typed (ぼーるう -> ボール打った).
 * - registered: words the user added (こーでっくす -> Codex), always available even if the dictionary lacks them.
 */
internal class LearningStore(private val dir: File?, private val io: Executor) {

    /** [strength]: what the user did (0.1.31); entries from before, or written by an older version, are [Strength.LEGACY]. */
    private class Entry(val reading: String, val surface: String, var count: Int, var seq: Long, var strength: Strength = Strength.LEGACY)

    private val words = LinkedHashMap<Pair<String, String>, Entry>()
    private val phrases = LinkedHashMap<Pair<String, String>, Entry>()
    private val registered = LinkedHashMap<String, MutableList<String>>() // reading -> surfaces, insertion order
    /** Palette entries the user committed, most recent first, per palette (0.1.32): the 履歴 tab. */
    private val recentSymbols = ArrayList<String>()
    private val recentEmoji = ArrayList<String>()
    private var seq = 0L
    private var version = 1L
    private var cached: LearningSnapshot? = null

    /** Reads the files once; unreadable or malformed files are ignored (learning starts empty). */
    fun load() {
        val d = dir ?: return
        readEntries(File(d, WORDS), words)
        readEntries(File(d, PHRASES), phrases)
        try {
            File(d, REGISTERED).takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.forEach { line ->
                val f = line.split('\t')
                if (f.size == 2 && validReading(f[0]) && validSurface(f[1])) registered.getOrPut(f[0]) { ArrayList(1) }.add(f[1])
            }
        } catch (e: Exception) {
            registered.clear()
        }
        readProvenance(File(d, PROVENANCE))
        readRecents(File(d, RECENTS))
        seq = (words.values + phrases.values).maxOfOrNull { it.seq } ?: 0
        changed(save = false)
    }

    /** Immutable view for the conversion thread; rebuilt only after a change. */
    fun snapshot(): LearningSnapshot = cached ?: build().also { cached = it }

    /**
     * Records a commit of [surface] for the typed [reading] with its dictionary [chosenWords] (may be empty when
     * unknown). [explicit]: the user chose something other than what was ranked first (see `docs/privacy-and-learning.md`);
     * otherwise the commit only accepted the ranking. Only an explicit commit records words, and only those not already in
     * the default conversion ([defaultWords]): accepting a default never reinforces it. An accepted phrase is recorded
     * as [Strength.IMPLICIT]; one already stronger is left exactly as it was. Kana-only words equal to their reading are not recorded.
     */
    fun learn(reading: String, surface: String, chosenWords: List<Word>, defaultWords: List<Word> = emptyList(), explicit: Boolean = true) {
        if (reading.isEmpty() || surface == reading || reading.length > MAX_PHRASE) return
        val defaults = defaultWords.toSet()
        if (explicit) {
            for (w in chosenWords) {
                if (w.surface == w.reading || !validReading(w.reading) || w in defaults) continue
                bump(words, w.reading, w.surface, MAX_WORDS, Strength.EXPLICIT)
            }
        }
        if (reading.length >= MIN_PHRASE && validReading(reading)) {
            val known = phrases[reading to surface]
            when {
                explicit -> bump(phrases, reading, surface, MAX_PHRASES, Strength.EXPLICIT)
                known == null || known.strength == Strength.IMPLICIT -> bump(phrases, reading, surface, MAX_PHRASES, Strength.IMPLICIT)
                else -> return // an explicit or legacy phrase is not reinforced by accepting it
            }
        }
        changed(save = true)
    }

    /** Adds a registered word; returns false for an invalid reading/surface. */
    fun register(reading: String, surface: String): Boolean {
        if (!validReading(reading) || !validSurface(surface)) return false
        val list = registered.getOrPut(reading) { ArrayList(1) }
        if (surface !in list) list += surface
        changed(save = true)
        return true
    }

    fun unregister(reading: String, surface: String) {
        val list = registered[reading] ?: return
        list.remove(surface)
        if (list.isEmpty()) registered.remove(reading)
        changed(save = true)
    }

    fun registeredWords(): List<Pair<String, String>> = registered.flatMap { (r, list) -> list.map { r to it } }

    /** Erases learned words and phrases and the palettes' 履歴 (registered words are kept). */
    fun clearHistory() {
        words.clear()
        phrases.clear()
        recentSymbols.clear()
        recentEmoji.clear()
        changed(save = true)
    }

    /** The 履歴 of the symbol or the [emoji] palette, most recent first. */
    fun recent(emoji: Boolean): List<String> = (if (emoji) recentEmoji else recentSymbols).toList()

    /**
     * The user committed palette [entry] from the symbol or the [emoji] palette: it moves to the front of that palette's
     * 履歴 (once, at most [MAX_RECENTS]). Only entries of the palette's own catalog are kept; anything else is ignored.
     */
    fun useEntry(emoji: Boolean, entry: String) {
        if (entry !in Palettes.catalog(emoji)) return
        val list = if (emoji) recentEmoji else recentSymbols
        if (list.firstOrNull() == entry) return
        list.remove(entry)
        list.add(0, entry)
        while (list.size > MAX_RECENTS) list.removeAt(list.lastIndex)
        changed(save = true)
    }

    val historySize: Int get() = words.size + phrases.size

    private fun bump(map: LinkedHashMap<Pair<String, String>, Entry>, reading: String, surface: String, max: Int, strength: Strength) {
        val key = reading to surface
        val e = map.remove(key) ?: Entry(reading, surface, 0, 0, strength)
        e.count = minOf(e.count + 1, MAX_COUNT)
        e.seq = ++seq
        e.strength = maxOf(e.strength, strength)
        map[key] = e // most recent last
        while (map.size > max) map.remove(map.keys.first())
    }

    private fun changed(save: Boolean) {
        version++
        cached = null
        if (!save) return
        val d = dir ?: return
        val w = words.values.map { "${it.reading}\t${it.surface}\t${it.count}\t${it.seq}" }
        val p = phrases.values.map { "${it.reading}\t${it.surface}\t${it.count}\t${it.seq}" }
        val r = registeredWords().map { "${it.first}\t${it.second}" }
        // Provenance in its own file: words.tsv and phrases.tsv keep the format older versions read. Legacy entries have no line.
        val v = listOf("w" to words, "p" to phrases).flatMap { (kind, map) ->
            map.values.filter { it.strength != Strength.LEGACY }.map { "$kind\t${it.reading}\t${it.surface}\t${it.strength.code}" }
        }
        val h = recentSymbols.map { "s\t$it" } + recentEmoji.map { "e\t$it" }
        io.execute {
            try {
                d.mkdirs()
                write(File(d, WORDS), w)
                write(File(d, PHRASES), p)
                write(File(d, REGISTERED), r)
                write(File(d, PROVENANCE), v)
                write(File(d, RECENTS), h)
            } catch (e: Exception) {
                // Not logged (paths only, but the content is user text). Learning stays in memory.
            }
        }
    }

    private fun build(): LearningSnapshot {
        val bonuses = HashMap<String, HashMap<String, Int>>()
        for ((reading, list) in words.values.groupBy { it.reading }) {
            val latest = list.maxByOrNull { it.seq } ?: continue
            val map = HashMap<String, Int>()
            for (e in list) {
                map[e.surface] = if (e === latest) LATEST_BONUS + PER_COUNT_BONUS * (minOf(e.count, 4) - 1) else OLDER_BONUS
            }
            bonuses[reading] = map
        }
        val additions = HashMap<String, List<AddedWord>>()
        for ((reading, surfaces) in registered) {
            additions[reading] = surfaces.map { AddedWord(it, REGISTERED_COST, UserWords.PROPER_NOUN_ID) }
            // Registered spellings the dictionary already has are promoted the same way.
            val map = bonuses.getOrPut(reading) { HashMap() }
            for (s in surfaces) map[s] = maxOf(map[s] ?: 0, REGISTERED_BONUS)
        }
        val ordered = phrases.values.sortedByDescending { it.seq }.map { Phrase(it.reading, it.surface, it.strength) }
        val pairs = registered.flatMap { (r, list) -> list.map { r to it } }.toSet()
        // Strong when the model ranks with context: words the user chose explicitly, and registered words.
        val strong = words.values.filter { it.strength == Strength.EXPLICIT }.map { it.reading to it.surface }.toSet() + pairs
        return LearningSnapshot(UserWords(version, bonuses, additions), ordered, pairs, strong)
    }

    /** Strengths of known entries; a line naming no loaded entry, or malformed, is ignored (the entry stays legacy). */
    private fun readProvenance(file: File) {
        try {
            if (!file.isFile) return
            for (line in file.readLines(Charsets.UTF_8)) {
                val f = line.split('\t')
                if (f.size != 4) continue
                val map = when (f[0]) { "w" -> words; "p" -> phrases; else -> continue }
                val strength = Strength.of(f[3])?.takeIf { it != Strength.LEGACY } ?: continue
                map[f[1] to f[2]]?.strength = strength
            }
        } catch (e: Exception) {
            for (e2 in words.values + phrases.values) e2.strength = Strength.LEGACY
        }
    }

    /** `s` or `e`, a tab, one catalog entry, most recent first. Malformed, unknown, repeated or excess lines are dropped. */
    private fun readRecents(file: File) {
        try {
            if (!file.isFile || file.length() > MAX_RECENTS_BYTES) return
            for (line in file.readLines(Charsets.UTF_8)) {
                val f = line.split('\t')
                if (f.size != 2) continue
                val emoji = when (f[0]) { "s" -> false; "e" -> true; else -> continue }
                val list = if (emoji) recentEmoji else recentSymbols
                if (list.size < MAX_RECENTS && f[1] in Palettes.catalog(emoji) && f[1] !in list) list += f[1]
            }
        } catch (e: Exception) {
            recentSymbols.clear()
            recentEmoji.clear()
        }
    }

    private fun readEntries(file: File, into: LinkedHashMap<Pair<String, String>, Entry>) {
        try {
            if (!file.isFile) return
            val list = file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 4 || !validReading(f[0]) || !validSurface(f[1])) return@mapNotNull null
                Entry(f[0], f[1], f[2].toIntOrNull() ?: return@mapNotNull null, f[3].toLongOrNull() ?: return@mapNotNull null)
            }
            for (e in list.sortedBy { it.seq }) into[e.reading to e.surface] = e
        } catch (e: Exception) {
            into.clear()
        }
    }

    private fun write(file: File, lines: List<String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val WORDS = "words.tsv"
        private const val PHRASES = "phrases.tsv"
        private const val REGISTERED = "registered.tsv"
        private const val PROVENANCE = "provenance.tsv"
        private const val RECENTS = "palette-recents.tsv"
        /** Entries per palette's 履歴. */
        const val MAX_RECENTS = 32
        /** A larger file is not one we wrote (2 × 32 short lines): it is not read at all. */
        private const val MAX_RECENTS_BYTES = 8 * 1024L
        const val MAX_WORDS = 3000
        const val MAX_PHRASES = 1000
        const val MAX_COUNT = 1000
        const val MIN_PHRASE = 2
        const val MAX_PHRASE = 64

        /** Mozc cost units: enough to swap usual homophones (売っ/打っ), not to break segmentation. */
        const val LATEST_BONUS = 1500
        const val PER_COUNT_BONUS = 300
        const val OLDER_BONUS = 500
        const val REGISTERED_BONUS = 2500
        const val REGISTERED_COST = 3000

        /** Hiragana and ー only, as the converter takes. */
        fun validReading(s: String): Boolean = s.isNotEmpty() && s.length <= MAX_PHRASE && s.all { it.code in 0x3041..0x3096 || it == 'ー' }

        fun validSurface(s: String): Boolean = s.isNotBlank() && s.length <= MAX_PHRASE && s.none { it == '\t' || it == '\n' || it == '\r' }
    }
}

internal typealias Phrase = LearnedOrder.Phrase
internal typealias Strength = LearnedOrder.Strength

/** Immutable learning data for one conversion. */
internal class LearningSnapshot(
    val user: UserWords,
    private val phrases: List<Phrase>,
    /** Words the user registered by hand (reading to surface): explicit, so nothing overrules a phrase that uses one. */
    private val registered: Set<Pair<String, String>> = emptySet(),
    /** Learned words that count when the model ranks with context: explicit choices and registered words. */
    private val strongWords: Set<Pair<String, String>> = registered,
) {
    fun isStrong(word: Word): Boolean = (word.reading to word.surface) in strongWords

    /**
     * Places the phrase learned for exactly [text] by [LearnedOrder.place] (first, second, or where the ranking put it),
     * and up to [MAX_PREDICTIONS] longer phrases whose reading starts with [text] right after the first candidate.
     * [words] stays aligned with the candidates. [contextRanked]: the model ordered these candidates with context.
     * [outweighed] gets the phrase and the exact-reading first candidate (0.1.29 rule).
     */
    fun arrange(
        text: String,
        candidates: List<Candidate>,
        words: List<List<Word>>,
        contextRanked: Boolean = false,
        outweighed: (String, String) -> Boolean = { _, _ -> false },
    ): Pair<List<Candidate>, List<List<Word>>> {
        if (phrases.isEmpty()) return candidates to words
        val list = candidates.zip(words).toMutableList()
        fun take(surface: String) = list.indexOfFirst { it.first.surface == surface }.takeIf { it >= 0 }?.let { list.removeAt(it) }
        var firstPrediction = 1
        LearnedOrder.pick(text, phrases)?.let { p ->
            val listed = list.indexOfFirst { it.first.surface == p.surface }
            val others = list.filterIndexed { i, _ -> i != listed }
            val leader = others.firstOrNull()?.first?.takeIf { it.reading == text }
            val place = LearnedOrder.place(p, registered, contextRanked, leader != null) { outweighed(p.surface, leader!!.surface) }
            when {
                place == LearnedOrder.KEEP && listed >= 0 -> Unit
                place == LearnedOrder.KEEP -> list.add(minOf(1, list.size), Candidate(text, p.surface, -1.0) to emptyList())
                else -> {
                    val phrase = take(p.surface) ?: (Candidate(text, p.surface, -1.0) to emptyList())
                    if (place == 1) firstPrediction = 2
                    list.add(minOf(place, list.size), phrase)
                }
            }
        }
        if (text.length >= MIN_PREDICTION_INPUT) {
            val predictions = phrases.asSequence()
                .filter { it.reading.length > text.length && it.reading.startsWith(text) }
                .distinctBy { it.surface }
                .take(MAX_PREDICTIONS)
                .toList()
            var at = minOf(firstPrediction, list.size)
            for (p in predictions) {
                if (list.any { it.first.surface == p.surface }) continue
                list.add(at++, Candidate(p.reading, p.surface, -0.5) to emptyList())
            }
        }
        return list.map { it.first } to list.map { it.second }
    }

    companion object {
        const val MAX_PREDICTIONS = 3
        const val MIN_PREDICTION_INPUT = 2
    }
}
