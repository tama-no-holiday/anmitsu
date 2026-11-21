package dev.astra.ime.oss

import dev.astra.ime.oss.zenz.ZenzRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ranking formula with a stand-in scorer; the real zenz model is only exercised on a device. */
class ZenzRankingTest {
    private val typed = "ぼーるをうったことがある。ばっとで"

    @Test
    fun laterContextCanPromoteAnEarlierWord() {
        val candidates = listOf("ボールを売ったことがある。バットで", "ボールを打ったことがある。バットで", typed)
        val readings = listOf(typed, typed, typed)
        val prompts = ArrayList<String>()
        val order = ZenzRanking.rerank(typed, candidates, readings, maxScored = 8) { context, reading, list ->
            prompts += "$context|$reading"
            FloatArray(list.size) { i -> if (list[i].contains("打った")) -3f else -8f }
        }
        assertEquals(listOf(1, 0, 2), order)
        // One prompt for candidates sharing a reading, in katakana, without context when there is no head.
        assertEquals(listOf("|ボールヲウッタコトガアル。バットデ"), prompts)
    }

    @Test
    fun theContextOptionsActOnlyOnAnExternalContextNeverOnAHeadOrRepairedUnits() {
        val typed = "きのうはあめだった。かき"
        val head = ZenzRanking.Head(typedLength = 10, surface = "昨日は雨だった。", reading = "きのうはあめだった。")
        val candidates = listOf("昨日は雨だった。下記", "昨日は雨だった。夏季", "昨日は雨だった。火器", "昨日は雨だった。柿")
        val readings = List(4) { typed }
        val scorer = ZenzRanking.Scorer { _, _, list -> FloatArray(list.size) { i -> if (list[i] == "柿") 0f else -2f } }
        val options = listOf(
            ZenzRanking.Weights(contextPerRank = 0.0),
            ZenzRanking.Weights(contextMargin = 1.0),
            ZenzRanking.Weights(contextPerRank = 0.0, contextMargin = 0.0),
        )
        val shipped = ZenzRanking.rerank(typed, candidates, readings, 8, head = head, leftContext = "打った", scorer = scorer)
        assertEquals(listOf(0, 1, 3, 2), shipped)
        for (weights in options) {
            // A head and a repaired unit are left context, but only the caller's flag says the editor's text is there.
            assertEquals(shipped, ZenzRanking.rerank(typed, candidates, readings, 8, weights, head = head, leftContext = "打った", scorer = scorer))
            assertEquals(3, ZenzRanking.rerank(typed, candidates, readings, 8, weights, head = head, external = true, scorer = scorer)!![0])
        }
        // Without options the flag changes nothing.
        assertEquals(shipped, ZenzRanking.rerank(typed, candidates, readings, 8, head = head, leftContext = "前の文。", external = true, scorer = scorer))
    }

    @Test
    fun theMarginMovesOnlyAnExactReadingAndOnlyPastItsThreshold() {
        val candidates = listOf("雨", "飴", "天", "網")
        val readings = listOf("あめ", "あめ", "あめ", "あま")
        fun order(margin: Double, learned: (Int) -> Boolean = { false }) = ZenzRanking.rerank(
            "あめ", candidates, readings, 8, ZenzRanking.Weights(contextMargin = margin), external = true, learned = learned,
        ) { _, reading, list -> FloatArray(list.size) { i -> if (reading == "アマ") 0f else if (list[i] == "天") -1f else -3f } }!!
        // 天 is 2 nats over 雨 and third, so the rank prior alone keeps 雨 first. 網 scores best but needs an edit.
        assertEquals(0, order(3.0)[0])
        assertEquals(listOf(2, 0, 1, 3), order(2.0).take(4))
        assertEquals(setOf(0, 1, 2, 3), order(2.0).toSet())
        // A learned first candidate keeps its bonus in the comparison.
        assertEquals(0, order(2.0) { it == 0 }[0])
    }

    @Test
    fun headIsContextAndOnlyTheTailIsScored() {
        val typed = "きのうはあめだった。きょうははれ"
        val head = ZenzRanking.Head(typedLength = 10, surface = "昨日は雨だった。", reading = "きのうはあめだった。")
        val candidates = listOf("昨日は雨だった。今日は晴れ", "昨日は雨だった。今日は腫れ", typed)
        val readings = listOf(typed, typed, typed)
        val seen = ArrayList<String>()
        val order = ZenzRanking.rerank(typed, candidates, readings, 8, head = head) { context, reading, list ->
            seen += "$context|$reading|${list.joinToString(",")}"
            FloatArray(list.size) { i -> if (list[i] == "今日は腫れ") 0.5f else -1f }
        }
        // The raw text does not start with the head, so it is not scored and stays after the scored ones.
        assertEquals(listOf(1, 0, 2), order)
        assertEquals(listOf("昨日は雨だった。|キョウハハレ|今日は晴れ,今日は腫れ"), seen)
        // Too long a tail: no reranking.
        assertNull(ZenzRanking.rerank(typed, candidates, readings, 8, head = head, maxTailChars = 3) { _, _, l -> FloatArray(l.size) })
    }

    @Test
    fun leftContextPrecedesTheHeadAndIsNeverScored() {
        // A repaired unit before the converted text (e.g. 打った kept while あめ is reconverted) is context only.
        val seen = ArrayList<String>()
        val candidates = listOf("雨", "飴", "あめ")
        ZenzRanking.rerank("あめ", candidates, List(3) { "あめ" }, 8, leftContext = "ボールを打った") { context, reading, list ->
            seen += "$context|$reading|${list.size}"
            FloatArray(list.size)
        }
        assertEquals(listOf("ボールを打った|アメ|3"), seen)
        val long = "あ".repeat(ZenzRanking.CONTEXT_CHARS + 5)
        ZenzRanking.rerank("あめ", candidates, List(3) { "あめ" }, 8, leftContext = long) { context, _, list ->
            assertEquals(ZenzRanking.CONTEXT_CHARS, context.length)
            FloatArray(list.size)
        }
    }

    @Test
    fun promptContextKeepsTheLastCodePointsOfOrdinaryTextOnly() {
        assertEquals("", ZenzRanking.promptContext(""))
        assertEquals("私は歯医者です。", ZenzRanking.promptContext("私は歯医者です。"))
        // Prompt marks, line breaks, tabs and invisible marks cannot reach the prompt; literal "</s>" is plain text.
        assertEquals("前文後</s>", ZenzRanking.promptContext("前文\n\r\t  ​﻿後</s>"))
        // Counted in code points, kept pairs whole, cut pairs dropped from either end.
        val emoji = "😀".repeat(ZenzRanking.CONTEXT_CHARS + 3)
        assertEquals("😀".repeat(ZenzRanking.CONTEXT_CHARS), ZenzRanking.promptContext(emoji))
        assertEquals("a", ZenzRanking.promptContext("\uDE00a\uD83D"))
        // The bound applies after sanitizing, so filler cannot crowd out the text.
        assertEquals("あ".repeat(ZenzRanking.CONTEXT_CHARS), ZenzRanking.promptContext("あ".repeat(ZenzRanking.CONTEXT_CHARS) + "\n".repeat(50)))
    }

    @Test
    fun aHeadAfterTheCommittedContextIsNotDuplicated() {
        val seen = ArrayList<String>()
        val candidates = listOf("今日は雨", "今日は飴")
        val head = ZenzRanking.Head(3, "今日は", "きょうは")
        ZenzRanking.rerank("きょうはあめ", candidates, List(2) { "きょうはあめ" }, 8, head = head, leftContext = "前の文。") { context, _, list ->
            seen += "$context|${list.joinToString(",")}"
            FloatArray(list.size)
        }
        assertEquals(listOf("前の文。今日は|雨,飴"), seen)
    }

    @Test
    fun correctionsPayPerEditAndRankPriorBreaksTies() {
        val typed = "へんかんしてはしい"
        val candidates = listOf("変換してはしい", "変換してほしい")
        val readings = listOf(typed, "へんかんしてほしい")
        // Second place pays 1 (rank) + 2 (one edit): a 3.5-nat gain wins, a 1.5-nat gain does not.
        val wins = ZenzRanking.rerank(typed, candidates, readings, 8) { _, _, list ->
            FloatArray(list.size) { i -> if (list[i] == "変換してほしい") -1.5f else -5f }
        }
        assertEquals(listOf(1, 0), wins)
        val loses = ZenzRanking.rerank(typed, candidates, readings, 8) { _, _, list ->
            FloatArray(list.size) { i -> if (list[i] == "変換してほしい") -3.5f else -5f }
        }
        assertEquals(listOf(0, 1), loses)
    }

    @Test
    fun aLearnedWordOutweighsAMildContextPreference() {
        val typed = "ぼーるうった"
        val candidates = listOf("ボール売った", "ボール打った")
        val readings = listOf(typed, typed)
        // zenz prefers 売った by 2 nats; the user chose 打った before (+3), which overcomes it and the rank prior (1).
        val scorer = ZenzRanking.Scorer { _, _, list -> FloatArray(list.size) { i -> if (list[i] == "ボール売った") -4f else -6f } }
        assertEquals(listOf(0, 1), ZenzRanking.rerank(typed, candidates, readings, 8, scorer = scorer))
        val learned = ZenzRanking.rerank(typed, candidates, readings, 8, learned = { it == 1 }, scorer = scorer)
        // -6 - 1 + 3 = -4 ties -4: equal scores keep the dictionary order, so the gap must exceed it.
        assertEquals(listOf(0, 1), learned)
        val stronger = ZenzRanking.rerank(typed, candidates, readings, 8, learned = { it == 1 }) { _, _, list ->
            FloatArray(list.size) { i -> if (list[i] == "ボール売った") -4f else -5.5f }
        }
        assertEquals(listOf(1, 0), stronger)
    }

    @Test
    fun onlyTheFirstCandidatesAreReorderedAndFailureKeepsDictionaryOrder() {
        val candidates = listOf("a", "b", "c", "d")
        val order = ZenzRanking.rerank("x", candidates, List(4) { "x" }, maxScored = 2) { _, _, list ->
            FloatArray(list.size) { i -> if (list[i] == "b") 0f else -10f }
        }
        assertEquals(listOf(1, 0, 2, 3), order)
        assertNull(ZenzRanking.rerank("x", candidates, List(4) { "x" }, 4) { _, _, _ -> null })
    }

    private val limits = ZenzRanking.Weights(phraseGap = 3.0, phraseShift = 1.5)

    /** Each context answers the leader's lead over the phrase, in nats; the prompts asked are in [asked]. */
    private fun leads(asked: MutableList<String>, vararg lead: Pair<String, Float>) = ZenzRanking.Scorer { context, _, _ ->
        asked += context
        floatArrayOf(lead.toMap().getValue(context), 0f)
    }

    private fun outweighs(scorer: ZenzRanking.Scorer, weights: ZenzRanking.Weights = limits, context: String = "前", plain: String = "") =
        ZenzRanking.contextOutweighs("かな", "learned", "leader", context, plain, weights, scorer)

    @Test
    fun contextOutweighsAPhraseOnlyWhenItIsTheReasonTheLeaderLeads() {
        assertEquals(true, outweighs(leads(ArrayList(), "前" to 6.28f, "" to 4.53f)))
        assertEquals(true, outweighs(leads(ArrayList(), "前" to 3f, "" to 1.5f)))
        // The model finds the phrase rare with or without the sentence: it stays the user's choice.
        assertEquals(false, outweighs(leads(ArrayList(), "前" to 9.4f, "" to 9.9f)))
        assertEquals(false, outweighs(leads(ArrayList(), "前" to 6f, "" to 4.6f)))
        // A shift too small to overrule a choice, as with 1.49 nats for the other surface.
        assertEquals(false, outweighs(leads(ArrayList(), "前" to 2.9f, "" to -6f)))
    }

    @Test
    fun theSecondPromptRunsOnlyWhenTheFirstPasses() {
        val asked = ArrayList<String>()
        assertEquals(false, outweighs(leads(asked, "前" to 1f)))
        assertEquals(listOf("前"), asked)
        asked.clear()
        assertEquals(true, outweighs(leads(asked, "前" to 6f, "" to 1f)))
        assertEquals(listOf("前", ""), asked)
    }

    @Test
    fun nothingToCompareOrNoScoresLeaveThePhrase() {
        val asked = ArrayList<String>()
        assertEquals(false, outweighs(leads(asked), weights = ZenzRanking.Weights()))
        assertEquals(false, outweighs(leads(asked), context = "前", plain = "前"))
        assertEquals(false, outweighs(leads(asked), context = "​", plain = ""))
        assertEquals(false, ZenzRanking.contextOutweighs("かな", "same", "same", "前", "", limits, leads(asked)))
        assertEquals(emptyList<String>(), asked)
        assertEquals(false, outweighs({ _, _, _ -> null }))
        var calls = 0
        assertEquals(false, outweighs({ _, _, _ -> if (calls++ == 0) floatArrayOf(9f, 0f) else null }))
    }

    @Test
    fun thePhraseIsScoredForTheWholeReadingBesideItsRival() {
        var seen: Triple<String, String, List<String>>? = null
        outweighs({ context, reading, list -> seen = Triple(context, reading, list); floatArrayOf(0f, 0f) })
        assertEquals(Triple("前", "カナ", listOf("leader", "learned")), seen)
    }
}
