import copy
import math
import unittest

from ime_poc import (BaselineConverter, BaselineCorrector, BaselineRanker, Candidate, Converter,
                     Corrector, Engine, Ranker, WeightedChannel)
from ime_poc.channel import (INDEL_COST, LIGHT_INDEL_COST, MOTOR_MAX_DISCOUNT, OTHER_SUB_COST,
                             SAME_ROW_COST, SAME_VOWEL_COST, TRANSPOSE_COST, VOICING_COST)
from ime_poc.engine import EDIT_WEIGHT, LANGUAGE_TOKEN_CAP, LANGUAGE_TOTAL_CAP, MAX_INPUT_CHARS, MAX_K

# Development fixture (seen while building the lexicon; not a blind holdout).
RAW = "きょうきょくいうと、かのくらいのごたいふならすいさつしてへんかんされてはしい"
READING = "きゅうきょくいうと、このくらいのごたいぷならすいさつしてへんかんされてほしい"
SURFACE = "究極言うと、このくらいの誤タイプなら推察して変換されてほしい"


def surfaces(conv):
    return [c.surface for c in conv.candidates]


def cost_of(conv, surface):
    return next(c.cost for c in conv.candidates if c.surface == surface)


class ChannelTest(unittest.TestCase):
    def setUp(self):
        self.ch = WeightedChannel()

    def test_identity_and_katakana(self):
        self.assertEqual(self.ch.distance("がっこう", "がっこう"), 0.0)
        self.assertEqual(self.ch.distance("パン", "ぱん"), 0.0)
        self.assertEqual(self.ch.distance("", ""), 0.0)

    def test_voicing(self):
        self.assertAlmostEqual(self.ch.distance("ふ", "ぷ"), VOICING_COST)
        self.assertAlmostEqual(self.ch.distance("か", "が"), VOICING_COST)
        self.assertAlmostEqual(self.ch.distance("よ", "ょ"), VOICING_COST)

    def test_same_row_vowel_and_other(self):
        self.assertAlmostEqual(self.ch.distance("か", "こ"), SAME_ROW_COST)
        self.assertAlmostEqual(self.ch.distance("ょ", "ゅ"), SAME_ROW_COST)
        self.assertAlmostEqual(self.ch.distance("か", "さ"), SAME_VOWEL_COST)
        self.assertAlmostEqual(self.ch.distance("か", "め"), OTHER_SUB_COST)

    def test_transposition(self):
        self.assertAlmostEqual(self.ch.distance("かしいゃ", "かいしゃ"), TRANSPOSE_COST)

    def test_insertion_and_deletion(self):
        self.assertAlmostEqual(self.ch.distance("がこう", "がっこう"), LIGHT_INDEL_COST)
        self.assertAlmostEqual(self.ch.distance("あめあ", "あめ"), INDEL_COST)
        self.assertAlmostEqual(self.ch.distance("あ", "あめ"), INDEL_COST)

    def test_touch_applies_at_observed_index(self):
        ch = WeightedChannel(touch_hints={1: {"め": 0.9}})
        self.assertAlmostEqual(ch.distance("あれ", "あめ"), -math.log(0.9))
        self.assertAlmostEqual(ch.distance("れ", "め", offset=1), -math.log(0.9))
        self.assertAlmostEqual(ch.distance("れ", "め"), SAME_VOWEL_COST)

    def test_touch_requires_useful_probabilities(self):
        for hints in ({1: {"め": 1.5}}, {1: {"め": 0.9, "る": 0.9}}, {1: {"め": float("nan")}},
                      {-1: {"め": 0.9}}, {1: {"め": 0.01}}, {1: {"め": True}}):
            with self.subTest(hints=hints):
                ch = WeightedChannel(touch_hints=hints)
                self.assertAlmostEqual(ch.distance("あれ", "あめ"), SAME_VOWEL_COST)

    def test_motor_counts_capped_directional_not_mutated(self):
        counts = {("ぺ", "べ"): 10 ** 9}
        snapshot = dict(counts)
        ch = WeightedChannel(motor_counts=counts)
        boosted = ch.distance("ぺ", "べ")
        self.assertLess(boosted, VOICING_COST)
        self.assertAlmostEqual(boosted, VOICING_COST * (1 - MOTOR_MAX_DISCOUNT))
        self.assertAlmostEqual(ch.distance("べ", "ぺ"), VOICING_COST)
        self.assertEqual(counts, snapshot)

    def test_costs_nonnegative(self):
        ch = WeightedChannel(touch_hints={0: {"あ": 1.0}}, motor_counts={("か", "が"): 10 ** 12})
        chars = "かがあぁっーんA😀"
        for o in chars:
            for i in chars:
                self.assertGreaterEqual(ch.distance(o, i), 0.0)


class EngineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = Engine()

    def check(self, conv, text, k=10):
        self.assertLessEqual(len(conv.candidates), k)
        self.assertLessEqual(len(conv.readings), k)
        self.assertIn(text, surfaces(conv))
        self.assertIn(text, [c.reading for c in conv.readings])
        for c in conv.readings + conv.candidates:
            self.assertGreaterEqual(c.cost, 0.0)
        for c in conv.readings:
            self.assertEqual(c.reading, c.surface)

    def assertTop(self, text, expected, **kw):
        conv = self.engine.convert(text, **kw)
        self.check(conv, text, kw.get("k", 10))
        self.assertEqual(conv.candidates[0].surface, expected, surfaces(conv))
        return conv

    def test_representative_top1(self):
        conv = self.assertTop(RAW, SURFACE)
        # Generation recall and post-conversion Top-1 are separate contracts.
        self.assertIn(READING, [c.reading for c in conv.readings])

    def test_representative_k2_keeps_identity(self):
        conv = self.assertTop(RAW, SURFACE, k=2)
        self.assertEqual(len(conv.candidates), 2)

    def test_composed_clean_sentences(self):
        # Newly composed from the same vocabulary; not blind holdouts.
        for text, expected in [
            ("あしたはがっこうにいく", "明日は学校に行く"),
            ("きょうはあめです", "今日は雨です"),
            ("このぶんしょうをへんかんしてほしい", "この文章を変換してほしい"),
            ("でんしゃでかいしゃにいく", "電車で会社に行く"),
            ("ぱんをたべる", "パンを食べる"),
            ("しりょうをかくにんする", "資料を確認する"),
            ("あしたはびょういんにいく", "明日は病院に行く"),
            ("かいぎのじかんをかくにんする", "会議の時間を確認する"),
        ]:
            with self.subTest(text=text):
                self.assertTop(text, expected)

    def test_composed_noisy_sentences(self):
        for text, expected in [
            ("あしたはがっこおにいく", "明日は学校に行く"),        # substitution (same row)
            ("ぱんをたぺる", "パンを食べる"),                      # substitution (voicing)
            ("あしたはがこうにいく", "明日は学校に行く"),          # deletion
            ("しりょうをかくにんんする", "資料を確認する"),        # insertion
            ("でんしゃでかしいゃにいく", "電車で会社に行く"),      # transposition
        ]:
            with self.subTest(text=text):
                self.assertTop(text, expected)

    def test_legitimate_competitors_not_overcorrected(self):
        for text, expected in [
            ("はいしゃにいく", "歯医者に行く"),
            ("かいしゃにいく", "会社に行く"),
            ("きょうはきょくをきく", "今日は曲を聞く"),
            ("かれはがっこうにいく", "彼は学校に行く"),
        ]:
            with self.subTest(text=text):
                self.assertTop(text, expected)

    def test_oov_emoji_mixed_and_katakana(self):
        self.assertTop("😀", "😀")
        conv = self.assertTop("ABCがっこう😀", "ABC学校😀")
        for s in surfaces(conv):
            self.assertTrue(s.startswith("ABC") and s.endswith("😀"), s)
        self.assertTop("パンをたべる", "パンを食べる")

    def test_k_and_beam_validation(self):
        for bad in (1, 0, -3, MAX_K + 1):
            with self.assertRaises(ValueError):
                self.engine.convert("あめ", k=bad)
        for bad in (2.5, True, "3"):
            with self.assertRaises(TypeError):
                self.engine.convert("あめ", k=bad)
        with self.assertRaises(ValueError):
            Engine(beam_width=0)
        with self.assertRaises(TypeError):
            Engine(beam_width="8")
        with self.assertRaises(TypeError):
            self.engine.convert(None)
        self.check(Engine(beam_width=1).convert(RAW, k=2), RAW, k=2)

    def test_long_and_empty_input(self):
        long_text = "あ" * (MAX_INPUT_CHARS + 1)
        conv = self.engine.convert(long_text)
        self.assertEqual(conv.candidates, (Candidate(long_text, long_text, 0.0),))
        self.assertEqual(conv.readings, conv.candidates)
        edge = "がっこう" * (MAX_INPUT_CHARS // 4)
        conv = self.engine.convert(edge)
        self.check(conv, edge)
        self.assertEqual(conv.candidates[0].surface, "学校" * (MAX_INPUT_CHARS // 4))
        empty = self.engine.convert("")
        self.assertEqual(empty.candidates, (Candidate("", "", 0.0),))
        self.assertEqual(empty.readings, (Candidate("", "", 0.0),))

    def test_sensitive_bypass(self):
        conv = self.engine.convert(RAW, sensitive=True, touch_hints={1: {"ゅ": 0.9}},
                                   motor_counts={("ふ", "ぷ"): 5}, language_counts={"究極": 5})
        self.assertEqual(conv.candidates, (Candidate(RAW, RAW, 0.0),))
        self.assertEqual(conv.readings, (Candidate(RAW, RAW, 0.0),))

    def test_touch_evidence_changes_candidates(self):
        hints = {1: {"め": 0.9}}
        snapshot = copy.deepcopy(hints)
        plain = self.engine.convert("あれ")
        touched = self.engine.convert("あれ", touch_hints=hints)
        self.assertNotIn("雨", surfaces(plain))
        self.assertEqual(touched.candidates[0].surface, "雨")
        self.check(touched, "あれ")
        self.assertEqual(hints, snapshot)

    def test_motor_counts_capped_and_not_mutated(self):
        counts = {("ぺ", "べ"): 40}
        snapshot = dict(counts)
        plain = self.engine.convert("ぱんをたぺる")
        boosted = self.engine.convert("ぱんをたぺる", motor_counts=counts)
        drop = cost_of(plain, "パンを食べる") - cost_of(boosted, "パンを食べる")
        self.assertGreater(drop, 0.0)
        self.assertLessEqual(drop, EDIT_WEIGHT * VOICING_COST * MOTOR_MAX_DISCOUNT + 1e-9)
        self.assertEqual(counts, snapshot)

    def test_language_counts_capped_and_not_mutated(self):
        counts = {"飴": 10 ** 9}
        snapshot = dict(counts)
        plain = self.engine.convert("あめ")
        boosted = self.engine.convert("あめ", language_counts=counts)
        drop = cost_of(plain, "飴") - cost_of(boosted, "飴")
        self.assertGreater(drop, 0.0)
        self.assertLessEqual(drop, LANGUAGE_TOKEN_CAP + 1e-9)
        self.assertEqual(boosted.candidates[0].surface, "雨")
        self.assertEqual(counts, snapshot)
        baseline = self.engine.convert("きょうきょく")
        huge = self.engine.convert("きょうきょく", language_counts={"今日": 10 ** 9, "曲": 10 ** 9})
        # Without context, 今日曲 is a legitimate competing reading. Test the
        # adaptation bound, rather than require one meaning regardless of history.
        self.assertLessEqual(cost_of(baseline, "今日曲") - cost_of(huge, "今日曲"), LANGUAGE_TOTAL_CAP)
        self.assertEqual(baseline.readings, huge.readings)

    def test_alternative_segmentation_pays_its_own_cost(self):
        from ime_poc import LexEntry, Lexicon, ReadingHypothesis, Token
        lex = Lexicon([LexEntry("あめ", "雨", 2.0, "noun")])
        cheap = (Token(0, 2, "あめ", "あめ", "lex", 2.0),)
        costly = (Token(0, 1, "あ", "あ", "oov", 3.0), Token(1, 2, "め", "め", "oov", 3.0))
        candidates = BaselineConverter(lex).convert(ReadingHypothesis("あめ", 2.0, (cheap, costly)), 10)
        self.assertEqual({c.surface: c.cost for c in candidates}, {"雨": 2.0, "あめ": 6.0})

    def test_negation_retains_suru_stem(self):
        # Regression found during synthetic evaluation; no sentence-specific entry.
        for text, expected in [("へんかんしない", "変換しない"), ("かくにんしない", "確認しない")]:
            with self.subTest(text=text):
                result = self.assertTop(text, expected)
                self.assertEqual(result.candidates[0].reading, text)

    def test_extreme_counts_are_bounded(self):
        ch = WeightedChannel(motor_counts={("ふ", "ぷ"): 10 ** 1000}, touch_hints={0: {"ぷ": 10 ** 1000}})
        self.assertTrue(math.isfinite(ch.distance("ふ", "ぷ")))
        result = self.engine.convert("あめ", language_counts={"飴": 10 ** 1000})
        self.assertTrue(all(math.isfinite(c.cost) for c in result.candidates))

    def test_generation_invariant_to_language_counts(self):
        plain = self.engine.convert(RAW)
        counted = self.engine.convert(RAW, language_counts={"今日": 10 ** 6, "曲": 10 ** 6, "彼": 99})
        self.assertEqual(plain.readings, counted.readings)

    def test_component_injection(self):
        lexicon = self.engine.lexicon
        self.assertIsInstance(BaselineCorrector(lexicon), Corrector)
        self.assertIsInstance(BaselineConverter(lexicon), Converter)
        self.assertIsInstance(BaselineRanker(), Ranker)

        class ShortestFirst:
            def rank(self, candidates, language_counts):
                return sorted(candidates, key=lambda c: (len(c.surface), c.cost))

        conv = Engine(ranker=ShortestFirst()).convert("あめ", k=3)
        self.assertEqual(len(conv.candidates[0].surface), 1)
        self.assertIn("あめ", surfaces(conv))


if __name__ == "__main__":
    unittest.main()
