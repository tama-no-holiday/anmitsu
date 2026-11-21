# Addendum, written after the dev run and before any holdout run

`protocol.md` and the case files are unchanged (`manifest.json`). This file changes no gate and no option.

## Selection from dev, by the frozen rule

`reports/context-policy-0.1.28-dev-summary.txt`. Positive cases: `prior0.5`, `margin1` and `margin2` each gain 3 and lose 0. None has a protected,
neutral or contradictory regression. The tie goes to the more conservative, so **`margin2`** is the one option run on the holdout, with `baseline`.

The dev run does not count `prior0.5`'s fixture losses, because the selection rule does not mention them. Reported, they also lean against it: it lost 3 fixtures
under two neutral prefixes, `margin1` and `margin2` none.

## Scores depend on the prompt cache

Found on dev: baseline gave 77 of 83 on the fixtures with `今日は天気がいいです。` and 74 with `ありがとうございました。`, the reverse of the earlier `context-eval` run.
`ossCli context-stability` on 284 fixture and prefix inputs (`android/build/claude-artifacts/stability.log`):

| pass against the first | candidate lists differ | orders differ | scores off by over 0.001 | largest difference |
|---|---|---|---|---|
| same order again | 0 | 0 | 0 | 0.0000 |
| reversed order | 0 | 0 | 67 | 0.3345 |
| prompt cache moved off first | 0 | 3 | 177 | 0.6981 |

The candidates are stable. The scores depend on what the native prompt cache held. The cause was not established. `android/zenz-native` is unchanged by this work, so this predates it.

## Consequence for the holdout

Every option in a run sees the same memoized scores, so option against baseline stays a paired comparison. The gates of `protocol.md` are judged on that run as written.
In addition the run repeats top-1 with each scoring preceded by moving the prompt cache off (`--cold-check`). That result is sensitivity only: it cannot pass a failed gate. If it reverses a passed
gate, that is recorded as shipping risk.
