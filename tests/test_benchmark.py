import unittest
from types import SimpleNamespace as NS
from ime_poc.benchmark import aggregate, metrics, options, percentile


class MetricTests(unittest.TestCase):
    def row(self, clean=False):
        return dict(id="fixture", split="clean" if clean else "test", kind="unit",
                    input="かな" if clean else "かに", reading="かな", surfaces=["仮名"])

    def test_nearest_rank(self):
        self.assertEqual(percentile(list(range(1, 21)), .95), 19)
        self.assertEqual(percentile([1, 2, 3, 4], .5), 2)
        for values, p in [([], .5), ([1], 0), ([1], 2)]:
            with self.assertRaises(ValueError):
                percentile(values, p)

    def test_generation_miss_ranking_hit_are_separate(self):
        result = NS(readings=[NS(reading="かに")], candidates=[NS(reading="かな", surface="仮名")])
        m = metrics(result, self.row())
        self.assertFalse(m["reading_recall@10"])
        self.assertTrue(m["top1_exact"])
        self.assertIsNone(m["overcorrected"])

    def test_ranking_miss_not_generation_miss(self):
        result = NS(readings=[NS(reading="かな")], candidates=[NS(reading="かな", surface="かな"), NS(reading="かな", surface="仮名")])
        m = metrics(result, self.row())
        self.assertEqual(m["reading_rank"], 1)
        self.assertEqual(m["surface_rank"], 2)
        self.assertFalse(m["top1_exact"])

    def test_kanji_conversion_is_not_reading_overcorrection(self):
        result = NS(readings=[NS(reading="かな")], candidates=[NS(reading="かな", surface="仮名")])
        m = metrics(result, self.row(clean=True))
        self.assertFalse(m["overcorrected"])
        a = aggregate([m])
        self.assertEqual(a["overcorrection"], dict(numerator=0, denominator=1, rate=0))
        self.assertEqual(a["top1_exact"]["denominator"], 1)

    def test_clean_reading_change_and_empty_output(self):
        result = NS(readings=[], candidates=[NS(reading="かに", surface="蟹")])
        self.assertTrue(metrics(result, self.row(True))["overcorrected"])
        self.assertTrue(metrics(NS(readings=[], candidates=[]), self.row())["empty_output"])

    def test_options_never_read_gold(self):
        row = {"touch_hints": {"0": {"は": .4, "ほ": .6}}}
        a = options(row, "D")
        b = options({**row, "reading": "SECRET_GOLD", "surfaces": ["SECRET_GOLD"]}, "D")
        self.assertEqual(a, b)
        self.assertEqual(options(row, "A"), {})
        a["touch_hints"][0]["ほ"] = 0
        self.assertEqual(row["touch_hints"]["0"]["ほ"], .6)


if __name__ == "__main__":
    unittest.main()
