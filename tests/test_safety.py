import io
import unittest
from contextlib import redirect_stdout
from unittest import mock

from ime_poc import Candidate, Conversion, Engine
from ime_poc.__main__ import main
from ime_poc.personalization import (MAX_COUNT, MAX_LANGUAGE_ENTRIES, MAX_MOTOR_ENTRIES,
                                     MAX_SURFACE_CHARS, EditorPolicy, Profile)
from ime_poc.session import Request, Session, run_request

NORMAL = EditorPolicy()
PASSWORD = EditorPolicy(sensitive=True)
PRIVATE = EditorPolicy(no_personalized_learning=True)


def fake(text, *surfaces):
    cands = tuple(Candidate(text, s, float(i)) for i, s in enumerate(surfaces + (text,)))
    return Conversion(cands, cands, {})


def cost_of(conv, surface):
    return next(c.cost for c in conv.candidates if c.surface == surface)


class ProfileTest(unittest.TestCase):
    def test_policy_blocks_all_changes(self):
        on, off = Profile(), Profile(learning_enabled=False)
        for profile, policy in ((off, NORMAL), (on, PASSWORD), (on, PRIVATE), (on, None)):
            with self.subTest(policy=policy, enabled=profile.learning_enabled):
                self.assertFalse(profile.observe_substitution("ぺ", "べ", policy=policy))
                self.assertFalse(profile.accept_word("資料", policy=policy))
                self.assertEqual((profile.motor_counts(), profile.language_counts()), ({}, {}))
                self.assertEqual(profile.generation, 0)

    def test_invalid_tokens_rejected(self):
        p = Profile()
        for o, i in (("ぺ", "ぺ"), ("ペ", "ぺ"), ("ぺぺ", "べ"), ("a", "b"), (None, "べ")):
            self.assertFalse(p.observe_substitution(o, i, policy=NORMAL))
        for s in ("", " 資料", "x" * (MAX_SURFACE_CHARS + 1), "資\n料", 5):
            self.assertFalse(p.accept_word(s, policy=NORMAL))
        self.assertEqual((p.motor_counts(), p.language_counts()), ({}, {}))

    def test_reset_clears_and_bumps_generation(self):
        p = Profile()
        self.assertTrue(p.observe_substitution("ぺ", "べ", policy=NORMAL))
        self.assertTrue(p.accept_word("資料", policy=NORMAL))
        p.reset()
        self.assertEqual((p.motor_counts(), p.language_counts(), p.generation), ({}, {}, 1))
        self.assertEqual(p.engine_kwargs(NORMAL), {"motor_counts": None, "language_counts": None})

    def test_count_and_entry_bounds(self):
        p = Profile()
        for _ in range(MAX_COUNT + 5):
            p.accept_word("資料", policy=NORMAL)
        self.assertEqual(p.language_counts(), {"資料": MAX_COUNT})
        for n in range(MAX_LANGUAGE_ENTRIES + 10):
            p.accept_word(f"語{n}", policy=NORMAL)
        self.assertEqual(len(p.language_counts()), MAX_LANGUAGE_ENTRIES)
        self.assertEqual(p.language_counts()["資料"], MAX_COUNT)
        kana = [chr(c) for c in range(ord("ぁ"), ord("ゖ") + 1)]
        for o in kana:
            for i in kana:
                p.observe_substitution(o, i, policy=NORMAL)
        self.assertEqual(len(p.motor_counts()), MAX_MOTOR_ENTRIES)

    def test_snapshots_isolated_and_repr_hides_contents(self):
        p = Profile()
        p.observe_substitution("か", "こ", policy=NORMAL)
        p.accept_word("秘密", policy=NORMAL)
        p.motor_counts()[("か", "こ")] = 99
        p.language_counts().clear()
        p.engine_kwargs(NORMAL)["language_counts"]["秘密"] = 10 ** 6
        self.assertEqual((p.motor_counts(), p.language_counts()), ({("か", "こ"): 1}, {"秘密": 1}))
        self.assertNotIn("秘密", repr(p))
        self.assertEqual(p.engine_kwargs(PASSWORD), {"sensitive": True})


class ProfileEngineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = Engine()

    def test_independent_updates_reach_engine(self):
        p = Profile()
        for _ in range(40):
            p.observe_substitution("ぺ", "べ", policy=NORMAL)
        plain = self.engine.convert("ぱんをたぺる")
        boosted = self.engine.convert("ぱんをたぺる", **p.engine_kwargs(NORMAL))
        self.assertLess(cost_of(boosted, "パンを食べる"), cost_of(plain, "パンを食べる"))
        for _ in range(5):
            p.accept_word("飴", policy=NORMAL)
        plain = self.engine.convert("あめ")
        boosted = self.engine.convert("あめ", **p.engine_kwargs(NORMAL))
        self.assertLess(cost_of(boosted, "飴"), cost_of(plain, "飴"))

    def test_run_request_honours_password_policy(self):
        p = Profile()
        p.accept_word("雨", policy=NORMAL)
        s = Session(p)
        s.start_editor(PASSWORD)
        s.edit("あめ")
        req = s.request()
        result = run_request(self.engine, p, req)
        self.assertEqual(result.candidates, (Candidate("あめ", "あめ", 0.0),))
        self.assertTrue(s.apply_result(req, result))


class SessionTest(unittest.TestCase):
    def setUp(self):
        self.profile = Profile()
        self.s = Session(self.profile)
        self.s.start_editor(NORMAL)
        self.s.edit("あめ")

    def state(self):
        return self.s.text, self.s.revision, self.s.candidates

    def test_reordered_arrivals_and_replay(self):
        old = self.s.request()
        self.s.edit("あめが")
        new = self.s.request()
        self.assertTrue(self.s.apply_result(new, fake("あめが", "雨が")))
        self.assertFalse(self.s.apply_result(old, fake("あめ", "雨")))
        self.assertFalse(self.s.apply_result(new, fake("あめが", "飴が")))
        self.assertEqual([c.surface for c in self.s.candidates], ["雨が", "あめが"])

    def test_superseded_request_same_revision(self):
        first, second = self.s.request(), self.s.request()
        self.assertFalse(self.s.apply_result(first, fake("あめ", "雨")))
        self.assertTrue(self.s.apply_result(second, fake("あめ", "雨")))

    def test_fabricated_and_cross_session_requests(self):
        real = self.s.request()
        forged = Request(real.session, real.revision, real.generation, real.text, real.policy)
        self.assertFalse(self.s.apply_result(forged, fake("あめ", "雨")))
        self.assertFalse(self.s.apply_result(None, fake("あめ", "雨")))
        other = Session(self.profile)
        other.start_editor(NORMAL)
        other.edit("あめ")
        self.assertFalse(self.s.apply_result(other.request(), fake("あめ", "雨")))
        self.assertTrue(self.s.apply_result(real, fake("あめ", "雨")))
        self.assertIsNone(self.s.choose(0, forged))

    def test_malformed_results_rejected(self):
        req = self.s.request()
        for bad in (None, "雨", Conversion((), (), {}),
                    Conversion((), (Candidate("あめ", "雨", 0.0),), {})):
            self.assertFalse(self.s.apply_result(req, bad))
        self.assertTrue(self.s.apply_result(req, fake("あめ", "雨")))

    def test_session_never_runs_inference(self):
        with mock.patch.object(Engine, "convert") as convert:
            req = self.s.request()
            self.s.apply_result(req, fake("あめ", "雨"))
            self.s.edit("あめだ")
            self.s.choose(0, req)
            convert.assert_not_called()

    def test_edit_invalidates(self):
        req = self.s.request()
        self.assertTrue(self.s.apply_result(req, fake("あめ", "雨")))
        revision = self.s.revision
        self.s.edit("あめだ")
        self.assertEqual(self.s.candidates, ())
        self.assertGreater(self.s.revision, revision)
        self.assertIsNone(self.s.choose(0, req))
        self.assertEqual(self.s.text, "あめだ")

    def test_editor_switch_and_finish(self):
        req = self.s.request()
        self.s.start_editor(NORMAL)
        self.assertFalse(self.s.apply_result(req, fake("あめ", "雨")))
        self.assertEqual(self.s.text, "")
        self.s.edit("あめ")
        req = self.s.request()
        self.s.finish()
        self.assertFalse(self.s.apply_result(req, fake("あめ", "雨")))
        self.assertIsNone(self.s.choose(0, req))
        self.assertEqual((self.s.active, self.s.text), (False, ""))
        with self.assertRaises(RuntimeError):
            self.s.edit("あ")
        with self.assertRaises(RuntimeError):
            self.s.request()

    def test_password_transition(self):
        req = self.s.request()
        self.s.set_policy(PASSWORD)
        self.assertFalse(self.s.apply_result(req, fake("あめ", "雨")))
        req = self.s.request()
        self.assertFalse(self.s.apply_result(req, fake("あめ", "雨")))
        self.assertEqual(self.s.candidates, ())
        self.assertTrue(self.s.apply_result(req, fake("あめ")))
        self.assertEqual(self.s.choose(0, req), "あめ")

    def test_stale_selection(self):
        req = self.s.request()
        self.s.apply_result(req, fake("あめ", "雨"))
        self.s.edit("あめが")
        self.assertIsNone(self.s.choose(0, req))
        self.assertEqual(self.s.text, "あめが")

    def test_failed_selection_leaves_state(self):
        req = self.s.request()
        self.s.apply_result(req, fake("あめ", "雨"))
        before = self.state()
        for bad in (2, -1, True, "0", None):
            self.assertIsNone(self.s.choose(bad, req))
            self.assertEqual(self.state(), before)
        self.assertEqual(self.s.choose(0, req), "雨")

    def test_reset_invalidation(self):
        req = self.s.request()
        self.profile.reset()
        self.assertFalse(self.s.apply_result(req, fake("あめ", "雨")))
        req = self.s.request()
        self.assertTrue(self.s.apply_result(req, fake("あめ", "雨")))
        self.s.reset_learning()
        self.assertEqual(self.s.candidates, ())
        self.assertIsNone(self.s.choose(0, req))

    def test_external_reset_hides_candidates_on_read(self):
        req = self.s.request()
        self.assertTrue(self.s.apply_result(req, fake("あめ", "雨")))
        self.profile.reset()
        self.assertEqual(self.s.candidates, ())
        self.assertIsNone(self.s.choose(0, req))

    def test_request_repr_does_not_expose_composition(self):
        self.s.set_policy(PASSWORD)
        self.s.edit("synthetic-secret-value")
        self.assertNotIn("synthetic-secret-value", repr(self.s.request()))

    def test_no_implicit_commit_and_no_history(self):
        revision = self.s.revision
        shown = self.s.request()
        self.assertTrue(self.s.apply_result(shown, fake("あめ", "雨")))
        self.assertEqual((self.s.text, self.s.revision), ("あめ", revision))
        late = self.s.request()
        self.assertEqual(self.s.choose(0, shown), "雨")
        self.assertFalse(self.s.apply_result(late, fake("あめ", "雨")))
        self.assertIsNone(self.s.choose(0, shown))
        self.assertEqual((self.s.text, self.s.candidates), ("", ()))
        self.assertNotIn("雨", repr(vars(self.s)))


class CliTest(unittest.TestCase):
    def run_cli(self, argv, stdin=b""):
        out = io.StringIO()
        with mock.patch("sys.stdin", io.TextIOWrapper(io.BytesIO(stdin), encoding="utf-8")), \
                redirect_stdout(out):
            code = main(argv)
        return code, out.getvalue()

    def test_help_without_args(self):
        code, out = self.run_cli([])
        self.assertEqual(code, 0)
        for command in ("demo", "benchmark", "convert"):
            self.assertIn(command, out)

    def test_convert_reads_stdin(self):
        code, out = self.run_cli(["convert", "--sensitive"], "あめ\n".encode())
        # One newline belongs to the input; one separates the output record.
        self.assertEqual((code, out), (0, "あめ\n\n"))
        code, out = self.run_cli(["convert"], "あめ\n".encode())
        self.assertEqual(code, 0)
        self.assertIn("雨", out.splitlines())
        self.assertIn("あめ", out.splitlines())

    def test_convert_preserves_trailing_whitespace(self):
        text = "synthetic secret \r\n\n"
        with mock.patch.object(Engine, "convert", return_value=fake(text)) as convert:
            self.run_cli(["convert", "--sensitive"], text.encode("utf-8"))
        convert.assert_called_once_with(text, k=10, sensitive=True)


if __name__ == "__main__":
    unittest.main()
