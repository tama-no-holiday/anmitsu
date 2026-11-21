"""Small experimental lexicon loader."""
from __future__ import annotations

import json
import math
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from .channel import is_kana

DEFAULT_LEXICON_PATH = Path(__file__).resolve().parent.parent / "data" / "lexicon.json"
POS_TAGS = frozenset({"noun", "verb", "adj", "adverb", "particle", "aux", "adnominal", "conj"})


@dataclass(frozen=True)
class LexEntry:
    reading: str
    surface: str
    cost: float
    pos: str


class Lexicon:
    def __init__(self, entries: Iterable[LexEntry], meta: dict | None = None) -> None:
        by_reading: dict[str, list[LexEntry]] = {}
        seen: set[tuple[str, str]] = set()
        for e in entries:
            if not e.reading or not all(is_kana(c) for c in e.reading):
                raise ValueError(f"lexicon reading must be hiragana: {e.reading!r}")
            if not e.surface:
                raise ValueError(f"empty surface for {e.reading!r}")
            if not math.isfinite(e.cost) or e.cost < 0:
                raise ValueError(f"lexicon cost must be finite and >= 0: {e!r}")
            if e.pos not in POS_TAGS:
                raise ValueError(f"unknown POS {e.pos!r} for {e.reading!r}")
            if (e.reading, e.surface) in seen:
                raise ValueError(f"duplicate entry {e.reading!r}/{e.surface!r}")
            seen.add((e.reading, e.surface))
            by_reading.setdefault(e.reading, []).append(e)
        self._by_reading = {r: tuple(sorted(v, key=lambda e: (e.cost, e.surface)))
                            for r, v in by_reading.items()}
        self.readings: tuple[str, ...] = tuple(sorted(self._by_reading))
        self.meta = dict(meta or {})

    def __len__(self) -> int:
        return sum(len(v) for v in self._by_reading.values())

    def entries(self, reading: str) -> tuple[LexEntry, ...]:
        return self._by_reading.get(reading, ())

    def min_cost(self, reading: str) -> float:
        return self._by_reading[reading][0].cost

    @classmethod
    def load(cls, path: str | Path | None = None) -> Lexicon:
        with open(path or DEFAULT_LEXICON_PATH, encoding="utf-8") as fh:
            raw = json.load(fh)
        entries = [LexEntry(str(r), str(s), float(c), str(p)) for r, s, c, p in raw["entries"]]
        return cls(entries, raw.get("meta"))
