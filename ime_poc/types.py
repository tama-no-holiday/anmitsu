"""Shared data types and replaceable component protocols."""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Protocol, runtime_checkable

if TYPE_CHECKING:
    from .channel import WeightedChannel


@dataclass(frozen=True)
class Candidate:
    """A reading or surface hypothesis; lower cost is better.

    ``tokens`` is internal bookkeeping (token readings or surfaces) and is
    excluded from equality.
    """

    reading: str
    surface: str
    cost: float
    tokens: tuple[str, ...] = field(default=(), compare=False, repr=False)


@dataclass(frozen=True)
class Conversion:
    """``readings`` are generation output before conversion/ranking;
    ``candidates`` are ranked final surfaces."""

    readings: tuple[Candidate, ...]
    candidates: tuple[Candidate, ...]
    timings_ms: dict[str, float]


@dataclass(frozen=True)
class Token:
    """One segment of the observed input. ``kind`` is "lex", "oov" or "sym"."""

    start: int
    end: int
    reading: str
    observed: str
    kind: str
    cost: float


@dataclass(frozen=True)
class ReadingHypothesis:
    reading: str
    cost: float
    segmentations: tuple[tuple[Token, ...], ...]


@runtime_checkable
class Corrector(Protocol):
    """Generates corrected reading hypotheses from noisy kana."""

    def generate(self, text: str, channel: WeightedChannel, limit: int) -> list[ReadingHypothesis]: ...


@runtime_checkable
class Converter(Protocol):
    """Maps one reading hypothesis to surface candidates."""

    def convert(self, hypothesis: ReadingHypothesis, limit: int) -> list[Candidate]: ...


@runtime_checkable
class Ranker(Protocol):
    """Orders surface candidates; may use prior-session language counts."""

    def rank(self, candidates: Sequence[Candidate],
             language_counts: Mapping[str, int] | None) -> list[Candidate]: ...
