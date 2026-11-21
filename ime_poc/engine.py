"""Baseline (heuristic, non-neural) noisy kana correction, conversion and ranking."""
from __future__ import annotations

import math
import time
from collections.abc import Mapping, Sequence
from dataclasses import replace
from pathlib import Path

from .channel import WeightedChannel, is_kana, to_hiragana
from .lexicon import Lexicon
from .types import Candidate, Conversion, Converter, Corrector, Ranker, ReadingHypothesis, Token

MAX_INPUT_CHARS = 128
MAX_K = 100
MAX_BEAM_WIDTH = 512
MIN_READINGS = 8
OOV_KANA_COST = 3.0
SYMBOL_COST = 0.3
IDENTITY_PENALTY = 1.0
EDIT_WEIGHT = 1.5  # Development-tuned; resist deleting valid short words to save lexical cost.
MAX_ARCS_PER_OFFSET = 24
SEGMENTATIONS_PER_READING = 3
SURFACES_PER_SEGMENTATION = 3
CONVERTER_BEAM = 6
LANGUAGE_RATE = 0.15
LANGUAGE_TOKEN_CAP = 0.4
LANGUAGE_TOTAL_CAP = 1.0
_PUNCT = frozenset("、。，．,.!?！？「」『』（）()・")
# Small penalties between general POS classes; nothing sentence-specific.
_TRANSITIONS = {
    ("BOS", "particle"): 1.5, ("BOS", "aux"): 1.0,
    ("punct", "particle"): 1.5, ("punct", "aux"): 1.0,
    ("particle", "particle"): 0.5, ("noun", "noun"): 0.4,
    ("adnominal", "verb"): 1.0, ("adnominal", "aux"): 1.0,
}


def _edit_cap(length: int) -> float:
    """Per-token weighted edit budget; short words must match almost exactly."""
    return 0.3 if length == 1 else 1.0 if length == 2 else 1.6 if length == 3 else 2.2


def _span_slack(length: int) -> int:
    return 0 if length == 1 else 1 if length <= 3 else 2


def _order(c: Candidate) -> tuple:
    return (c.cost, c.surface, c.reading)


def _ms(start: float) -> float:
    return (time.perf_counter() - start) * 1000.0


class BaselineCorrector:
    """Beam search over lexicon-guided spans of the raw kana with OOV passthrough.

    Every arc cost is nonnegative (edit cost + lexical cost, or a fixed
    OOV/symbol cost). This bounds scoring but does not by itself prevent
    overcorrection: token length and edit costs still trade off.
    """

    def __init__(self, lexicon: Lexicon, beam_width: int = 32) -> None:
        self.lexicon = lexicon
        self.beam_width = beam_width

    def _arcs(self, text: str, norm: str, channel: WeightedChannel) -> list[list[Token]]:
        n = len(norm)
        run_end = [0] * n
        for i in range(n - 1, -1, -1):
            if is_kana(norm[i]):
                run_end[i] = run_end[i + 1] if i + 1 < n and is_kana(norm[i + 1]) else i + 1
        cache: dict[tuple, list[float]] = {}  # request-local span/word edit costs
        lex = self.lexicon
        arcs = []
        for i in range(n):
            if not is_kana(norm[i]):
                arcs.append([Token(i, i + 1, text[i], text[i], "sym", SYMBOL_COST)])
                continue
            found = []
            for reading in lex.readings:
                size = len(reading)
                slack = _span_slack(size)
                lo = max(1, size - slack)
                hi = min(run_end[i], i + size + slack)
                if hi - i < lo:
                    continue
                window = norm[i:hi]
                cap = _edit_cap(size)
                key = (i, window, reading) if channel.has_touch else (window, reading)
                dists = cache.get(key)
                if dists is None:
                    dists = cache[key] = channel.prefix_distances(window, reading, offset=i, cap=cap)
                base = lex.min_cost(reading)
                for a in range(lo, hi - i + 1):
                    if dists[a] <= cap:
                        found.append(Token(i, i + a, reading, text[i:i + a], "lex", EDIT_WEIGHT * dists[a] + base))
            found.sort(key=lambda t: (t.cost, t.end, t.reading))
            del found[MAX_ARCS_PER_OFFSET:]
            found.append(Token(i, i + 1, norm[i], text[i], "oov", OOV_KANA_COST))
            arcs.append(found)
        return arcs

    def generate(self, text: str, channel: WeightedChannel, limit: int) -> list[ReadingHypothesis]:
        norm = to_hiragana(text)
        n = len(norm)
        arcs = self._arcs(text, norm, channel)
        # beams[i]: token-reading sequence -> (cost, tokens) for paths ending at offset i
        beams: list[dict] = [{} for _ in range(n + 1)]
        beams[0][()] = (0.0, ())
        for i in range(n):
            if not beams[i]:
                continue
            kept = sorted(beams[i].items(), key=lambda kv: (kv[1][0], kv[0]))[:self.beam_width]
            beams[i] = {}
            for key, (cost, toks) in kept:
                for tok in arcs[i]:
                    nkey = key + (tok.reading,)
                    ncost = cost + tok.cost
                    slot = beams[tok.end]
                    old = slot.get(nkey)
                    if old is None or ncost < old[0]:
                        slot[nkey] = (ncost, toks + (tok,))
        finals = sorted(beams[n].items(), key=lambda kv: (kv[1][0], kv[0]))[:self.beam_width]
        grouped: dict[str, list] = {}
        for key, (cost, toks) in finals:
            grouped.setdefault("".join(key), []).append((cost, toks))
        hyps = [ReadingHypothesis(r, segs[0][0], tuple(t for _, t in segs[:SEGMENTATIONS_PER_READING]))
                for r, segs in grouped.items()]
        hyps.sort(key=lambda h: (h.cost, h.reading))
        return hyps[:limit]


class BaselineConverter:
    """Chooses lexicon surfaces per token using general POS-class transitions."""

    def __init__(self, lexicon: Lexicon) -> None:
        self.lexicon = lexicon

    def _options(self, tok: Token) -> list[tuple[str, float, str]]:
        if tok.kind == "lex":
            floor = self.lexicon.min_cost(tok.reading)
            return [(e.surface, e.cost - floor, e.pos) for e in self.lexicon.entries(tok.reading)]
        if tok.kind == "sym":
            return [(tok.observed, 0.0, "punct" if tok.observed in _PUNCT else "sym")]
        return [(tok.observed, 0.0, "unk")]

    def convert(self, hypothesis: ReadingHypothesis, limit: int) -> list[Candidate]:
        best: dict[str, Candidate] = {}
        for seg in hypothesis.segmentations:
            states: list[tuple[float, tuple[str, ...], str]] = [(0.0, (), "BOS")]
            for tok in seg:
                options = self._options(tok)
                states = sorted(
                    (cost + extra + _TRANSITIONS.get((last, pos), 0.0), surfaces + (surface,), pos)
                    for cost, surfaces, last in states for surface, extra, pos in options
                )[:CONVERTER_BEAM]
            for cost, surfaces, _ in states[:SURFACES_PER_SEGMENTATION]:
                surface = "".join(surfaces)
                # Each segmentation has its own channel/lexical costs. The
                # hypothesis cost is only the cheapest segmentation, not a
                # reusable discount for alternative tokenizations.
                total = sum(tok.cost for tok in seg) + cost
                if surface not in best or total < best[surface].cost:
                    best[surface] = Candidate(hypothesis.reading, surface, total, surfaces)
        return sorted(best.values(), key=_order)[:limit]


def _clean_counts(counts: Mapping[str, int] | None) -> dict[str, int]:
    if not isinstance(counts, Mapping):
        return {}
    return {k: min(v, 1_000_000) for k, v in counts.items()
            if isinstance(k, str) and isinstance(v, int) and not isinstance(v, bool) and v > 0}


class BaselineRanker:
    """Cost ordering with a capped per-token bonus from prior-session surface counts.

    ``language_counts`` must come from independent prior sessions, never from
    evaluation targets.
    """

    def rank(self, candidates: Sequence[Candidate],
             language_counts: Mapping[str, int] | None) -> list[Candidate]:
        counts = _clean_counts(language_counts)
        ranked = []
        for cand in candidates:
            bonus = sum(min(LANGUAGE_TOKEN_CAP, LANGUAGE_RATE * math.log1p(counts[t]))
                        for t in sorted(set(cand.tokens)) if t in counts)
            if bonus:
                cand = replace(cand, cost=max(0.0, cand.cost - min(bonus, LANGUAGE_TOTAL_CAP)))
            ranked.append(cand)
        return sorted(ranked, key=_order)


def _keep_identity(items: Sequence[Candidate], identity: Candidate, k: int,
                   attr: str) -> tuple[Candidate, ...]:
    """Bound to k while guaranteeing the raw input survives; keeps ranker order."""
    top = list(items[:k])
    if all(getattr(c, attr) != getattr(identity, attr) for c in top):
        top = top[:k - 1]
        pos = next((j for j, c in enumerate(top) if c.cost > identity.cost), len(top))
        top.insert(pos, identity)
    return tuple(top)


class Engine:
    """Noisy-input kana-to-Japanese conversion with replaceable components."""

    def __init__(self, lexicon_path: str | Path | None = None, beam_width: int = 32, *,
                 lexicon: Lexicon | None = None, corrector: Corrector | None = None,
                 converter: Converter | None = None, ranker: Ranker | None = None) -> None:
        if isinstance(beam_width, bool) or not isinstance(beam_width, int):
            raise TypeError("beam_width must be an int")
        if not 1 <= beam_width <= MAX_BEAM_WIDTH:
            raise ValueError(f"beam_width must be in [1, {MAX_BEAM_WIDTH}]")
        self.lexicon = lexicon if lexicon is not None else Lexicon.load(lexicon_path)
        self.corrector: Corrector = corrector or BaselineCorrector(self.lexicon, beam_width)
        self.converter: Converter = converter or BaselineConverter(self.lexicon)
        self.ranker: Ranker = ranker or BaselineRanker()

    def convert(self, text: str, k: int = 10, *,
                touch_hints: dict[int, dict[str, float]] | None = None,
                motor_counts: dict[tuple[str, str], int] | None = None,
                language_counts: dict[str, int] | None = None,
                sensitive: bool = False) -> Conversion:
        if not isinstance(text, str):
            raise TypeError("text must be a str")
        if isinstance(k, bool) or not isinstance(k, int):
            raise TypeError("k must be an int")
        if not 2 <= k <= MAX_K:
            raise ValueError(f"k must be in [2, {MAX_K}] so the raw input can survive as an alternative")
        start = time.perf_counter()
        identity = Candidate(text, text, 0.0, (text,))
        # Sensitive, empty or over-long input: identity only, no personal data used, no truncation.
        if sensitive or not text or len(text) > MAX_INPUT_CHARS:
            return Conversion((identity,), (identity,), {"total": _ms(start)})

        channel = WeightedChannel(touch_hints=touch_hints, motor_counts=motor_counts)
        hyps = self.corrector.generate(text, channel, max(k, MIN_READINGS))
        correct_ms = _ms(start)

        norm = to_hiragana(text)
        id_cost = min([sum(OOV_KANA_COST if is_kana(c) else SYMBOL_COST for c in norm)]
                      + [h.cost for h in hyps if h.reading == norm])
        readings = _keep_identity(
            [Candidate(h.reading, h.reading, h.cost,
                       tuple(t.reading for t in h.segmentations[0]) if h.segmentations else ())
             for h in hyps],
            replace(identity, cost=id_cost), k, "reading")

        mark = time.perf_counter()
        pool: dict[str, Candidate] = {}
        for hyp in hyps:
            for cand in self.converter.convert(hyp, SEGMENTATIONS_PER_READING * SURFACES_PER_SEGMENTATION):
                old = pool.get(cand.surface)
                if old is None or cand.cost < old.cost:
                    pool[cand.surface] = cand
        convert_ms = _ms(mark)

        # Compare the literal alternative with an actual same-reading conversion
        # including POS costs, rather than the cheaper pre-conversion score.
        same_reading_costs = [c.cost for c in pool.values() if c.reading == norm]
        if same_reading_costs:
            id_cost = min(same_reading_costs)

        mark = time.perf_counter()
        ranked = self.ranker.rank(sorted(pool.values(), key=_order), language_counts)
        candidates = _keep_identity(ranked, replace(identity, cost=id_cost + IDENTITY_PENALTY),
                                    k, "surface")
        return Conversion(readings, candidates, {
            "correct": correct_ms, "convert": convert_ms, "rank": _ms(mark), "total": _ms(start)})
