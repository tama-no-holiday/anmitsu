# 0.1.30 (B): asking the field for the text before the cursor (prototype 2b)

Code: `HostContext.kt` (new), `AstraImeService.askField` / `rankingContext`, `CommittedContext.kt` (doc only), settings text.
Tests: `HostContextTest` (8), `CommittedContextTest` "Asking the field" (13). Diagnostic: `ossCli context-learning-diag`.
Inputs are fixed example strings. **Nothing here ran on a device.** Constraints decided on 2026-11-02: reads allowed,
a main-thread wait of at most 100 ms, no synchronous host query, no automatic switch to 2a. Paths under `android/build/`
and `artifacts/` are local build outputs, not distributed.

## What it does

- First key of a new composition (kana, or a digit composing after an English word), before anything of ours is
  composing: `getTextBeforeCursor(128, 0)` runs on a reading thread; the main thread waits until 100 ms after the call
  (monotonic deadline taken before the thread starts, so starting it counts). 100 ms is far over a 16 ms frame: a slow
  field stalls that one key.
- In time: sanitized on the reading thread to `ZenzRanking.promptContext` (40 code points) and used as ranking context for
  that composition only, while no commit, field start/finish, switch change, overtaken report or shadow change happened
  since (`Asked` stamps). Dropped when the composition ends.
- Null or a failing connection: no answer, not slow. Late: abandoned (its text is never stored or handed out), asking off
  for that field until another field starts (a restart of the same field does not reset it) and for every field for 30 s
  on `System.nanoTime`; both in memory only and gone with the service.
- Fallback in every case without an answer: the 0.1.29 shadow (`CommittedContext`), which is empty whenever the cursor is
  not known to line up. There is no other fallback and no synchronous read (2a is not implemented).
- Bound: at most one read at a time, one daemon thread per read, no queue. A new ask while a read is still running is
  refused without starting a thread. Nothing cancels a running read (see sources); `close()` only drops its answer. The
  conversion worker is never involved.
- Not asked: fields `EditorPolicy.allowsCommittedContext` refuses (password, TYPE_NULL, no-personalized-learning,
  no-suggestions, URL, email, number, phone, date; unchanged, no per-app list), switch off, no zenz judge, an English word
  of ours open in the field (it would be in the answer), an editor of this process (the settings try box: there the call is
  not a one-way IPC and the editor's handler is the waiting main thread, so it would always time out).
- Own text: the first composing write goes out only after the answer or the cutoff, so an in-time answer cannot contain it;
  a late answer could (the test shows one did) and is dropped. Existing composition / segment repair: never asked (only the
  first key of a composition asks). Selection: asked; `BaseInputConnection` answers what precedes the selection start,
  which the composition replaces. Other editors may answer differently: not verified.

Google Docs: if its fields are refused by `EditorPolicy` (flags), nothing changes there; whether they are is still not
checked on a device. Reads do not fix that, and no exception was added.

## Sources (primary only) and what they establish

Fetched 2026-11-02 with PowerShell `Invoke-WebRequest`.

1. `https://developer.android.com/reference/android/view/inputmethod/InputConnection` (page "Last updated 2026-08-28 UTC").
   Documented: `getTextBeforeCursor` "will trigger an IPC round-trip that will take some time"; `getHandler()` lets the
   **editor** choose the thread its methods are called back on. **Not documented:** that an IME may call
   `InputConnection` from a thread other than its main thread, or any ordering between calls made from different IME threads.
2. `https://developer.android.com/reference/android/os/IBinder` (same fetch). Documented: "multiple oneway calls being made to
   the same IBinder object ... will be dispatched in the other process one at a time, with the same order as the original
   calls"; not guaranteed across different IBinder objects, when mixing oneway and non-oneway, or in-process ("no oneway
   semantics").
3. AOSP `platform/frameworks/base`, tag `android-15.0.0_r1` (implementation, not contract; devices may differ):
   - `core/java/com/android/internal/inputmethod/IRemoteInputConnection.aidl`: `oneway interface IRemoteInputConnection`;
     `getTextBeforeCursor`, `setComposingText`, `commitText` are all on it (reads return through an `AndroidFuture`).
   - `core/java/android/inputmethodservice/RemoteInputConnection.java` (IME side): `getTextBeforeCursor` is annotated
     `@AnyThread`, sends the oneway call and blocks in `CompletableFutureUtil.getResultOrNull(..., MAX_WAIT_TIME_MILLIS)`,
     `MAX_WAIT_TIME_MILLIS = 2000`; returns null at once if the cancellation group is cancelled.
   - `core/java/com/android/internal/inputmethod/CompletableFutureUtil.java`: on `InterruptedException` it records the
     interrupt and **waits again** (a new full timeout); on timeout it returns null. So interrupting does not end a read early.
   - `core/java/android/view/inputmethod/RemoteInputConnectionImpl.java` (editor side): every call is run on one `Handler`
     over the editor's looper (`dispatch`: run directly if already on that thread, else `mH.post`), so oneway calls received
     in order run in order.

What this supports, and how strongly:

- Background invocation: **implementation evidence only** (`@AnyThread` in AOSP 15). No documented guarantee. Gboard and
  LatinIME practice was not used as evidence.
- Our earlier commits being visible to the read: the commit (main thread) is sent before the read is started
  (happens-before through `Thread.start`), both are oneway on the same binder (documented ordering) and the editor runs them
  on one handler (AOSP). Contract for the binder hop, implementation for the rest.
- Our first composing write not being in an in-time answer: does not rely on any of the above; the write is sent only after
  the answer arrived or the wait ended, and late answers are discarded.
- Cancellation: none available (sources above); hence the single-flight bound instead of `Future.cancel`.
- Local SDK stubs were not used as evidence.

Not a blocker: nothing found says 2b deadlocks or is unsupported for a remote editor. The in-process case would deadlock-wait
until the cutoff and is excluded.

## Tests (JVM, Robolectric; no device)

`HostContextTest`: fast answer sanitized and bounded to 40 code points, empty answer; null and exception are NONE and not
slow; a stuck reader ends the wait at the deadline (50 ms in the test) and its late text is never returned to the next ask;
while stuck, 20 further asks start nothing (`threadsStarted` 1, live reading threads unchanged); field off until another
field, quiet exactly 30 s on an injected clock; field still off after the quiet period; `close()` refuses and drops; an
interrupted wait is not an answer and keeps the interrupt.

`CommittedContextTest` (real service, worker, `ZenzRanking` with a spy scorer, `FakeEditor` on synthetic text): asked once
per composition and before any composing span (`answered` records span state at read time); our commit is in the next
answer; announcer/dentist cursor-back case with reads; host paste moving the cursor (the read sees the current text);
selection; stuck host: wait 90–1000 ms, composing written after the cutoff, late read saw our span and was not used, the
aligned shadow ranked instead, empty without an aligned shadow; quiet period across fields and its end; failing connection
falls back and is retried next composition; switch off drops the answer at once and asks nothing more; 8 refused field
types read 0 times; an English word of ours open: 0 reads; an editor of this process: 0 reads.

Existing tests: every pre-0.1.30 editor still throws on any read. `TestIme.hostReads()` is false unless a test sets
`reads`, so those tests and `theEditorsHereReallyRefuseToBeRead` keep their invariant unchanged; nothing was deleted.

## Model evidence (separate from wiring and phone behaviour)

`ossCli context-learning-diag`, real dictionary (`compiled-f11000`), `zenz-v3.2-xsmall-Q5_K_M`, desktop
`libastra_zenz.dll`, canonical scorer; output `android/build/claude-artifacts/ctx-read-diag.tsv` (not committed).

| reading | context | logP A / B | first, no learning |
|---|---|---|---|
| こうえんにいきました (A 公園, B 公演) | none | -1.91 / -0.46 | 公演に行きました |
| | 子供と遊ぶ約束をした。 | -0.13 / -2.88 | 公園に行きました |
| こうえんにいきます (A 公園, B 講演) | none / 子供と…した。 | -0.83 / -2.29, -0.11 / -4.33 | 公園に行きます |
| しかいをつとめます (A 司会, B 歯科医) | 私は歯医者です。 | -1.89 / -0.40 | 司会を務めます (dictionary order; learned B: 歯科医) |
| しかいをつとめま | any | B not in the list | 司会を務めま (ま kept, Part A) |

So with the sentence actually delivered, the model ranks 公園 first for the Docs example. The "neutral" control
context also gives 公園 here, so this case shows that some context helps, not that the meaning of this one is used. Whether
the phone delivers it depends on the field's flags and the host answering in 100 ms: unmeasured.

## Limits and device checks still needed

- Wait time on real hosts (plain app, Google Docs, a heavy app); how often the 100 ms cutoff hits; whether Docs fields
  pass `EditorPolicy`.
- Behaviour of real editors with a selection, and of hosts that answer with stale text.
- The first key of each composition can block the main thread up to 100 ms (plus thread start, included in the deadline).
- If 2b misbehaves on the device, falling back to 2a is a separate manual decision; nothing switches automatically.

## Run and package

- core 101, oss-converter 97 (up to date from Part A, inputs unchanged), app 435 (+21: HostContextTest 8, CommittedContextTest 13) = 633 run, 632 passed, 0 failed, 1 skipped (`BrandRenderTest`); assembleDebug OK; lint 0 errors, 2 known warnings.
- `package-android.ps1 -Version 0.1.30 -RequireOss -RequireZenz`: `anmitsu-0.1.30.apk`, 95,077,134 B, SHA-256 ff914aad805630ec71ca1caea9e1aa9fc8e585f1fc534d167e8647fd6776b89a, versionCode 31, no permissions, signer 63564ca0…7fca527. Contains Part A (3ad0ded) and B. Not device tested, not uploaded.
