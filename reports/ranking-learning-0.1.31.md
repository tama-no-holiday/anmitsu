# 0.1.31 (B): context-first ranking, learning by what the user did

User-approved redesign (2026-11-02). **Nothing here ran on a device.** Model, dictionary, native library, icons, themes,
package and signer unchanged. No setting added: the approved priorities are expressed by the rules below, so the
optional "Context-first / Learned-choice-first" switch was not needed.

## Ranking (`ZenzRanking.Weights.contextFirst`, on in `OssBackend.RANKING`)

- The dictionary still generates the candidates (top 20, first 8 scored by zenz, `ZENZ_SCORED`, unchanged).
- With **meaningful committed context** (at least one letter or ideograph survives `promptContext`) and a working model,
  the exact-reading candidates are ordered among themselves by the model's log-probability, plus the learned bonus
  (3 nats) only for strong words (explicit choices, registered words). Dictionary rank only breaks exact ties. The rank
  prior (`perRank` 1.0) and `margin2` no longer act on them. Before, 歯科医 at dictionary rank 7 needed to beat 司会 at
  rank 3 by 4 + 2 nats; the model's 1.49-nat preference lost.
- Corrections (edit distance > 0) keep their old slots and costs; `keepExactLead` still puts an exact reading first
  unless the dictionary found the correction worth leading. Nothing is rewritten or committed automatically.
- No context, context of punctuation/digits only, context switch off, private fields: the 0.1.30 rules.
- A failed, short, NaN or infinite score: the dictionary order (new check; before, NaN sorted arbitrarily).

## Learning provenance

| Action | Strength | Effect |
|---|---|---|
| 確定 / Space / tap of the candidate ranked first (live or not) | implicit | phrase recorded weak; no word bonus; never put first; an existing stronger entry is left untouched (count and recency too) |
| 変換 to another candidate then 確定, tap of another candidate, panel pick, prediction | explicit | phrase first (yields second only by the 0.1.29 context rule); its words not in the default conversion get the bonus and count as strong |
| Segment chosen in segment selection (locked) with a word the default lacked, or without alignment (kana/katakana) | explicit | as above |
| Only selecting, cycling, resizing, editing, opening the panel, cancelling, moving the cursor | none | nothing learned (unchanged) |
| English words | none | English input never learns (unchanged, verified: only the two Japanese commit paths call `learnCommit`) |

- Tapping the unchanged first candidate is classified **implicit**: it confirms the ranking and gives no new information.
- Words already in the default conversion are no longer reinforced, even if learned before (0.1.30 did reinforce them,
  which let an accepted default harden itself).
- Registered words: in generation as before (cost bonus, added entries); strong in context ranking; a phrase keeps first
  place only if it contains a word registered **for part of its reading** (0.1.29 checked any registered surface as a
  substring of any phrase).
- Stored: `provenance.tsv` (kind, reading, surface, e/i) beside the unchanged `words.tsv`/`phrases.tsv`, so older
  versions still read the history. No context, sentences, timestamps or attributes.

### Migration

Existing entries have no provenance and load as **legacy**; nothing is deleted or rewritten on load. Legacy phrases go
first as before when the model is not ranking with context; with meaningful context they stay where the model puts them.
Legacy words keep their dictionary bonus but are not strong. A legacy entry never becomes explicit by being accepted; an
explicit choice upgrades it. Learning default on, stored off respected, clear erases words, phrases and provenance, never
registered words (tested).

## Real-model diagnostic (`ossCli ranking-policy-diag`)

Same `rerank`, `keepExactLead` and `LearnedOrder.place` as the app (supplement and literal choices not included). Learned
states are what `LearningStore` keeps after each action. Cases written before any rule was tuned; no weight was tuned on
them afterwards. Dev `data/ranking-policy-dev.tsv` (6, overlaps 0.1.29's diagnostic), validation
`data/ranking-policy-validation.tsv` (8, new). Outputs `android/build/claude-artifacts/policy-{dev,validation}.tsv`.

Reported case d1 しかいをつとめます (scores in nats, 司会 / 歯科医):

| context | no learning 0.1.30 → 0.1.31 | implicit 歯科医 ×5 | explicit 歯科医 | legacy 歯科医 |
|---|---|---|---|---|
| none (-0.13 / -4.66) | 司会 → 司会 | 歯科医 → **司会** | 歯科医 → 歯科医 | 歯科医 → 歯科医 |
| 私はアナウンサーです。 (-0.07 / -6.35) | 司会 → 司会 | 司会 → 司会 | 司会 → 司会 | 司会 → 司会 |
| 私は歯医者です。 (-1.89 / -0.40) | 司会 → **歯科医** | 歯科医 → 歯科医 | 歯科医 → 歯科医 | 歯科医 → 歯科医 |
| 今日はいい天気です。 (-0.25 / -4.06) | 司会 → 司会 | 歯科医 → **司会** | 歯科医 → 歯科医 | 歯科医 → **司会** |

Context-matched first place, no learning (A with A's context, B with B's):

| set | 0.1.30 | 0.1.31 | changed |
|---|---|---|---|
| dev (12) | 8 | 10 | d1 B, d5 B gained; none lost |
| validation (16) | 10 | 10 | none |

- No-context rows without learning: identical in all 14 cases.
- **Tradeoff, nonmatching neutral context** (今日はいい天気です。, no learning): the first candidate changed in 4 of 14 cases,
  d2 記者 → 帰社, d6 鑑賞 → 観賞, v2 成果を上げました → 成果をあげました, v3 回答 → 解凍. With B's context d2 gives 帰社 (not 汽車)
  in 0.1.31 where 0.1.30 kept 記者: the model, not the dictionary, now decides, and it is wrong there.
- Model limits not addressed: v6 仕様 never first (the model prefers 使用 even after 設計書を開いた。), v1/v2/v4/v5/v8 B
  context too weak for the model.
- Implicit repetition no longer pins B in any row (0.1.30 pinned it everywhere except where context outweighed it).
  Explicit B: identical to 0.1.30 in all 56 rows. Legacy B: identical without context, the model's order with context.

### Coverage and latency

- Exact-reading candidates in the top 20: dev 120, 72 beyond rank 8; validation 160, 96 beyond rank 8. Those are never
  scored and the model cannot choose them. Of the 28 target surfaces, 0 missing and 1 beyond rank 8 (v2 青果, rank 10,
  so 青果 with 八百屋 context is out of reach). General remedy, not done: in context-first mode also score exact-reading
  candidates beyond 8 (same reading, one shared prompt and KV cache), bounded to a few more.
- Rerank only, host, 4 threads: p50 48.6 / 53.3 ms, p95 69.5 / 75.6 ms (dev / validation, 96 / 128 reranks). The new
  rule adds no prompt; phone time unknown.

## Tests

- oss-converter `RankingPolicyTest` (8): context-first vs 0.1.30 rules, corrections keep their slot, ties, strong words,
  context without letters, NaN/infinite/short/failed scores, unscored candidates stay, phrase placement by strength,
  latest explicit wins, registered word bound to its reading.
- app `LearningTest` +3: implicit repetition never pins or adds bonus, explicit correction and its replacement, legacy
  files load unchanged and are not rewritten on acceptance, provenance survives restart, clear keeps registered words.
- app `InputUiTest` +1 in the real service: 確定 of the top is weak, 変換 alone records nothing, 変換 then 確定 is explicit.

## Limits

- Neutral or misleading context now moves first place more often (above). Without a frozen held-out set larger than
  these 14 cases, the size of this effect in real typing is unknown.
- "Meaningful context" is checked on the left context including repaired segments before the composition.
- Explicit vs implicit compares with what was ranked first, not with what was on screen: with live conversion off,
  tapping the first candidate is implicit although the screen showed kana.
- Phrase strengths are per (reading, surface); an older version writing history in between leaves those entries legacy.
- Segment explicitness compares a locked unit's words with the whole default conversion's words.
