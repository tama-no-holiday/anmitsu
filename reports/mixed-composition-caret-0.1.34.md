# 0.1.34: whole mixed English candidates (A) and a composing caret the arrows move without committing (B)

Based on 09cd050 (0.1.33). versionName 0.1.34, versionCode 35. **Nothing here ran on a device.** Model, dictionary, ranking strength,
learning, native library, layouts, flicks, long-press and the number screens are unchanged. Paths under `artifacts/`
are local build outputs, not distributed.

Commits: `eda1192` (core caret), `711da94` (A + B in the service, tests), then the version/report commit. A and B share
one commit because both rest on the new `EnglishWord` (entries with a caret) and the same service paths; splitting
them would leave a commit that does not compile.

## Review follow-up (same 0.1.34 / code 35)

The review found two requested behaviours missing from `d97b986`; both are now implemented (commits `4b97a7c`, `df9bbd0`,
then the report commit). The APK reviewed is kept as `artifacts/apk/anmitsu-0.1.34-pre-review.apk` (SHA-256
bb68f9af…dbbe, with `build-info-0.1.34-pre-review.json`). Still **nothing ran on a device**; still not uploaded.

### R1. Combined forms of different parts (`WholeCandidates`, stage 6)

Each changeable part is an axis: a token (letters: first dictionary spelling, Title/UPPER/lower, full width of each; digits:
the supplement's number forms; punctuation: its symbol forms, full width added) or, for a bracket kind whose matched pairs
span tokens, the pair styles (both brackets change together, so a pair never gets two styles). Each form has a cost:
1 for widths, case, the first spelling, 「」『』【】（）, circled numbers ①–⑳; 4 and more (in the supplement's order) for
the others. Then, after stages 1–5 as before:
- 6a: one part in a cost-1 form, the rest as typed (`m-①`, `m－1`);
- 6b: one part in one of its first 3 forms inside each whole form of the rest (UPPER, Title, full width, full UPPER, full
  Title), rank by rank over all parts, at most 40 (`m-1x` → `M-①X`, `Ｍ－①Ｘ`);
- 6c: a best-first search over form-index vectors (priority queue by cost, then fewer parts changed, then nearest the
  caret; visited set): two or more parts changed together from each part's first 5 forms (at most 40 such
  combinations), and the costlier forms of one part; at most 600 queue steps.
The whole list stops at 96 (was 64), each candidate once and always the whole composition. The service builds it once per
composition state (text, caret, lookups held, field, converter ready), not per render. Work per state is bounded by the
constants above (≤ 600 pops × ≤ 9 axes of short string steps), on the main thread, no I/O; it is **not** every
combination: with many parts the 40 combinations go to the cheapest ones nearest the caret. Address fields still get no
width/number/punctuation forms (letters' case and dictionary forms combine).

Through the service (`MixedCaretTest.combinedForms…`, English field with "x " before): `m1` → `M① ｍ① Ｍ① M１`;
`m-1` → `M-① ｍ-① Ｍ－① M－1 m－①`; number screen `1-2` → `①－2 ①-② 1－② １－２`; `abc()` → `ABC「」 Abc【】 ａｂｃ「」 abc『』`;
`a(1)` → `A「①」 a「①」 A(①) Ａ（１）`; `openai-1` → `OpenAI-①` (English index × number form), picked: the field reads
`x m1m-11-2abc()a(1)OpenAI-①`, one commit per Enter or pick, no reads. Every bracket in every listed candidate of `abc()`
and `a(1)` is closed by its own partner. A chip from an earlier composition commits nothing; with the caret moved into
`m-1x` the strip is still whole (`M-①X`, `Ｍ－①Ｘ`) and a pick replaces it all (`<M-①X`). Pure test: a 23-character
mixed composition stays ≤ 96, distinct, whole, and has two-part combinations.

### R2. A reversible boundary at the composing caret (`RepairSession` caret mode)

- A caret strictly inside a segment shows that segment split at the caret (`split()`, a view only: `shownUnits`, the
  segment buttons, the highlighted range and the editor's caret see it; `units` do not change). Where the alignment gives
  the caret a place (a word boundary, inside kana-written text, passthrough text) both parts keep their words (`明日|は`);
  inside a word written otherwise both parts show their kana (`あし|たは`), never mapped by surface length.
- Candidates are for the part before the caret (`scopeTyped`: `明日|は雨` → あした, `あし|た` → あし, `明日は|雨` → あしたは).
  Every new scope bumps the revision, so a result for the previous scope is rejected (tested), and is requested anew.
- Moving on drops the split; walking back to the end untouched leaves the units identical (same objects, no undo
  history) and the conversion as before. **Enter after only moving commits the conversion**: `finish()` now returns the
  units, not what a passing caret shows (`明日はあ|め` + Enter → `明日は雨`; `あし|たは雨` + Enter → `明日は雨`; caret at the
  end of the committed text). This replaces the first version's "commits the kana shown".
- An edit or a pick at the caret makes that one boundary real (`caretReplace`, `choose`): typed text joins a part before it
  that shows kana or becomes a new unit (`明日は|雨` + の → units あしたは / の / あめ, shown `明日はの|雨`; 明日は and 雨 are not
  converted again); typing continues in that new unit without further splits; ⌫ removes the character before the caret
  and keeps the words around it (`明日は|` ⌫ → `明日|`, no reconversion); 小゛゜ replaces it; a pick replaces only the part
  before the caret (`明日|は雨` → アシタ → `アシタ|は雨`; inside 雨, `あ|め` → ア → `明日はア|め`). The other part of a word
  cut through converts again as the request's neighbour (live conversion previews it); every other unit stays.
- English in the middle of a kana composition (`abc` taken over by `あめ`, live `abc雨`): the converter gives each letter
  its own passthrough segment, so `ab|c雨` shows units a / b / c / あめ, scope b; の there → a / bの / c / あめ, `|c雨` kept.
  (A passthrough segment cut inside, `ab|c` in one unit, is covered in `ComposingCaretTest`.)
- Explicit segment selection is unchanged: a segment button (a split part selects its whole segment) leaves caret mode
  and its ◀ ▶ resize. ▲ ▼ keep the start/end fallback (no line geometry).

### Follow-up runs

- Targeted: `ComposingCaretTest` 11/11 (7 → 11; 3 rewritten for the new semantics: edits, choice, finish); `MixedCaretTest`
  22/22 (18 → 22; 3 rewritten: middle edits, kana pick, stale result; new: Enter after moving, English in the middle,
  combined forms, stale/moved English picks); `EnglishInputTest` +1. First runs failed as expected on the old pins, then
  on test assumptions (a digit first on the English screen types at once; letters are one segment each), and once on a
  real bug (pair-style forms were not sorted by cost, hiding `A「①」`), fixed.
- Full `scripts/build-android.ps1` with OSS resources and zenz lib/model, `:core:test :oss-converter:test
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL (`artifacts/full-0.1.34-review.log`).
  core 116 (up to date from the targeted run with the same inputs), oss-converter 105 (up to date, unchanged), app 496:
  717 run, 716 passed, 0 failed, 1 skipped (`BrandRenderTest.render`). Lint 0 errors, the 2 known warnings.
  Only the final state was fully run; the intermediate commit `4b97a7c` was not built on its own.
- APK `artifacts/apk/anmitsu-0.1.34.apk`, 95,323,892 B, SHA-256
  `d971470e2515da07f18b69cc4c65f02956885fa21a4ae2b51fe8b7478f3abfb7`, `package-android.ps1 -Version 0.1.34 -RequireOss
  -RequireZenz` passed. versionCode 35, versionName 0.1.34, package dev.astra.ime.trial, label anmitsu, no permissions,
  signer 63564ca0…7fca527 (same as before). Against the pre-review APK only `classes10.dex` and `classes14.dex` differ
  (55 entries; zenz library and model, OSS assets, English index, supplement identical by CRC and size); the file is
  213 KB larger because the dex growth crossed an alignment boundary.
- No new synchronous host calls (only `setComposingText`, `setSelection`, `commitText`, `finishComposingText` as before);
  no ranking, learning, dictionary, model or native changes; privacy rules unchanged.

### Remaining gaps after the follow-up

- Combinations are bounded (above), not exhaustive.
- The scope is the part before the caret only; the part after it gets no candidates of its own until the caret is at its
  end. A unit typed in at the caret is converted as a whole (no split while it is being typed).
- Only one cut part converts as the neighbour per request; a second one left as kana converts with a later request.
- ▲ ▼ remain start/end; real editors' handling of `setSelection` inside a composing span is unverified on devices.

## Reproduced first (0.1.33 code, real service, Robolectric)

`MixedCaretTest`, three tests written before any change, run on 09cd050 (`artifacts/repro-0.1.34.log`), 3 of 3 failed:
- `mMinusOneHasCandidatesForTheWholeComposition`: timed out waiting for `M-1` (the strip had only `1` and its forms,
  matching the reported failure).
- `anArrowInAnEnglishCompositionMovesItsCaretWithoutCommitting`, `anArrowInAKanaCompositionMovesItsCaretWithoutCommitting`:
  `composingStart` was -1 after one ◀, i.e. the composition had been committed.

## A. Candidates for the whole English composition

`EnglishWord` now holds entries (a letter, or one insertion: punctuation, digit, palette entry, an emoji sequence
whole) and a caret between them. `WholeCandidates` (in `EnglishInput.kt`) builds the strip and the ▼ panel; every
candidate is the complete composition, so a pick commits exactly it (nothing before or after it is lost or committed
by itself) and typing a symbol or digit never commits anything.

Order, each candidate once, at most `MAX` (64 in the first version, 96 after the review follow-up):
1. the composition as typed (what Enter/Space commit);
2. every word in its dictionary spelling (existing English index: `openai123` → `OpenAI123`), then completions of the word
   at the caret (≤ 3, shown under 予測 in the panel);
3. whole case forms: Title words, UPPER, lower (`m-1` → `M-1`);
4. whole width forms of the above (`ｍ－１`, `Ｍ－１`), half width when something full-width was typed;
5. bracket styles applied to every matched `()`, `[]`, `{}` pair at once, from the existing Mozc-derived supplement's own
   pairs (≤ 20 per kind: `(12)` → `（12）` `「12」` `【12】` …), so a pair is never split into two styles;
6. one token changed with the rest as typed, the token at the caret first, then by distance (≤ 8 tokens; ≤ 8 forms each,
   ≤ 20 for punctuation): words (spellings, case, full width), digits (the supplement's number forms: `m-①`, `m-１`,
   `m-一` …), punctuation (widths and Mozc's groups: `m－1`, `m—1` …). A bracket paired with one in another token
   changes only through 5; an unmatched one keeps its own forms (`(12` → `「12`).

Examples now offered (all pinned by tests): `m-1` → `M-1`, `ｍ－１`, `Ｍ－１`, `m-①`, `m-１`, `m－1`, `ｍ-1`;
`openai123` → `OpenAI123`, `OPENAI123`, `ｏｐｅｎａｉ１２３`, `openai１２３`; `abc()` → `abc（）`, `abc「」`, `abc【】`,
`ABC()`, `ａｂｃ（）` and never `abc（)`; `(12)` → `（12）`, `「12」`, `【12】`, `(⑫)`, `(十二)`, `（１２）`; `hi,openai` →
`hi,OpenAI`; `()` alone, `12` alone, `openai` alone: unchanged 0.1.33 lists.

Bounds (superseded by the review follow-up below, which adds combinations of different tokens): the first version
changed two different tokens together only through the whole-composition forms 3–5, so `M-①` or `ｍ-①` were not listed.
Index lookups: ≤ 4 words per background job; keys over 24 characters have no supplement forms. Only ASCII/full-width
`()`, `[]`, `{}` count as pairs. Address (URL, e-mail) fields: a mixed composition gets case and dictionary forms and
bracket styles only, no width, number or punctuation forms (number forms were already off there). Password, raw, number,
phone and date fields type at once as before (tested).

## B. The composing caret

While anything of ours is composed, ◀ ▶ ▲ ▼ never commit and never reach the editor. All arrow inputs (tap, flick in any
of the four directions on the arrow keys, held repeat) arrive at the same `onAction`, so they all obey it. With nothing
composed the editor's own key events are sent as before (DPAD_UP/DOWN still only where `EditorPolicy.allowsVerticalKeys`).

- English: the caret moves one entry a press (an emoji sequence is one step); typing, palette entries, the number
  screens, ⌫ and the case key act at the caret (the case key flips the letter before it). ⌫ with the caret at the start
  does nothing. A kana typed with the caret inside an English composition goes in at the caret as typed (it is not
  converted; at the end the 0.1.25 take-over into a kana composition is unchanged). Space still confirms the whole
  composition and then types a space after it.
- Kana: the first ◀ (or ▲) puts a caret into what is shown, split into its existing segments (`RepairSession` with
  `caretMode`). The caret moves one character of the **reading** per press (`Graphemes`: emoji sequences, flags,
  combining marks, surrogate pairs whole). Its place in the surface comes from the alignment (`Alignment.surfaceOffset`),
  never from surface length: exact on segment boundaries and inside kana-written segments; inside a word written
  otherwise (明日 for あした) that segment shows its kana while the caret is in it and its word again when the caret
  leaves untouched. Example (fixture 明日は雨): 明日は雨| → 明日はあ|め → 明日は|雨 → 明日|は雨 → あし|たは雨.
  Typing, ⌫ and 小゛゜ act at the caret in that segment only; the segment converts again (live conversion previews its
  first candidate, as in segment selection) and every other segment, the prefix and the suffix keep what they show. A
  candidate picked in the strip replaces only that segment and puts the caret after it. Back at the end with nothing
  edited or chosen, the composition is exactly what it was before the first arrow (its conversion, and typing continues
  it). Screen switches and the case key keep the caret. (The first version's "Enter commits a word shown as kana as that
  kana" and "the caret does not split a segment" are replaced by the review follow-up below.)
- Tapping a segment button still selects it, and there ◀ ▶ resize it as in 0.1.23 (kept on purpose; existing tests
  unchanged). ▲ ▼ there still do nothing.
- ▲ ▼ inside a composition go to its start and its end. **Fallback:** the keyboard has no line geometry of the editor
  (no `CursorAnchorInfo` is requested), so it cannot know a line above or below; a multi-line field gets no DPAD_UP/DOWN
  while composing.
- Editor caret: after the composing write, `setSelection(start + offset)` puts the editor's caret there (one-way like
  every write, no read, no wait). Its report is registered with `OwnReports`, so `onUpdateSelection` takes it as ours and
  neither drops the composition nor treats it as a tap; a report that is not ours still ends the composition without
  writing at the new cursor (tested). With the span's start unknown the editor's caret stays at the end (fallback),
  while typing still uses our caret.
- Stale results: kana results are bound to the segment's revision (`RepairSession.apply`) and English lookups to the
  composition's generation; neither moves the caret or undoes an edit (tested). Fields: a new field never gets the old
  composition; hiding the keyboard leaves the typed kana, as before.
- No synchronous host call was added: only `setComposingText`, `setSelection`, `commitText`, `finishComposingText`.
  Privacy rules unchanged: nothing about the caret or English typing is stored, logged or learned; index results are
  held in memory for the open composition only.

## Tests

New: `core` `ComposingCaretTest` (7: grapheme steps incl. ZWJ family, skin tone, flag, keycap, combining dakuten; surface
mapping with unequal reading/surface lengths; caret walk; edits at the caret; a choice; finish; emoji in the reading).
`app` `MixedCaretTest` (18, real service, fake editor): the 3 reproductions; A on 12-key and QWERTY with the number screen,
dictionary and bracket pairs, address fields, protected/number/phone fields; B English (typing, ⌫, case key, number
screen at the caret, ▲ ▼ ends, our own reports kept, a foreign move ends it, no commitText counted on arrows, a pick
replacing the whole composition, emoji steps, kana at the caret, stale lookups and a new field); B kana (reading vs
surface walk, middle insertion/deletion/小゛゜ before the suffix, pick of one segment, segment button resize, stale
result, emoji, new field / hidden keyboard / foreign tap). QWERTY has no arrow key in this app, so arrow behaviour there
is the same action path; it was not pressed as a key.

Changed because they pinned the old behaviour: `EnglishInputTest` `insertionsKeepTheirText…` (⌫ at the start),
`candidatesKeepCanonicalMixedCase…` (new API), `theSymbolRunIsTheTrailingPunctuation…` replaced by
`tokensAreRunsOfOneKind…`, `inASearchFieldEnterConfirms…` (`hi,OpenAI`), `punctuationDelimitsUrlsAndEmails…` (whole
address), `lettersAndDigitsAfterAFirstSymbol…` (renamed `…CoverAllOfIt`), `theEnglishOriginSurvives…` (digit forms with
the emoji), `numberScreensComposeFromEitherSide…` (`1()` whole); `InputUiTest` `explicitConfirmationKeepsVisiblePrefix…`,
`arrowsConfirmTheShownComposition…` → `arrowsNeverConfirmTheShownComposition…`, `bracketFlicksOnYaAndCursorKeys…`;
`ReleasePrepTest` `inASingleLineFieldVerticalFlicks…` (▶ at the end does nothing), `aFailedEnglishIndexLoad…` (lookup
signature); `UxPolishTest` `verticalActionsConfirm…` → `verticalActionsMoveTheComposingCaret…`.

Targeted runs during the work: first run of the new suites 51 run, 5 failed (bracket styles capped at 8 hid 「」【】; an
e-mail field opened on English and the test's mode key went to かな); both fixed, then 0 failed.

Final `scripts/build-android.ps1` with OSS resources and zenz lib/model, tasks `:core:test :oss-converter:test
:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL (`artifacts/full-0.1.34.log`). core 112,
oss-converter 105 (both up to date from this session's earlier full run with the same inputs), app 491 = 708 run, 707
passed, 0 failed, 1 skipped (`BrandRenderTest.render`, as before). Lint 0 errors, 2 known warnings
(ClickableViewAccessibility, NewerVersionAvailable).

## APK

First build, before the review (kept as `artifacts/apk/anmitsu-0.1.34-pre-review.apk`; the current APK is in the review follow-up above), 95,110,754 B, SHA-256 `bb68f9af2cb069e51b0fc38740b3e72412aa59966e9b6642f5339ca46570dbbe`,
`package-android.ps1 -Version 0.1.34 -RequireOss -RequireZenz` passed. versionName 0.1.34, versionCode 35, package
dev.astra.ime.trial, label anmitsu, no permissions, signer 63564ca0…7fca527 (same as 0.1.33). Of 55 entries only
`classes10.dex`, `classes14.dex` and `AndroidManifest.xml` differ from 0.1.33; the zenz library and model, the OSS
assets, the English index and the supplement are identical by CRC and size (the byte size equals 0.1.33's, the 16 KiB
page alignment absorbs the dex growth). All earlier APKs kept; 0.1.33's build info kept as `build-info-0.1.33.json`. Not
uploaded.

## Remaining gaps

- Not every combination of token forms: bounded search, see the review follow-up; no visual line movement for ▲ ▼ (see B).
- A kana typed into the middle of an English composition stays unconverted.
- Real editors' handling of `setSelection` inside a composing span is unverified (some may finish composing or report
  differently; then the composition ends with the text left as shown, never rewritten).

## Device checks

1. English 12-key, chat field: `m`, `-`, number screen `1`: strip starts `m-1`, has `M-1 ｍ－１ Ｍ－１ m-①`; pick `Ｍ－１`.
2. QWERTY: `openai123`: `OpenAI123` listed.
3. `abc()` ◀, `x`: `abc(x)` with the caret before `)`; pick `abc「x」`.
4. かな: `あしたはあめ` live 明日は雨, ◀ ×2, type の: suffix 雨 stays, caret before it, nothing committed; Enter commits it.
5. ◀ into 明日 shows あし|たは; ▶ back to the end shows 明日は雨 again.
6. The caret position in Chrome, Google Docs, a messaging app: does the editor keep the composing span after
   `setSelection`, and does the caret appear where expected?
7. Hold ◀ inside a long composition: repeats without committing.
8. URL bar: `m-1` gives no full-width forms; password field: arrows move the field's cursor.
9. (Review) `m-1`: `M-①`, `ｍ-①`, `Ｍ－①` in the strip; `abc()`: `ABC「」`; does the longer strip stay responsive while typing?
10. (Review) あしたはあめ, ◀ ×3: segment buttons 明日 / は / 雨 and 明日 highlighted; Enter commits 明日は雨. ◀ ×4 then Enter: 明日は雨.
11. (Review) 明日は|雨, type の, ⌫ ⌫: 明日|雨, nothing else reconverted.
