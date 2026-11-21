"""UI-independent composition lifecycle gate (no Android, no worker thread).

Inference runs elsewhere via ``run_request``; the session only decides whether
a returned result may still be shown or selected. It holds the current
composition only and never stores committed text.
"""
from __future__ import annotations

import itertools
from dataclasses import dataclass, field

from .engine import MAX_K
from .personalization import EditorPolicy, Profile
from .types import Candidate, Conversion

_SESSION_IDS = itertools.count(1)


@dataclass(frozen=True, eq=False)
class Request:
    """Immutable inference snapshot. Accepted only by object identity, so copies are rejected."""

    session: int
    revision: int
    generation: int
    text: str = field(repr=False)
    policy: EditorPolicy


def run_request(engine, profile: Profile, request: Request, k: int = 10) -> Conversion:
    """Worker-side inference for ``request``, honouring its policy."""
    return engine.convert(request.text, k=k, **profile.engine_kwargs(request.policy))


def _valid(result: object, request: Request) -> bool:
    if not isinstance(result, Conversion) or not isinstance(result.candidates, tuple):
        return False
    cands = result.candidates
    if not 0 < len(cands) <= MAX_K or not all(isinstance(c, Candidate) and isinstance(c.surface, str)
                                              for c in cands):
        return False
    surfaces = [c.surface for c in cands]
    if request.text not in surfaces:
        return False
    return not request.policy.sensitive or all(s == request.text for s in surfaces)


def _check_policy(policy: object) -> None:
    if not isinstance(policy, EditorPolicy):
        raise TypeError("policy must be an EditorPolicy")


class Session:
    """Composition state for one keyboard. Edits, editor switches, finish, policy
    changes and profile resets all bump ``revision`` and drop candidates and tokens."""

    def __init__(self, profile: Profile) -> None:
        self._profile = profile
        self._session = 0
        self._revision = 0
        self._generation = profile.generation
        self._policy = EditorPolicy()
        self._active = False
        self._text = ""
        self._candidates: tuple[Candidate, ...] = ()
        self._pending: Request | None = None
        self._shown: Request | None = None

    active = property(lambda self: self._active)
    session = property(lambda self: self._session)
    revision = property(lambda self: self._revision)
    text = property(lambda self: self._text)
    policy = property(lambda self: self._policy)
    @property
    def candidates(self) -> tuple[Candidate, ...]:
        # A profile can also be reset outside Session.reset_learning. Do not
        # let a UI read the old personalized suggestions after that reset.
        self.refresh()
        return self._candidates

    def _invalidate(self, clear_text: bool) -> None:
        self._revision += 1
        self._pending = self._shown = None
        self._candidates = ()
        if clear_text:
            self._text = ""

    def _require_active(self) -> None:
        if not self._active:
            raise RuntimeError("no active editor")

    def start_editor(self, policy: EditorPolicy) -> int:
        """Begin a new editor session; everything from the previous editor is dropped."""
        _check_policy(policy)
        self._session = next(_SESSION_IDS)
        self._active = True
        self._policy = policy
        self._generation = self._profile.generation
        self._invalidate(True)
        return self._session

    def finish(self) -> None:
        self._active = False
        self._invalidate(True)

    def set_policy(self, policy: EditorPolicy) -> None:
        """Change flags of the current editor (e.g. to a password field)."""
        _check_policy(policy)
        self._require_active()
        self._policy = policy
        self._invalidate(False)

    def edit(self, text: str) -> int:
        """Replace the composition and return the new revision. Never runs inference."""
        self._require_active()
        if not isinstance(text, str):
            raise TypeError("text must be a str")
        self._text = text
        self._invalidate(False)
        return self._revision

    def refresh(self) -> bool:
        """Invalidate if the profile generation changed; returns whether it did."""
        if self._profile.generation == self._generation:
            return False
        self._generation = self._profile.generation
        self._invalidate(False)
        return True

    def reset_learning(self) -> None:
        self._profile.reset()
        self.refresh()

    def request(self) -> Request:
        """Snapshot the composition for inference; supersedes any earlier pending request."""
        self._require_active()
        self.refresh()
        self._pending = Request(self._session, self._revision, self._generation, self._text, self._policy)
        return self._pending

    def _fresh(self, request: Request) -> bool:
        return (self._active and not self.refresh() and request.session == self._session
                and request.revision == self._revision and request.generation == self._generation
                and request.policy == self._policy and request.text == self._text)

    def apply_result(self, request: Request, result: Conversion) -> bool:
        """Show ``result`` as candidates if ``request`` is the current pending one.

        Consumes the token on success. Only candidates change; nothing is committed.
        """
        if request is None or request is not self._pending or not self._fresh(request):
            return False
        if not _valid(result, request):
            return False
        self._pending = None
        self._shown = request
        self._candidates = result.candidates
        return True

    def choose(self, index: int, request: Request) -> str | None:
        """Return the chosen surface for the app to commit, or None with state unchanged.

        On success the composition is cleared and ``revision`` bumped so late results cannot land.
        """
        if request is None or request is not self._shown or not self._fresh(request):
            return None
        if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < len(self._candidates):
            return None
        chosen = self._candidates[index].surface
        if self._policy.sensitive and chosen != self._text:
            return None
        self._invalidate(True)
        return chosen
