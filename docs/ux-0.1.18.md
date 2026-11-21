# 0.1.18-trial: every exact candidate, prediction first when supported

> **Historical development record** of the 0.1.18 trial, published with the 1.0.0 source because the source cites it.
> It does not describe 1.0.0 (see [README.md](../README.md)); later trials changed the panel and keys. Local logs,
> earlier records and the 0.1.19 record it mentions are not published.

Base 5271f17. Written on a separate development branch, then applied unchanged to the main development branch together
with the mode-key change below. Nothing here ran on a device.

Historical, with corrections. The final 45df479 (with the mode key) reports app 224, so 396 tests in all, not the 223 and 395
below. Its APK is 94,815,578 bytes, SHA-256 8fe56033…7df674; the figures first recorded in the development log (not
published) were the earlier build. 0.1.19 replaced the panel layout described below (入力どおり is now 変換, no counts,
both groups sideways) and two English keys (its record is not published).

## Mode key and digits (added on the development branch)

- The mode key toggles かな ⇄ English only (label: ABC on かな, あ on English). Numbers left the tap cycle.
- A still hold on that key (the system long-press timeout, 400 ms by default) opens numbers at once; a corner hint reads
  123. The release after a hold sends nothing. A finger that leaves the center zone first is a flick, as before, and
  never opens numbers. On numbers the mode key goes back to the screen they were opened from (あ or ABC) and has no hold.
- English 12-key down flicks are digits in phone-pad order: @#/& → 1, abc → 2 … wxyz → 9, '"() → 0. Letters and the other
  flicks are unchanged. The down flicks of `_` and `-` gave way to 1 and 0: `-` stays on the number pad, and `_` moved to
  the number pad's decimal key (down flick, replacing a duplicate `=`). Both remain on the 123 rows.
- Tests: `EnglishInputTest` (real touch events: digits by down flick, hold vs tap vs slide-off on the mode key, return
  screen), `PaletteLayoutTest` (down flicks in every shift state), updated cycle expectations in `UxPolishTest`. All numbers are host JVM on development data, not a blind accuracy measurement.

## Why 皿 was missing

Reproduced on the shipped `compiled-f11000` dictionary. さら has 15 exact surfaces, 皿 among them. With zenz the app uses
edit penalties at scale 1, and there only 2 of the 15 came back.

Loss, in pipeline order:

| Stage | What happened |
|---|---|
| Lexicon | Fine. All 15 are stored. |
| Lattice | Fine. The fuzzy lattice holds them at penalty 0. |
| Best path | される's され plus the edit penalty beat 更, so the whole-path winner was a correction. |
| Variants | `alternatives()` kept 24 nodes reading the *path's* key. さら's words were "another reading" and shared 4 slots. **This is the root cause of the report.** |
| Variant cap | The 24 slots counted lattice nodes, not surfaces, so POS duplicates used them up (かみ 17 of 23 with correction off). |
| Merge | `corrected` meant "differs from the best path", so the typed reading sorted as a correction. |
| Limit | The candidate cap of 20 cut the rest, and fragments such as は死 came before whole words (はし 9 of 14; こう 20 of 90). |
| zenz | Scores log P(surface given its own reading). These are not comparable across readings, so a typo fix can outrank what was typed. It can reorder, not restore. |
| UI | The panel listed `session.candidates`, so at most 20. |

The 0.1.13 caps of 24 + 4 were per span and per path key, which is why they did not help.

## What changed

**Exact list.** `OssConverter.exactCandidates` reads the dictionary directly: one trie lookup plus that reading's postings,
no scan and no correction, ranker or count limit. Unique surfaces, cheapest Viterbi local cost first, ties by surface, so
the order is stable. Learning only lowers costs; registered words join the list. It runs when the panel opens, on the
conversion thread, cancellable, never per key press.

Two kinds of "exact", kept distinct:

- **Whole-reading lookup**: every dictionary entry for the entire typed text. This is the "all" guarantee.
- **Segmented paths**: for text with no such entry (or as extra lines after them), the best exact path plus every
  same-reading replacement of *one* word in the variant window. Linear, never the exponential product. Text before the
  window (48 characters) stays at its best spelling. The header says 単語ごとの候補 for these.

A focused repair unit uses the same lookup, so a word with its particle (橋が) lists every homophone of the word.

**Panel (▼).** One panel where the keys were, keeping their height (at least two exact rows on short landscape keys, at
most 60% of the screen). No tabs.

- 予測・補正, top, two rows, scrolls sideways: the rankers' first choice, then corrections and completions.
- 入力どおり N件, below, scrolls vertically: raw kana, katakana, learned phrases the dictionary lacks, then every exact
  word. Long entries wrap.
- Both show at once and scroll independently. `CandidateSectionView` draws only visible candidates on one canvas, no View
  per candidate. The first move past the slop locks the gesture: along the axis it scrolls, across it nothing happens, and
  a drag, second finger or cancel never commits. Only an unmoved tap on one candidate picks it.
- ▼ also works in repair mode, for the focused unit; a choice there locks the unit and commits nothing.
- While open, the strip keeps only the raw chip and ▲.

**Classification** (`CandidateGroups`) uses aligned segments, then readings, never the surface. A wrong reading on an
aligned span is a correction even when longer (さらい for さら is a dropped kana, not a completion). Only an unaligned
longer reading is a prediction. The chosen late word carries its span, so it is learned and repaired like any dictionary word.

**Collapsed strip and live conversion.** Prediction leads only when supported, otherwise exact leads. さら at scale 1 was
され サレ 去れ …, now 更 皿 され さら サラ …. The list stays capped at 20; the panel is what guarantees access. Corrections past
the cap go to a separate list of at most 12. Dictionary completions appear only in the panel, never per key press.

## How certainty is approximated

The model gives no calibrated confidence and none is pretended. A correction leads only if its path total, edit penalties
included, is at least 2,500 Mozc cost units below the exact path. Otherwise the exact path leads and the correction stays
near the front, placed after the first alternative. With zenz, a reorder cannot put a correction first unless that test
passed. The margin is one development-tuned number, not a probability, and it cannot tell a real slip from a rare exact word.

Development fixtures (63 cases: 15 typos, 48 clean; dictionary same at every row):

| Path | Typo fixed first | Clean top-1 | Clean reread as something else |
|---|---|---|---|
| Dictionary, scale 1, before | 9/15 | 40/48 | 6 |
| Dictionary, scale 1, after | 6/15 | 40/48 | 1 |
| Dictionary, scale 2 (the app without zenz), before | 4/15 | 40/48 | 1 |
| Dictionary, scale 2, after | 4/15 | 40/48 | 1 |
| zenz host, scale 1, no guard | 8/15 | 40/48 | 2 |
| zenz host, scale 1, guard 2,500 | 6/15 | 41/48 | 1 |

This is a deliberate trade: on the zenz path, 2 fewer typos fixed first for 1 fewer clean reread. Those corrections are still
offered, one tap away. The sample is small; 500 kept 8/15 with 2 rereads, 1,500 gave 6/15 with 2 (both measured just before the last ranking fix, not rerun). "zenz, no guard" already
includes the variant fix, so it is not the old build. Top-20 at scale 2 fell from 53 to 52: one correction left the capped
strip and is in the panel's separate list. No claim of better neural quality is made.

## Counts on the shipped dictionary

`ossCli exact-eval`; the UTF-8 reports were written to a local build directory and are not published. Dictionary:
1,992,270 readings, 2,637,097 unique reading-surface pairs. Largest homophone set 331 (ゆうき); 2,151 readings over 20,
1,635 over 24, 88 over 100, none over 500.

| Reading | Dictionary | Ranked before (off/scale 1/scale 2) | Ranked after, capped at 20 | Exact list after |
|---|---|---|---|---|
| さら | 15 | 15 / 2 / 15 | 15 / 15 / 15 | 15 |
| あし | 10 | 10 / 10 / 10 | 10 / 10 / 10 | 10 |
| はし | 14 | 9 / 9 / 9 | 12 / 12 / 12 | 14 + 32 segmented |
| かみ | 23 | 17 / 17 / 17 | 20 / 20 / 20 | 23 |
| せい | 40 | 16 / 16 / 16 | 20 / 20 / 20 | 40 |
| こう | 90 | 20 / 20 / 20 | 20 / 20 / 20 | 90 |
| しょう | 82 | 17 / 17 / 17 | 20 / 20 / 20 | 82 |
| き | 33 | 18 / 18 / 18 | 20 / 20 / 20 | 33 |
| し | 25 | 19 / 19 / 19 | 20 / 20 / 20 | 25 |
| ひ | 20 | 16 / 16 / 16 | 20 / 20 / 20 | 20 |
| か | 32 | 20 / 20 / 20 | 20 / 20 / 20 | 32 |

Sweep: every reading over 20 surfaces plus every 400th other one, 7,127 readings: exact list equals the dictionary read
directly, no duplicates, 0 failures. The 200 costliest single-kanji entries were all reachable.

Host JVM, warm, 200 rounds over 13 readings, not a phone: `exactCandidates` p50 0.08 ms, p95 0.8 ms, max 2.0 ms;
`completions` p50 4.7 ms, p95 13.2 ms, max 20.7 ms.

## Limitations

- "All" means all entries of this dictionary, not every kanji or reading ever spelled. 3,468 distinct kanji have a
  single-kanji entry (3 outside the BMP), 11,179 reading pairs. し has only 25 surfaces. A kanji that exists only inside
  compounds cannot be chosen alone.
- Mozc's `single_kanji` data is not in this build: upstream's builder is excluded and the file is not in the local Mozc
  cache, which holds dictionary files only. Adding it needs a new pinned source, a rebuild and new locks, so it was not
  done. No readings were invented and the dictionary format is unchanged.
- Fragment lines (は死) still follow whole words in the exact list. They are exact, so they stay.
- A full sentence lists one-word replacements only: changing two words takes repair, one unit at a time.
- Candidates in the canvas sections are not separate accessibility nodes, as with the palette; the chip panel they replace
  was. TalkBack users lose individual candidates in the panel until virtual nodes are added.
- Completions come from a walk of 5,000 trie nodes, breadth first: short words, not long rare ones.
- Touch feel, row sizes, landscape height and phone latency are unverified without a device.

## Tests

Added: `CandidateGroupsTest` (core), `ExactCandidatesTest` (oss-converter), `ExactPanelTest` and `OssBackendTest` cases
(app). Final run: core 87, oss-converter 85, app 223 = 395 passed, 0 failed. Negative control: 6 mutations (exact list cut to
24, old variant bucket, classification without alignment, no direction lock, drag commits, stale lookup accepted); all
caught, the last only after adding a test for a reopened panel.
