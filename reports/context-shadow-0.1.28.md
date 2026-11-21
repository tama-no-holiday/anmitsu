# Context without reading the field: the shadow (0.1.28)

*0.1.29 changed the table of forgetting below: a cursor move inside known text, an arrow key and ⌫ no longer forget. See `reports/context-learning-0.1.29.md`. The sources and everything about reads stand.*

**Result: committed context no longer asks the host for text, on any thread. Getting context cannot wait on the app. Inserting text still takes what the host takes, and nothing here measures latency on a phone.** Unshipped, not committed, not checked on a device. versionName 0.1.28, versionCode 29.

Code: `android/app/src/main/java/dev/astra/ime/CommittedContext.kt`, wired in `AstraImeService.kt`. Tests: `CommittedContextTest.kt`. The synchronous read and its 100 ms / 30 s cooldown are gone. Their evidence stays in `docs/ux-0.1.28.md`, marked as history.

## Sources of context

| source | when | limit |
|---|---|---|
| `EditorInfo.getInitialTextBeforeCursor(128, 0)` | once, when a field starts | the field as it was then, local to the IME, no round trip |
| own commits | each commit the connection accepted | what this keyboard wrote |

Nothing else. No `getTextBeforeCursor`, `getSurroundingText`, `getExtractedText`, `getTextAfterCursor` or `getSelectedText` for context, no async read, no fallback. The service now contains no call to any of them.

## API levels

Level checked in the SDK, `platforms/android-36/data/api-versions.xml`: `getInitialTextBeforeCursor(II)` has `since="30"`. The official website was not reachable from this session; the SDK's data is the same source lint uses.

| device | seed | context |
|---|---|---|
| API 26 to 29 | none, the method does not exist | own commits only, so nothing in the first sentence typed |
| API 30+, host fills the snapshot | up to 40 code points | seed + own commits |
| API 30+, null, throwing, or longer than the cursor offset | none | own commits only |
| API 30+, cursor unknown or a selection | none | own commits only |

An answer may be shorter than the cursor offset (a truncated snapshot): it is still the text right before the cursor. A pair cut at its start is dropped by sanitizing.

## State and invalidation

State per field: `text` (at most 40 sanitized code points), `end` (expected absolute offset, UTF-16), `expected` (reports our writes should cause, oldest first, at most 64), `generation` (bumped when text is forgotten).

| event | text | offsets | why |
|---|---|---|---|
| field starts, new `EditorInfo`, known collapsed cursor | seed | `end` = cursor | |
| same `EditorInfo` object again | empty | unknown | the framework repeats the old object on a configuration change; its snapshot is old. The service keeps the cursor it tracked, see "Late reports and configuration changes" |
| composing write accepted | unchanged | span expected | a composition is not committed |
| commit accepted at `end` | appended once | `end` + length | candidates, punctuation, space, English word, emoji |
| commit accepted elsewhere | that commit alone | from there | what was before is not what precedes it |
| composing write at another offset | forgotten | from there | same |
| composition deleted to empty | unchanged | back to `end` | text before it did not change |
| kept segment, repair | unchanged | span expected | reaches the prompt as the composition's head, once |
| English word taken over by kana | unchanged | span expected | head of the composition, not committed |
| line break key | unchanged | `end` + 1 expected | a report elsewhere (sent, cleared) forgets; sanitizing removes the break |
| report matching any expected entry | unchanged | earlier entries dropped | late, coalesced and repeated reports are ours |
| caret inside our own span | unchanged | unchanged | nothing before the span moved |
| report matching nothing | forgotten | a plain cursor is adopted | cursor jump, external edit, filter refusing a commit |
| selection, someone else's span | forgotten | unknown | |
| write refused (`false`) | forgotten | unknown | |
| own delete of committed text, or of a selection | forgotten | unknown | the amount is not worked out |
| arrow key | forgotten | unknown | |
| editor action | forgotten | unknown | search or send may clear the field |
| paste from the strip | forgotten | kept | never context, never learned; later commits build again |
| more than 64 writes with no report | forgotten | unknown | such a host cannot confirm anything |
| hide | forgotten | unknown | no reseed when shown again |
| restart, other field | new snapshot only | | |
| finish, destroy, switch off | cleared, nothing followed | | applied by a preference listener, at that moment |
| switch on in an open field | empty | | old snapshot not reused |

A conversion in flight when text is forgotten is not shown. The same reading runs again without that text (`generation`). Token staleness, the head-once rule and inference off the main thread are unchanged.

Password, raw, private (`IME_FLAG_NO_PERSONALIZED_LEARNING`), no-suggestion, URL, e-mail, number, phone and date fields: no seed, no own commits, nothing tracked.

Learning is untouched: on by default, a stored off respected, opt-out and clear kept. `learnCommit` has no parameter for context. Context off and learning off are separate.

## What this does not claim

- **Not freshness.** Only offsets are checked. A host that replaces text with text of the same length, or transforms a commit without changing its length, goes unnoticed. `aSilentSameLengthRewriteIsNotDetected` pins this. The effect is ranking with outdated text; nothing is written or stored.
- **A commit is believed before it is confirmed.** `commitText` returning true means the host took it, not that it landed. A later report that disagrees forgets the text and re-ranks; a host that never reports stays believed for up to 64 writes, after which a silent host keeps only recent commits.
- **Not zero latency.** The guarantee covers context acquisition only. `commitText` and `setComposingText` still go to the host.
- **A snapshot can hold a foreign span.** `EditorInfo` does not mark uncommitted text. The first report showing the span forgets it; until then it can be in the seed.
- **Less context than the read gave.** No seed on API 26 to 29. One ⌫ on committed text loses all of it until the next commit. How often this happens is unknown. Hosts that send reports we do not expect lose the seed more often; WebView and Chrome are unverified.
- **Ranking evidence is unchanged and indirect.** No model, rank or native change, so no evaluation was rerun, as agreed. `margin2` was judged with a prefix given outright, and how often a phone has that prefix now is not measured.

## Coverage

Real service, worker and `ZenzRanking`, fake editor, tiny dictionary, spy scorer. **This is wiring, not model accuracy, and JVM runs say nothing about phone timing.**

- Every editor in `CommittedContextTest` throws on `getTextBeforeCursor`, `getTextAfterCursor`, `getSelectedText`, `getExtractedText` and `getCursorCapsMode`, so a read fails the test at once, not by inspecting source. `theEditorsHereReallyRefuseToBeRead` proves the guard fires. `getSurroundingText` and `takeSnapshot` do not exist on these SDKs, so a call would fail there too.
- The user's two sentences, typed: 私はアナウンサーです。 and 私は歯医者です。 reach しかいをつとめます across their commits, once, with no read.
- Also covered: seeded prompt, null, throwing, longer and truncated snapshots, a cut pair, styled text, unknown cursor, selection, reading longer than the surface, late, coalesced and repeated reports, refused writes, a commit a filter dropped, cursor, selection, foreign span, external edit, span moved, restart, other field, repeated `EditorInfo`, hide and show, finish, delete, composition cancel, paste, line break, editor action, English and digit transitions, emoji offsets, protected fields, both switches, learning isolation, late results after a field change, a cursor move, a commit and a switch.
- **API:** only SDK 28 and 30 are available offline. Real API 29 did not run. `api29IsTreatedAsBelowApi30EvenWithASnapshotPresent` sets `SDK_INT` to 29 on the SDK 30 runtime and checks that the method is not called. API 26 and 27 take the same branch, and did not run.
- Not covered: any real host. Chrome, WebView and messaging apps report differently and are the main unknown. Exercised reports come from the fake, shaped after `BaseInputConnection`.

## Late reports and configuration changes (fixed)

Two lifecycle bugs found above are fixed. Neither touches where context comes from: the service still has no call that asks the field for text, and context and learning defaults are unchanged. Code: `OwnReports.kt` (new), `AstraImeService.kt`, `CommittedContext.kt`.

**1. A late report of our own write ended a newer composition.** The service treated every report as the current state. So one key press that commits and composes (English 12-key digits), or kana typed before a commit was reported, lost the composition. A late report also overwrote `selStart`, so the next write started from an old offset.

The service now keeps `OwnReports`: the offsets each accepted write should report, oldest first, in every field, whether or not context is allowed. It holds no text.

| report | what it does |
|---|---|
| fits a write, continues from the last report, a newer write still unreported | **late:** no cursor change, composition and English word stay |
| fits the newest write | as before |
| already passed over, does not continue from the last report | **overtaken:** ignored completely, the shadow does not see it either |
| fits nothing | as before: composition ends and the list is emptied, keeping only a plain cursor |

"Continues" means the report's old offsets equal the last report's new ones, which holds for anything delivered in order. That is what keeps external moves working: a cursor moved back onto an earlier commit either continues from the last report and fits nothing, or fails to continue and is not trusted as late. `own` and tap handling in the composition are unchanged; `late` is checked only after them. The old `ownCursors` list is gone: it covered English words only, commits only.

Key events (⌫, line break, arrows), and commits at an unknown offset, add an entry nothing matches, so older reports still count as late.

**2. A configuration change reset the cursor to the field's first offset.** The real `InputMethodService` path repeats the old `EditorInfo` through `onStartInput(info, true)`. The service now keeps the cursor or selection it tracked (unknown stays unknown) and the writes still to be reported, and expects the report of finishing the span. A new `EditorInfo` object still takes its own offsets, and the list starts over. The shadow's rule is unchanged: empty after a repeat.

### What this leaves open

- **A move identical to a late report passes as late.** While a commit is unreported, a move to exactly its offset, with no span, continuing from the last report, cannot be told from the report. The next report that fits nothing ends the composition. Pinned by `aMoveThatLooksExactlyLikeTheLateReportIsTakenForIt`.
- **"Continues" is assumed from the framework's behaviour and exercised by the fake.** A host whose old offsets do not continue gets no late tolerance, as before this fix, and could have a real move onto one of its last 8 reported writes ignored as overtaken. Chrome and WebView are unverified.
- **Not fixed, from reading the code, not tested:** the report of ⌫, a line break or an arrow has an offset we cannot predict, so kana typed before that report arrives can still lose its composition. Same for a commit made while the composition's start is unknown. Fixing it means excusing an unknown offset, which weakens external-edit detection, so it was left.
- **Not changed:** after an external move ends a composition by writing the kana back, `selStart` is the moved cursor until the next report.

## Final run

Library `91a88be6…` from `android/build/zenz-android-0.1.28`, matching `android/app/zenz-assets.lock`, OSS and zenz bundled, test tasks cleaned first, two consecutive runs by `android/build/claude-artifacts/run-shadow.ps1`, both exit 0. **JVM only (Robolectric, fake editor). Nothing here ran on a device.** Counted from the XML of the second run:

| module | run | failed | skipped |
|---|---|---|---|
| core | 101 | 0 | 0 |
| oss-converter | 92 | 0 | 0 |
| app | 381 (`CommittedContextTest` 98) | 0 | 1 (`BrandRenderTest`, no directory) |
| **total** | **574** | **0** | **1** |

`:app:assembleDebug` OK. `:app:lintDebug` 0 errors, the 2 known warnings (ClickableViewAccessibility, NewerVersionAvailable). No native rebuild, no model evaluation. Logs `android/build/claude-artifacts/shadow-0.1.28-*.log`; the previous run's as `shadow-0.1.28-before-lifecycle-*.log`.

**Reproduced before the fix:** with the service unchanged, 16 of 27 tests failed (the first 26 new ones plus the rewritten `digitsComposingAfterACommittedWordFollowTheWord`; `lifecycle-before-fix.log`). Every failure was a dropped composition or a cursor from the old snapshot, among them `theFrameworksConfigurationPathRepeatsTheEditorInfoAndKeepsTheCursor`, which goes through the real `onConfigurationChanged`. The other 11 passed before and must still pass: mostly external moves, plus cases the old code already handled, such as a coalesced report. 3 more tests were added afterwards, and there are 29 new in all.

**Mutations of the fix, tests rerun, source restored by hash:** late reports trusted without continuity (2 failed); overtaken without continuity (12); late reports moving the cursor (1); list kept after an unmatched report (0 at first, a real gap, so `aCommitBeforeAnExternalMoveExcusesNothingAfterIt` was added; then 1). The first full run also found a real weakness, an external move onto the field's start excused as late, and the continuity check on late reports was added for it.

**Test problems met, disclosed:**
- `offNeitherRecordsNorUsesHistoryWhileContextStaysInMemoryRanking` failed once, in run 2 of an earlier `run-shadow` pass: it captured `""` while the writer was replacing a file. Fixed in the test by waiting for stable content. Learning behaviour is unchanged.
- `aLiveConversionOfAnotherLengthIsNeverContextOnlyWhatWasCommittedIs` failed once in an earlier run. The likely cause is a job still running when the spy is cleared, inferred rather than proven. The test now waits for that prompt.
- `SupplementInputTest.symbolsJoinTheStripAfterTheFirstEntriesAndThePanelListsEveryOne` failed once in an earlier run, at the panel list, and passed in every run since. It delivers no selection reports, so it does not reach this change. It was **not** changed, its cause is not established, and it may be flaky.

Local verification APK, not packaged or uploaded: `android/app/build/outputs/apk/debug/app-debug.apk`, 95,229,829 bytes, SHA-256 `b05007ca9f9143e4547ce0ebf2aca3ac15552502cf4104425540114b92408873`, versionCode 29, versionName 0.1.28, label anmitsu, min 26, target 36, no permissions, arm64-v8a only, zipalign -P 16 OK, v2 signature verified, signer `63564ca0…7fca527` unchanged. The previous build (`8090ab0c…`) is kept as `android/build/claude-artifacts/app-debug-0.1.28-before-lifecycle-8090ab0c.apk`.

Contents checked from the APK: library 6,207,440 bytes `91a88be6…` and model `00c64b3d…` match the zenz lock, and `lexicon.bin`, `connectionId.dat` and `oss-manifest.properties` match the OSS lock.

Separate and still open: the library needs dotprod and fp16 while minSdk is 26, and it has never run on a device.
