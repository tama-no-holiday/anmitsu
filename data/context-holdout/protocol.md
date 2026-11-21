# Held-out evaluation of committed context ranking (0.1.28)

Written, with `cases.tsv`, before any model or converter run on these inputs. `manifest.json` holds the hashes. Nothing here changes after the
first model run; a result that disagrees with this file is reported, not fixed.

## Data

- Claude wrote every case and its accepted surfaces. All synthetic. These are not independent human labels and no real user tried them.
- `kind`: `positive` (the prefix supports the accepted surface), `neutral` (unrelated prefix, accepted = what fits with no context),
  `contradictory` (the prefix's topic suggests another homophone; words in the typed text decide), `protected` (uncommon words, proper nouns,
  katakana, unambiguous text the context must not disturb).
- Accepted surfaces list every form considered right, kana and kanji included, before any result was seen. They do not change.
- Split by reading family: `sha256(salt + family)[0] % 3 == 0` is dev, the rest holdout. No family is in both. None shares a reading family
  or an accepted surface with `data/context-eval.tsv`.
- `data/context-eval.tsv`, its 22 target examples and the user's pair stay development examples. They are reported, and they take no part
  in selecting an option or in accept/reject.

## Options (all use the shipped model, dictionary, 8 scored, 20 listed, correction off unless stated)

`score(i) = logP(i) - perRank * i - perEdit * edits(i) + learned(i)`, as shipped, is always the base.

| name | applies only with a non-empty external context | definition |
|---|---|---|
| `baseline` | - | shipped: perRank 1.0 |
| `prior0.5` | yes | perRank 0.5 |
| `margin1` | yes | baseline order, then: among candidates reading exactly the typed tail, the one with the best `logP + learned` moves to first if that beats the baseline first's `logP - perEdit * edits + learned` by at least 1.0 nat |
| `margin2` | yes | the same with 2.0 nats (the cost of one kana edit) |

The margins were fixed as one and two round units (one rank position, one edit), not from any measured gap. `margin1` and `margin2` move at most one
candidate. `keepExactLead` runs after every option. External context means committed text passed separately, non-empty after `promptContext`;
it is never inferred from the converter's head or repaired units.

## Order of work

1. Run all four options once on `dev.tsv`. Dev may also be used to find harness bugs. A harness bug fix must not change a policy definition.
2. Select one option from dev alone: the most net positive gains over baseline (gains minus losses among positive cases), ignoring options with
   a dev protected-control regression or with more than 1 extra neutral or contradictory regression. Ties go to the more conservative: `margin2`, `margin1`,
   `prior0.5`. If none qualifies or the best net gain is 0, `baseline` is selected, the holdout is not run, and the production weights stay.
3. Run `baseline` and that one option once on `holdout.tsv`. Holdout results accept or reject. They do not tune.

## Acceptance, frozen

Regression: right with `baseline` under the same prefix, wrong with the option. Gain: the reverse. All must hold on the holdout:

1. Empty external context: order identical to `baseline` on every case and fixture, including internal heads (unit test).
2. `protected`: no regression.
3. `positive`: at least 2 gains, and gains minus regressions at least 2.
4. `neutral` and `contradictory` together: at most 1 regression.
5. Unrelated prefixes (`今日は天気がいいです。`, `よろしくお願いします。`, `ありがとうございました。`) on the 83 existing fixtures: at most 1 extra lost fixture
   over baseline, summed over the three prefixes.
6. Typo suite: the same fixtures' typed column with correction on (`EditPenalties()`), empty context and each prefix above: no regression.
7. Host latency, same warmup and repeats, interleaved: prefix p95 of the option within 15 % of baseline, first conversion and per key.

Every regression is listed and marked as shipping risk even when the option passes. If any gate fails the production weights stay at 1.0, the option does not
ship, and this holdout is not used for further tuning.

## Measures reported apart from ranking

Candidate recall (an accepted surface in the 20-candidate list) and scored recall (in the first 8, which is all the scorer sees). An accepted surface missing from
the list is a recall miss, not a scorer error; missing from the first 8 is a shortlist miss. `maxScored` stays 8.
