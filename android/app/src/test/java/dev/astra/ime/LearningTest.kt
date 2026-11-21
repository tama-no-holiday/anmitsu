package dev.astra.ime

import dev.astra.ime.core.Candidate
import dev.astra.ime.oss.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class LearningTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private fun store() = LearningStore(temp.root, direct).also { it.load() }

    @Test
    fun paletteHistoryIsBoundedDeduplicatedCatalogOnlyAndSurvivesARestart() {
        val s = store()
        val symbols = Palettes.symbols.flatMap { it.items }
        symbols.take(40).forEach { s.useEntry(false, it) }
        assertEquals(LearningStore.MAX_RECENTS, s.recent(false).size)
        assertEquals(symbols.take(40).reversed().take(LearningStore.MAX_RECENTS), s.recent(false)) // most recent first
        s.useEntry(false, symbols[39])
        s.useEntry(false, symbols[20]) // used again: moves to the front, still once
        assertEquals(symbols[20], s.recent(false).first())
        assertEquals(1, s.recent(false).count { it == symbols[20] })
        // Only catalog entries, each in its own palette: no free text, no clipboard, no emoji among the symbols.
        s.useEntry(false, "password123")
        s.useEntry(false, "😀")
        s.useEntry(true, "→")
        val flag = Palettes.emoji.last().items.last()
        s.useEntry(true, flag)
        assertEquals(listOf(flag), s.recent(true))
        assertFalse("password123" in s.recent(false) || "😀" in s.recent(false))
        // Persisted on the device and read back as it was.
        val again = store()
        assertEquals(s.recent(false), again.recent(false))
        assertEquals(listOf(flag), again.recent(true))
        // 学習内容を消去する clears it with the learned words; registered words stay.
        again.register("あんみつ", "anmitsu")
        again.clearHistory()
        assertTrue(again.recent(false).isEmpty() && again.recent(true).isEmpty())
        assertTrue(store().recent(false).isEmpty())
        assertEquals(1, store().registeredWords().size)
    }

    @Test
    fun aDamagedOrForeignHistoryFileIsReadSafely() {
        val file = java.io.File(temp.root, "palette-recents.tsv")
        val lines = listOf("s\t→", "s\t→", "x\t※", "s", "s\tfree text", "e\t→", "e\t😀", "s\t※\textra") +
            Palettes.symbols.flatMap { it.items }.map { "s\t$it" }
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        val s = store()
        assertEquals("→", s.recent(false).first())
        assertEquals(LearningStore.MAX_RECENTS, s.recent(false).size)
        assertEquals(s.recent(false).distinct(), s.recent(false))
        assertEquals(listOf("😀"), s.recent(true))
        // A file far larger than any we write is not read at all; binary noise is no crash.
        file.writeText("s\t→\n" + "x".repeat(20_000), Charsets.UTF_8)
        assertTrue(store().recent(false).isEmpty())
        file.writeBytes(ByteArray(64) { (it * 37).toByte() })
        assertTrue(store().recent(false).isEmpty())
    }

    @Test
    fun theLatestChoiceForAReadingGetsTheLargestBonus() {
        val s = store()
        val sold = Word("うっ", "売っ")
        val hit = Word("うっ", "打っ")
        // Accepting the default does not add its words; picking a variant does.
        s.learn("ぼーるをうった", "ボールを売った", listOf(Word("ぼーる", "ボール"), sold), defaultWords = listOf(Word("ぼーる", "ボール"), sold))
        assertEquals(0, s.snapshot().user.bonus("うっ", "売っ"))
        s.learn("ぼーるをうった", "ボールを打った", listOf(Word("ぼーる", "ボール"), hit), defaultWords = listOf(Word("ぼーる", "ボール"), sold))
        val user = s.snapshot().user
        assertEquals(LearningStore.LATEST_BONUS, user.bonus("うっ", "打っ"))
        assertEquals(0, user.bonus("ぼーる", "ボール"))
        // Choosing 売っ later makes it the latest; 打っ keeps a smaller bonus.
        s.learn("うった", "売った", listOf(sold, Word("た", "た")), defaultWords = listOf(hit, Word("た", "た")))
        val after = s.snapshot().user
        assertEquals(LearningStore.LATEST_BONUS, after.bonus("うっ", "売っ"))
        assertEquals(LearningStore.OLDER_BONUS, after.bonus("うっ", "打っ"))
        assertTrue(after.version != user.version)
    }

    @Test
    fun phrasesComeFirstForTheSameReadingAndPredictLongerOnes() {
        val s = store()
        s.learn("ぼーるうった", "ボール打った", emptyList())
        val snap = s.snapshot()
        val typed = listOf(Candidate("ぼーるう", "ボール鵜", 0.0), Candidate("ぼーるう", "ぼーるう", 1.0))
        val (predicted, words) = snap.arrange("ぼーるう", typed, listOf(listOf(Word("ぼーる", "ボール")), emptyList()))
        assertEquals(listOf("ボール鵜", "ボール打った", "ぼーるう"), predicted.map { it.surface })
        assertEquals("ぼーるうった", predicted[1].reading) // a prediction carries the reading it completes
        assertEquals(listOf(listOf(Word("ぼーる", "ボール")), emptyList(), emptyList()), words)

        val exact = listOf(Candidate("ぼーるうった", "ボール売った", 0.0), Candidate("ぼーるうった", "ボール打った", 1.0))
        val (first, _) = snap.arrange("ぼーるうった", exact, listOf(emptyList(), emptyList()))
        assertEquals(listOf("ボール打った", "ボール売った"), first.map { it.surface })
        // Too short to predict from.
        assertEquals(listOf("ぼ"), snap.arrange("ぼ", listOf(Candidate("ぼ", "ぼ", 0.0)), listOf(emptyList())).first.map { it.surface })
    }

    @Test
    fun anOutweighedPhraseIsSecondAndPredictionsFollowIt() {
        val s = store()
        s.learn("あめだまや", "飴玉屋", emptyList())
        s.learn("あめだま", "雨だま", emptyList())
        val list = listOf(Candidate("あめだま", "飴玉", 0.0), Candidate("あめだま", "あめ玉", 1.0))
        val words = listOf(listOf(Word("あめだま", "飴玉")), emptyList())
        var asked: Pair<String, String>? = null
        val (arranged, aligned) = s.snapshot().arrange("あめだま", list, words) { phrase, leader -> asked = phrase to leader; true }
        assertEquals("雨だま" to "飴玉", asked)
        assertEquals(listOf("飴玉", "雨だま", "飴玉屋", "あめ玉"), arranged.map { it.surface })
        assertEquals(words[0], aligned[0])
        assertEquals(listOf("雨だま", "飴玉屋", "飴玉", "あめ玉"), s.snapshot().arrange("あめだま", list, words).first.map { it.surface })
    }

    @Test
    fun onlyAnExactReadingCanTakeThePlaceOfAPhrase() {
        val s = store()
        s.learn("あめ", "飴", emptyList())
        val list = listOf(Candidate("あめ", "飴", 0.0), Candidate("あま", "尼", 1.0), Candidate("あめ", "雨", 2.0))
        var asked = 0
        val (arranged, _) = s.snapshot().arrange("あめ", list, List(3) { emptyList() }) { _, _ -> asked++; true }
        assertEquals(0, asked) // the correction 尼 leads what remains, and the model compares readings only like for like
        assertEquals(listOf("飴", "尼", "雨"), arranged.map { it.surface })
    }

    @Test
    fun nothingOverrulesAPhraseThatUsesARegisteredWord() {
        val s = store()
        s.register("こーでっくす", "Codex")
        s.learn("こーでっくすです", "Codexです", emptyList())
        val list = listOf(Candidate("こーでっくすです", "コーデックスです", 0.0))
        val (arranged, _) = s.snapshot().arrange("こーでっくすです", list, listOf(emptyList())) { _, _ -> true }
        assertEquals(listOf("Codexです", "コーデックスです"), arranged.map { it.surface })
    }

    // --- Provenance (0.1.31) ----------------------------------------------------------------------

    private val dentist = listOf(Candidate("しかいをつとめます", "司会を務めます", 0.0), Candidate("しかいをつとめます", "歯科医を務めます", 1.0))
    private val noWords = List(2) { emptyList<Word>() }

    private fun first(s: LearningStore, list: List<Candidate> = dentist, contextRanked: Boolean = false) =
        s.snapshot().arrange("しかいをつとめます", list, noWords, contextRanked = contextRanked).first.map { it.surface }

    @Test
    fun acceptingTheRankingNeverPinsItOrReinforcesItsWords() {
        val s = store()
        val word = Word("しかい", "歯科医")
        val reversed = dentist.reversed()
        // The model put 歯科医 first (a dentist sentence); accepted five times, implicitly.
        repeat(5) { s.learn("しかいをつとめます", "歯科医を務めます", listOf(word), defaultWords = listOf(word), explicit = false) }
        assertEquals(0, s.snapshot().user.bonus("しかい", "歯科医"))
        assertEquals(listOf("司会を務めます", "歯科医を務めます"), first(s)) // no pin without context either
        assertEquals(listOf("歯科医を務めます", "司会を務めます"), first(s, reversed)) // where the ranking puts it
        // Explicitly chosen once: first, and its word is strong.
        s.learn("しかいをつとめます", "歯科医を務めます", listOf(word), defaultWords = listOf(Word("しかい", "司会")), explicit = true)
        assertEquals(listOf("歯科医を務めます", "司会を務めます"), first(s))
        assertTrue(s.snapshot().isStrong(word))
        // Later implicit acceptances of another surface do not undo an explicit choice, and do not refresh it.
        s.learn("しかいをつとめます", "司会を務めます", emptyList(), explicit = false)
        assertEquals(listOf("歯科医を務めます", "司会を務めます"), first(s))
        // An explicit correction to another surface replaces it.
        s.learn("しかいをつとめます", "司会を務めます", listOf(Word("しかい", "司会")), defaultWords = listOf(word), explicit = true)
        assertEquals(listOf("司会を務めます", "歯科医を務めます"), first(s, reversed))
    }

    @Test
    fun oldHistoryIsKeptAsLegacyAndNeverBecomesExplicitByItself() {
        java.io.File(temp.root, "phrases.tsv").writeText("しかいをつとめます\t歯科医を務めます\t7\t3\n", Charsets.UTF_8)
        java.io.File(temp.root, "words.tsv").writeText("しかい\t歯科医\t7\t2\n", Charsets.UTF_8)
        val s = store()
        assertEquals(2, s.historySize)
        assertEquals(LearningStore.LATEST_BONUS + 3 * LearningStore.PER_COUNT_BONUS, s.snapshot().user.bonus("しかい", "歯科医"))
        assertFalse(s.snapshot().isStrong(Word("しかい", "歯科医")))
        assertEquals(listOf("歯科医を務めます", "司会を務めます"), first(s)) // as before 0.1.31 without context ranking
        assertEquals(listOf("司会を務めます", "歯科医を務めます"), first(s, contextRanked = true)) // the model's order with context
        // Accepting it does not strengthen it; the files keep the format older versions read.
        s.learn("しかいをつとめます", "歯科医を務めます", emptyList(), explicit = false)
        val reloaded = store()
        assertEquals(listOf("司会を務めます", "歯科医を務めます"), first(reloaded, contextRanked = true))
        assertEquals("しかいをつとめます\t歯科医を務めます\t7\t3", java.io.File(temp.root, "phrases.tsv").readText().trim())
    }

    @Test
    fun provenanceSurvivesARestartHoldsNoTextBeyondTheEntriesAndIsClearedWithHistory() {
        val s = store()
        s.learn("しかいをつとめます", "歯科医を務めます", listOf(Word("しかい", "歯科医")), explicit = true)
        s.learn("あめ", "雨", emptyList(), explicit = false)
        val lines = java.io.File(temp.root, "provenance.tsv").readLines()
        assertEquals(setOf("w\tしかい\t歯科医\te", "p\tしかいをつとめます\t歯科医を務めます\te", "p\tあめ\t雨\ti"), lines.toSet())
        val again = store()
        assertTrue(again.snapshot().isStrong(Word("しかい", "歯科医")))
        assertEquals(listOf("歯科医を務めます", "司会を務めます"), first(again, contextRanked = true))
        again.register("しかい", "史海")
        again.clearHistory()
        assertTrue(java.io.File(temp.root, "provenance.tsv").readText().isEmpty())
        assertEquals(listOf("しかい" to "史海"), again.registeredWords()) // registered words are never erased
        assertTrue(again.snapshot().isStrong(Word("しかい", "史海")))
    }

    @Test
    fun persistsRegistersAndClears() {
        val s = store()
        s.learn("あめ", "飴", listOf(Word("あめ", "飴")))
        assertTrue(s.register("こーでっくす", "Codex"))
        assertFalse(s.register("Codex", "こーでっくす")) // reading must be kana
        assertFalse(s.register("こーど", "a\tb"))

        val reloaded = store()
        assertEquals(2, reloaded.historySize) // one word + one phrase
        val user = reloaded.snapshot().user
        assertEquals(LearningStore.LATEST_BONUS, user.bonus("あめ", "飴"))
        assertEquals(listOf("Codex"), user.added("こーでっくす").map { it.surface })
        assertEquals(listOf("こーでっくす" to "Codex"), reloaded.registeredWords())

        reloaded.clearHistory()
        assertEquals(0, store().historySize)
        assertEquals(1, store().registeredWords().size) // registered words survive clearing history
        reloaded.unregister("こーでっくす", "Codex")
        assertTrue(store().registeredWords().isEmpty())
    }

    @Test
    fun kanaOnlyAndUnchangedCommitsAreNotLearned() {
        val s = store()
        s.learn("ありがとう", "ありがとう", listOf(Word("ありがとう", "ありがとう")))
        s.learn("", "x", emptyList())
        assertEquals(0, s.historySize)
    }
}
