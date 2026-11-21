"""Bounded, memory-only personalization. Nothing is persisted, logged or kept as raw history."""
from __future__ import annotations

from dataclasses import dataclass

from .channel import is_kana, to_hiragana

MAX_COUNT = 10_000
MAX_MOTOR_ENTRIES = 256
MAX_LANGUAGE_ENTRIES = 512
MAX_SURFACE_CHARS = 16


@dataclass(frozen=True)
class EditorPolicy:
    """Per-editor privacy flags. ``sensitive`` covers password-like fields (no correction at all)."""

    sensitive: bool = False
    no_personalized_learning: bool = False


def _bump(table: dict, key, limit: int) -> None:
    if key not in table and len(table) >= limit:
        # Evict the weakest entry (deterministic tie-break) so the table stays bounded.
        del table[min(table, key=lambda k: (table[k], k))]
    table[key] = min(MAX_COUNT, table.get(key, 0) + 1)


class Profile:
    """Aggregate counts updated only by explicit calls, never from Top-1 or raw input.

    ``motor_counts`` maps (observed, intended) single kana to counts and
    ``language_counts`` maps accepted surfaces to counts. ``generation``
    increments on reset so sessions can drop results built from discarded data.
    """

    def __init__(self, learning_enabled: bool = True) -> None:
        self.learning_enabled = bool(learning_enabled)
        self._motor: dict[tuple[str, str], int] = {}
        self._language: dict[str, int] = {}
        self._generation = 0

    def __repr__(self) -> str:  # never expose learned contents
        return (f"Profile(generation={self._generation}, motor_entries={len(self._motor)}, "
                f"language_entries={len(self._language)})")

    @property
    def generation(self) -> int:
        return self._generation

    def _may_learn(self, policy: object) -> bool:
        return (bool(self.learning_enabled) and isinstance(policy, EditorPolicy)
                and not policy.sensitive and not policy.no_personalized_learning)

    def observe_substitution(self, observed: str, intended: str, *, policy: EditorPolicy) -> bool:
        """Count one explicit correction of kana ``observed`` to ``intended``. Returns whether stored."""
        if not self._may_learn(policy) or not all(isinstance(c, str) and len(c) == 1
                                                  for c in (observed, intended)):
            return False
        pair = (to_hiragana(observed), to_hiragana(intended))
        if pair[0] == pair[1] or not all(is_kana(c) for c in pair):
            return False
        _bump(self._motor, pair, MAX_MOTOR_ENTRIES)
        return True

    def accept_word(self, surface: str, *, policy: EditorPolicy) -> bool:
        """Count one explicitly accepted word surface. Returns whether stored."""
        if (not self._may_learn(policy) or not isinstance(surface, str)
                or not 0 < len(surface) <= MAX_SURFACE_CHARS
                or not surface.isprintable() or surface.strip() != surface):
            return False
        _bump(self._language, surface, MAX_LANGUAGE_ENTRIES)
        return True

    def reset(self) -> None:
        """Forget everything and start a new generation."""
        self._motor.clear()
        self._language.clear()
        self._generation += 1

    def motor_counts(self) -> dict[tuple[str, str], int]:
        return dict(self._motor)

    def language_counts(self) -> dict[str, int]:
        return dict(self._language)

    def engine_kwargs(self, policy: EditorPolicy) -> dict:
        """Copied keyword arguments for ``Engine.convert``; fails closed to sensitive."""
        if not isinstance(policy, EditorPolicy) or policy.sensitive:
            return {"sensitive": True}
        return {"motor_counts": dict(self._motor) or None,
                "language_counts": dict(self._language) or None}
