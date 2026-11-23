# Context at the cursor, and learned phrases against it (0.1.29)

**Result: the failure reproduced in the real service and is fixed there, in JVM tests with scores replayed from the real model. Two causes were confirmed and both had to be fixed. The input sequence behind the reported screen, and the learned state at that time, are not known. Nothing here ran on a device.**

Code: `CommittedContext.kt`, `Learning.kt`, `ConversionWorker.kt`, `OssBackend.kt`, `AstraImeService.kt`, `ZenzRanking.kt`. Diagnostic: `ossCli context-learning-diag`, `data/context-learning-diag.tsv`. Inputs are fixed example strings; no field text or learning store from a device was used. Paths under `android/build/` and `artifacts/` are local build outputs, not distributed.

## The report

Typed 私はアナウンサーです, 私は歯医者です, しかいをつとめます. Shown: `私はアナウンサーです。歯科医を務めます` with the composition ending there, then a line `私は歯医者です。`. 司会を務めます is in the list but 歯科医を務めます is first.

## Confirmed and not confirmed

| | status | evidence |
|---|---|---|
| Moving the cursor erased context | **confirmed in code and test** | every moved-cursor test got `""` before the fix: the shadow forgot on any report it could not match |
| A learned phrase is pinned first after ranking | **confirmed in code and test** | `LearningSnapshot.arrange` puts it at index 0 whatever the ranking said. `theFailureAsReported` gives `私はアナウンサーです。歯科医を務めます私は歯医者です。` on the old code, the reported text |
| The model or dictionary put 歯科医 first | **ruled out for this input** | without learning it is never first, in any context (table below). It is in the list (dictionary rank 7, or 0 to 2 once learned) |
| The dentist sentence was still context at the announcer cursor | **not the cause on a normal host** | a report that fits nothing forgets. Reachable only on a host whose old offsets do not continue, which the shadow ignored as overtaken. Closed anyway |
| 歯科医を務めます had been learned as a phrase in the reported state | **hypothesis** | it is the only route found to that screen at that cursor, and picking it in an earlier try would record it. No learning store was inspected, and no device was involved |
| The cursor was moved back | **hypothesis** | the layout suggests it; the input sequence is unknown |

## Real model, isolated factors

`zenz-v3.2-xsmall-Q5_K_M`, the real dictionary, correction on as the app uses it, canonical scorer. Log probabilities for しかいをつとめます, and the first candidate for each state. Output `android/build/claude-artifacts/ctx-learn-diag-after.tsv`.

| context | 司会 | 歯科医 | no learning | learned words only (歯科医) | phrase pin (old) | contextual phrase (new) |
|---|---|---|---|---|---|---|
| none | -0.13 | -4.66 | 司会 | 司会 | **歯科医** | **歯科医** (kept: nothing against it) |
| 私はアナウンサーです。 | -0.07 | -6.35 | 司会 | 司会 | **歯科医** | 司会, 歯科医 second |
| 私は歯医者です。 | -1.89 | -0.40 | 司会 (歯科医 third) | **歯科医** | **歯科医** | 歯科医 |
| 今日はいい天気です。 | -0.25 | -4.06 | 司会 | 司会 | **歯科医** | **歯科医** (kept) |

So:

- **Candidates are not the problem**, nor is the prompt, once the right text reaches it: with the announcer sentence the model's lead for 司会 grows from 4.53 to 6.28 nats.
- **The only states that give the reported screen** are the phrase pin, in any context, or learned words with the dentist sentence as context.
- **Neither fix works alone.** Restoring context without touching the pin still shows 歯科医. Weakening the pin with the context still empty changes nothing either, since a phrase needs context against it. Removing the pin outright would fix this input and destroy learning: 歯科医 would no longer come first with no context.
- **The model itself stays limited.** With the dentist sentence and no learning, 歯科医 leads the model's scores by 1.49 nats and is still third, below `margin2`. That is the known result of 0.1.28 and was not touched.

Unrelated homophones, 14 rows, 4 contexts, 3 learned states, 168 rows, 112 with a learned phrase:

| learned phrase in | rows | moved to second |
|---|---|---|
| no context | 28 | 0 |
| context agreeing with it | 28 | 0 |
| context against it | 28 | 10 (司会×2, 公園, 髪, 鑑賞, 進行, 暑い, 花, 制作, and 信仰 the other way) |
| a neutral sentence (今日はいい天気です。) | 28 | 4 (公園, 髪, 進行, 暑い) |

The other 18 against-it rows keep the phrase: the model's evidence is too weak or does not come from the context (記者/汽車, 橋/箸, 紹介/照会 among them). The 4 neutral rows are the cost: a sentence chosen as neutral still shifted the model. 天気 before あついです is arguably right; 進行 over 信仰 after a weather sentence is simply the model. In all of them the phrase is one tap away.

The two limits were set on this same small set and are round numbers, so **these counts are development results, not a held-out estimate**. The frozen holdout has no learned phrases and says nothing about them.

## The fixes

**1. Context is what precedes the current cursor.** `CommittedContext` now holds a stretch of the field by absolute offset, at most 512 UTF-16 units, instead of a tail of the text. Own commits are spliced in at the cursor, so later text moves along. An unexplained plain cursor inside that stretch is a move and the context is read from there. Outside it, nothing is known and nothing is invented.

| event | before | now |
|---|---|---|
| plain cursor move inside what is known | forgot all | context from that offset |
| arrow key | forgot all | empty until the report, then the same |
| ⌫ on committed text | forgot all | keeps what precedes the reported cursor, drops what followed |
| cursor outside what is known | forgot | forgets |
| selection, foreign span, cursor leaving a composition | forgot | forgets |
| paste, refused write, editor action, hide, finish, delete of a selection | forgot | forgets |
| cursor at offset 0 | forgot | forgets: it is also what a cleared field reports |
| report taken as overtaken | ignored by the shadow | forgets: it could be a move |

Still no read of any kind: the only sources are `EditorInfo`'s snapshot and accepted own commits. Every editor in the tests throws on a read. Password, private, URL and similar fields hold nothing, as before.

**2. A learned phrase is a default, not a rule for every sentence.** With committed text before the cursor, `ZenzRanking.contextOutweighs` compares the phrase with the first exact-reading candidate. The phrase takes second place only if the model prefers that candidate by `phraseGap` = 3.0 nats with the context, **and** that lead grew by `phraseShift` = 1.5 nats against no context. The second condition is what keeps learning: a surface the model merely finds rare stays the user's choice (紹介 over 照会 leads by 9.4 with context and 9.8 without, so the phrase stands).

- No context, context off, learning off, private field, no judge: exactly the old behaviour, no extra prompt.
- Failed or missing scores: the phrase stands.
- A phrase containing a registered word never moves. Registered words are not otherwise touched.
- Word bonuses, `learned`, `margin2`, `perRank`, the model and the native library are unchanged.
- Learning stores what it stored. Context still never reaches it, and no attribute of the user is inferred or stored.

**Cost:** up to two more prompts of two candidates each, only when the whole typed reading equals a learned phrase and there is committed context, and the second runs only if the first passes. Off the main thread, like the rest. Not measured on a phone.

## Reproduction matrix

Real service, worker and `ZenzRanking`; fake editor that fails on any read; tiny dictionary; `theFailureAsReported` and its neighbours replay the real scores above. **This shows behaviour given those scores, not model accuracy.**

11 tests failed on the unchanged code (`ctx-before-fix.log`), all with an empty context or the reported text; 10 for the reason shown and `textInsertedInTheMiddle...` first on a flaw in its own wait, corrected afterwards. The rest of the new tests passed before and must stay passing. Added after that run, so not run on the old code: the two limit tests below and the unit tests of the new functions, which could not compile there.

Covered: announcer then dentist with the cursor back; reverse order; appended after the last; back and forth; inside a sentence; insertion in the middle; a line between; arrows; the field's own snapshot; ⌫; selection; outside what is known; offset 0; the bound; paste; private and off fields; restart in the middle; another field; a host whose offsets do not continue. Learning: off, phrase against, for, or neutral to the context, no context, context off, another reading, a registered word, a choice against the context still learned and without the context.

**Existing tests changed, on purpose,** because they pinned the old behaviour: `aMovedCursorForgets...` (now reaches outside what is known), `arrowKeysForget`, `deletingCommittedTextForgets...`, `aReportFarFromOurCommitForgetsItToo` (that offset's text is right), `reportsOvertakenByANewerOneChangeNothing` (composition kept, field text forgotten).

## Final run

Library `91a88be6…` from `android/build/zenz-android-0.1.28`, matching the lock; no native rebuild; model, dictionary and weights unchanged. Test tasks cleaned first, two consecutive runs, both exit 0, counted from the XML of the second. **JVM only.** Logs `android/build/claude-artifacts/final-0.1.29-*.log`.

| module | run | failed | skipped |
|---|---|---|---|
| core | 101 | 0 | 0 |
| oss-converter | 96 | 0 | 0 |
| app | 414 (`CommittedContextTest` 128) | 0 | 1 (`BrandRenderTest`) |
| **total** | **611** | **0** | **1** |

`:app:assembleDebug` OK. `:app:lintDebug` 0 errors, the 2 known warnings. An earlier full run failed one new test, `aHostWhoseOffsetsDoNotContinue...`: its offset had aged out of the 8 entries kept, so it was an ordinary move and got the right context. The test was rewritten to reach that branch. No product change.

**Frozen holdout, rerun as regression only, nothing tuned on it:** file hashes unchanged, `android/build/claude-artifacts/holdout-0.1.29.tsv` identical to `reports/context-policy-0.1.28-canonical-holdout.tsv`, 289 of 289 rows. That is expected and proves little: `rerank` did not change and the holdout has no learned phrases. `margin2` keeps its 60 -> 64 of 84.

Mutation testing was not run for this version.

APK `artifacts/apk/anmitsu-0.1.29.apk`, 95,060,474 bytes, SHA-256 `e00be8d45e44989942794924d1ad4455e289586892c0d2cd5aad1938d83889a6`, versionCode 30, no permissions, arm64-v8a, min 26, target 36, zipalign -P 16 OK, v2 verified, signer `63564ca0…7fca527` unchanged. Library, model, `lexicon.bin`, `connectionId.dat` and the manifest match their locks. Against 0.1.28 only `AndroidManifest.xml`, `classes3/6/14.dex`, `resources.arsc` and signing files differ. It is 169 KB smaller than 0.1.28 although its entries are 10 KB larger: the difference is zip padding, not content. `anmitsu-0.1.28.apk` is unchanged; the old `build-info.json` is kept as `build-info-0.1.28.json`.

## Device checks

1. The library loads and conversion works; it has never run on a device. Typing does not stall.
2. **The reported case, without clearing learning:** in one field type 私はアナウンサーです。, a new line, 私は歯医者です。, tap back after the first 。 and type しかいをつとめます. Expect 司会を務めます first and 歯科医を務めます second. Then undo that input, tap after the second sentence, type it again: expect 歯科医を務めます first if it was learned, as the reported state suggests.
3. In an empty field the same reading: a learned 歯科医を務めます stays first. If it does not, it was never learned, and the learned-phrase hypothesis above is wrong.
4. Back with ◀ instead of a tap: same result as 2.
5. ⌫ one character at the moved cursor, then type: context still works, no stall.
6. Select text, paste from the host's menu, send a message and keep typing: nothing odd in candidates; a wrong first candidate here means outdated context.
7. Repeat 2 in Chrome and in a messaging app. Reports there are unverified.
8. Turn 確定済みの前の文 off: a learned phrase is first everywhere. Turn 学習 off: no phrase. 学習内容を消去 still clears phrases and leaves registered words.
9. Password field and incognito: no context, and no learning, as before.
10. Whether a phrase dropping to second in a neutral sentence (4 of 28 here) is acceptable in real use.

## Constraints

- **A cursor move cannot be told from an edit that lands like one.** A host that pastes from its own menu in the middle of known text, then reports a cursor inside that stretch, gives outdated context. Pinned by `anEditThatLandsLikeAMoveInsideWhatIsKnownIsNotDetected`. Only offsets are visible, and asking the field is ruled out. The effect is one ranking with old text; nothing is written or stored. This is a wider exposure than 0.1.28, which forgot on every move, accepted in exchange for context that is correct in the ordinary case.
- **Not all positions are known.** Moving forward past the snapshot, reaching back past 128 units of it or past 512 of tracked text, or after a selection, gives no context, so a learned phrase stays first there. API 26 to 29 have no snapshot, so only text typed in this field session counts.
- **Moving out of an open composition forgets**, because the service writes the kana back and the host moves the cursor (existing behaviour, not changed).
- **The model decides how often this helps:** 18 of 28 contradicted phrases stayed first, and 4 of 28 neutral ones moved. How that feels in use is unknown.
- How real hosts report moves, WebView and Chrome above all, is unverified.
