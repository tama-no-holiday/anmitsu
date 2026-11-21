"""Weighted optimal-string-alignment noisy channel for kana input."""
from __future__ import annotations

import math
from collections.abc import Mapping

VOICING_COST = 0.8      # か/が, ふ/ぷ, や/ゃ, つ/っ
SAME_ROW_COST = 1.0     # か/こ, ょ/ゅ: same consonant row, different vowel
SAME_VOWEL_COST = 1.4   # か/さ: same vowel, different consonant
OTHER_SUB_COST = 2.0
INDEL_COST = 1.2
LIGHT_INDEL_COST = 0.8  # small kana, っ, ん, ー are easy to drop or double
TRANSPOSE_COST = 1.0
TOUCH_MIN_PROB = 0.05
TOUCH_FLOOR = 0.05
MOTOR_RATE = 0.1
MOTOR_MAX_DISCOUNT = 0.5

_ROWS = {
    "a": "あいうえお", "k": "かきくけこ", "s": "さしすせそ", "t": "たちつてと",
    "n": "なにぬねの", "h": "はひふへほ", "m": "まみむめも", "y": "や_ゆ_よ",
    "r": "らりるれろ", "w": "わ___を", "g": "がぎぐげご", "z": "ざじずぜぞ",
    "d": "だぢづでど", "b": "ばびぶべぼ", "p": "ぱぴぷぺぽ", "xa": "ぁぃぅぇぉ",
    "xy": "ゃ_ゅ_ょ", "xt": "__っ__",
}
# Rows related by voicing (dakuten/handakuten) or small/large size.
_FAMILY = {"g": "k", "z": "s", "d": "t", "xt": "t", "b": "h", "p": "h", "xa": "a", "xy": "y"}
_KANA = {ch: (row, v) for row, chars in _ROWS.items() for v, ch in enumerate(chars) if ch != "_"}
_LIGHT = frozenset("ぁぃぅぇぉゃゅょっんー")


def _base_cost(a: str, b: str) -> float:
    (ra, va), (rb, vb) = _KANA[a], _KANA[b]
    if ra == rb:
        return SAME_ROW_COST
    if _FAMILY.get(ra, ra) == _FAMILY.get(rb, rb):
        return VOICING_COST if va == vb else SAME_VOWEL_COST
    return SAME_VOWEL_COST if va == vb else OTHER_SUB_COST


# Character-confusion constants only; no input text is cached across requests.
_BASE = {(a, b): _base_cost(a, b) for a in _KANA for b in _KANA if a != b}


def to_hiragana(text: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


def is_kana(ch: str) -> bool:
    return "ぁ" <= ch <= "ゖ" or ch == "ー"


def _indel(ch: str) -> float:
    return LIGHT_INDEL_COST if ch in _LIGHT else INDEL_COST


class WeightedChannel:
    """Edit cost of typing ``observed`` when ``intended`` was meant.

    touch_hints: observed index -> {candidate char: probability}; synthetic
    keyboard evidence, used only where probabilities are valid (each in
    [TOUCH_MIN_PROB, 1], total <= 1 per index).
    motor_counts: (observed, intended) -> prior substitution count; the
    discount grows logarithmically and is capped at MOTOR_MAX_DISCOUNT.
    Inputs are copied, never mutated.
    """

    def __init__(self, touch_hints: Mapping[int, Mapping[str, float]] | None = None,
                 motor_counts: Mapping[tuple[str, str], int] | None = None) -> None:
        self._touch: dict[int, dict[str, float]] = {}
        for idx, probs in (touch_hints or {}).items():
            if isinstance(idx, bool) or not isinstance(idx, int) or idx < 0:
                continue
            if not isinstance(probs, Mapping):
                continue
            clean = {}
            for ch, p in probs.items():
                if not isinstance(ch, str) or len(ch) != 1 or isinstance(p, bool):
                    continue
                if isinstance(p, (int, float)) and TOUCH_MIN_PROB <= p <= 1.0 and math.isfinite(p):
                    clean[to_hiragana(ch)] = float(p)
            if clean and sum(clean.values()) <= 1.0 + 1e-6:
                self._touch[idx] = clean
        self._motor: dict[tuple[str, str], int] = {}
        for key, n in (motor_counts or {}).items():
            if not (isinstance(key, tuple) and len(key) == 2
                    and all(isinstance(c, str) and len(c) == 1 for c in key)):
                continue
            if isinstance(n, bool) or not isinstance(n, int) or n <= 0:
                continue
            pair = (to_hiragana(key[0]), to_hiragana(key[1]))
            if pair[0] != pair[1]:
                self._motor[pair] = min(1_000_000, self._motor.get(pair, 0) + n)

    @property
    def has_touch(self) -> bool:
        return bool(self._touch)

    def substitution(self, observed: str, intended: str, index: int) -> float:
        if observed == intended:
            return 0.0
        cost = _BASE.get((observed, intended), OTHER_SUB_COST)
        n = self._motor.get((observed, intended))
        if n:
            cost *= 1.0 - min(MOTOR_MAX_DISCOUNT, MOTOR_RATE * math.log1p(n))
        hint = self._touch.get(index)
        if hint and intended in hint:
            cost = min(cost, max(TOUCH_FLOOR, -math.log(hint[intended])))
        return cost

    def prefix_distances(self, observed: str, intended: str, *, offset: int = 0,
                         cap: float = math.inf) -> list[float]:
        """Distances of every prefix observed[:a] to the whole ``intended``.

        Entries are inf once two consecutive DP rows exceed ``cap``.
        """
        n_obs, n_int = len(observed), len(intended)
        prev2: list[float] = []
        prev = [0.0] * (n_int + 1)
        for b in range(1, n_int + 1):
            prev[b] = prev[b - 1] + _indel(intended[b - 1])
        out = [prev[n_int]]
        for a in range(1, n_obs + 1):
            oc = observed[a - 1]
            ins = _indel(oc)
            cur = [prev[0] + ins] + [0.0] * n_int
            for b in range(1, n_int + 1):
                tc = intended[b - 1]
                v = prev[b - 1] + self.substitution(oc, tc, offset + a - 1)
                x = prev[b] + ins
                if x < v:
                    v = x
                x = cur[b - 1] + _indel(tc)
                if x < v:
                    v = x
                if (a > 1 and b > 1 and oc != tc and oc == intended[b - 2]
                        and observed[a - 2] == tc):
                    x = prev2[b - 2] + TRANSPOSE_COST
                    if x < v:
                        v = x
                cur[b] = v
            out.append(cur[n_int])
            if min(cur) > cap and min(prev) > cap:
                out.extend([math.inf] * (n_obs - a))
                break
            prev2, prev = prev, cur
        return out

    def distance(self, observed: str, intended: str, *, offset: int = 0) -> float:
        return self.prefix_distances(to_hiragana(observed), to_hiragana(intended), offset=offset)[-1]
