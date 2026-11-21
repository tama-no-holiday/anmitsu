# Why zenz scores moved with typing history, and the fix (0.1.28)

**Result: the prompt cache was not stale. The scores moved because batch shape followed the cache, and batch shape changes the numbers. Scores no longer depend on history on the host, at a latency cost. Not run on arm64.**

Host only: Windows, shipped model `zenz-v3.2-xsmall-Q5_K_M.gguf` (`00c64b3d…`), llama.cpp 0.7.0, unmodified. All inputs synthetic.

## Cause

1. llama.cpp resolves `flash_attn = auto` to flash attention on this model, and a batch takes a different code path from a single row. The first difference in a row is about 1e-7 relative without flash attention and 3e-5 with it. Neither is a logic error.
2. Q5_K weights quantize their input to 8 bits per block of 256 (`quantize_row_q8_K`). A difference near 1e-7 can carry one value across a rounding boundary.
3. That flip is a step, not noise, and the next layers amplify it. Traced node by node with `cb_eval` (K/V F32, no flash attention, whole batch against single tokens): layers match at 1e-7 until the first flip, **exactly 1 of 512 values** in `ffn_norm-4` at position 13, no sign flip, then `ffn_up-4` is 2.6e-3 off, the next quantized input has 138 flips, and the logits end about 1e-2 off. Position 15 starts the same way, with 1 flip in `ffn_norm-1`.
4. The scorer decoded only the tokens after the cached prefix, so the batch a token went through depended on what came before. That is the whole of the history dependence.

Switching off flash attention and using F32 K/V reduced the whole-batch difference but did not remove it (0.45 to 0.16 nats on these cases), and F32 doubles the K/V memory. It would treat a symptom, so it was not done.

## Stale cache: ruled out

The position, prefix, sequence-removal and candidate-branch logic was kept as it was; only batch boundaries and the reuse point changed. With canonical batches, a scorer after any history matches an empty-context reference to 4e-6 nats, which is float against double arithmetic in the check, the same in every state. Stale logits look nothing like this: a mutation keeping the previous prompt's logits fails by 22.5 nats.

## Fix

`zenz_scorer.cpp`: sequences decode in blocks of 8 from their start, and a cache is reused only up to a block boundary, so a token's batches depend on the input alone. Prompt cache and candidate prefix sharing both stay. The size comes from the speed table below, which uses no labelled case.

Dropping the cache was rejected: it would re-decode up to 352 prompt tokens per key. One token per decode is the simplest canonical shape, but costs +76 % per key in the table below.

| decode, 4 threads, scratch bench (8 candidates per key) | first p50 / p95 ms | key p50 / p95 ms | history independent |
|---|---|---|---|
| old | 18.9 / 22.4 | 39.9 / 44.7 | no |
| 1 | 27.0 / 34.2 | 70.1 / 78.4 | yes |
| 2 | 24.6 / 30.9 | 62.1 / 71.1 | not checked |
| 4 | 22.9 / 27.9 | 51.8 / 57.7 | yes |
| **8** | 22.8 / 29.7 | 44.9 / 52.0 | **yes** |
| 16 | 22.1 / 25.3 | 46.0 / 51.8 | yes |
| 32 | 23.0 / 25.5 | 46.9 / 54.2 | not checked |

Block 8 and block 16 do not equal a one-token reference, because a batch of 8 or more takes another attention path; blocks 1 and 4 do. What matters is that no state differs from another. Nothing suggests one canonical shape is more accurate than another.

**Latency cost**, measured directly: real converter and JNI, `data/context-eval.tsv`, 3 interleaved runs of each library, warmup 1, repeats 3, ms, host only, averages of the runs. Drift between runs of one library was up to 5 ms at key p50 and 8 ms at first p50. The earlier report's figures (first p50 57) against this holdout rerun (79) overstate the cost: they were measured hours apart, and this drift is why the table below interleaves.

| | first p50 | first p95 | key p50 | key p95 |
|---|---|---|---|---|
| 0.1.27 scorer, empty | 53.9 | 72.9 | 28.0 | 56.4 |
| fixed, empty | 57.6 | 77.0 | 31.7 | 58.2 |
| 0.1.27 scorer, prefix | 58.3 | 80.6 | 28.4 | 55.6 |
| fixed, prefix | 66.8 | 91.3 | 31.6 | 59.8 |

So about +15 % on a first conversion with context and +11 % at key p50, +7 % at key p95. **Not measured on a phone, where the ratio can differ.**

## Measurements

`zenz-cache-check` (`android/zenz-native/src/zenz_cache_check.cpp`), 11 cases, 35 candidates, including 40 emoji plus a 64-kana reading (past 256 KV cells). States: fresh open, warm identical, after an unrelated prompt, after a prefix prompt, candidates reversed. Output: `reports/zenz-cache-check-0.1.28.txt`.

| scorer | threads | worst against reference, nats | states differing bit for bit | verdict |
|---|---|---|---|---|
| 0.1.27 source | 4 | 1.53 | 34 of 34 compared, up to **2.70** | FAILED, as it should |
| stale-logits mutation (scratch) | 4 | 22.47 | 102, up to 22.47 | FAILED, as it should |
| final | 1, 2, 3, 4 | 0.000004 | 0 | PASSED, same summary at 1 and 4 |

The 0.1.27 scorer's 2.70 is on the long case; short inputs stayed within the 0.70 reported earlier. **So 0.70 understated the old effect: on long input it was above the 2.0 margin.**

`ossCli context-stability`, 284 fixture and prefix inputs through the real converter and JNI:

| pass against the first | 0.1.27 orders differ | max delta | final orders differ | max delta |
|---|---|---|---|---|
| same order again | 0 | 0.0000 | 0 | 0.0000 |
| reversed | 0 | 0.3345 | 0 | 0.0000 |
| prompt cache moved off first | 3 | 0.6981 | 0 | 0.0000 |

## Frozen evaluation, rerun unchanged

Regression validation only, nothing tuned. Hashes of `cases.tsv`, `dev.tsv`, `holdout.tsv` and `protocol.md` match `manifest.json`. New files `reports/context-policy-0.1.28-canonical-*`; the earlier ones are kept as the historical run.

- Dev reproduces: `prior0.5`, `margin1`, `margin2` tie at +3 with no regressions, so `margin2`. `margin2` was not reselected.
- Holdout, `baseline` against `margin2`: all 7 gates pass. Positive 60 -> 64 of 84, same four gains, 0 regressions in 60 others, fixtures and typo suite 0 lost, empty context 310/310, with the prompt cache moved off the same result.
- **0 of 144 holdout cases changed any rank or top-1 against the earlier run.** Expected-candidate scores moved by median 0, p95 0.04, max 0.11 nats.
- The fixtures now agree across prefixes: baseline 77 of 83 under all three, where the earlier run had 74 under `ありがとうございました。`. That was this bug.
- The dentist example stays rank 3, at -0.40 against -1.89, 1.49 nats under `margin2`. The announcer example stays first.

**Disclosed:** the first version of the fix decoded one token at a time. Its dev run gave baseline 30/48, and the frozen rule would then have picked `margin1` (+4 against +3). It was not acted on, and it was not why block 8 replaced it; that was speed (the run's latency led to the table above), and that version never ran on the holdout. Dev then reproduced under block 8. The lesson stands anyway: **one case moved the dev tie, so the selection of `margin2` over `margin1` rests on rounding-sized evidence.** The decision stays frozen; it is not a strong one.

## What `margin2` still depends on

Deterministic is not accurate. Each rounding draw is one sample of noise that a Q5_K model carries on any platform, and it is now only stable. Holdout margins close to 2.0: cx002 幹事 promoted at 2.18, cx143 not promoted at 1.82 (the gain there would be correct), cx121 at 2.97. The largest difference on ordinary short text between two decode shapes was 0.45 nats (long text 1.37), so a platform with other rounding could flip cx002 or cx143 and is not expected to flip a case much further from 2.0. **arm64 uses different kernels, so scores there will differ from these by an unknown amount within that kind of range, and a host gate result does not carry over bit for bit.** `margin2` holds as a conservative policy on these host numbers. Nothing here justifies moving it.

## arm64

- Recipe recovered and proven (`third_party/zenz/README.md`). It had never been written down, and the required `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` was found only in an old build tree. The 0.1.27 source rebuilt with it matches the locked `82c60356…` in every section and all 1,016,755 disassembled lines, with only the build-id different.
- The fixed library is `91a88be6df08349bd07557b0eaeea3243f0400f93759492e58987c9774a88b59`, 6,207,440 bytes, 16 KB aligned, from the final source, stamped `c60d0d4`. `android/app/zenz-assets.lock` is updated.
- **Not run on any device.** That history independence holds on arm64 is expected, because the mechanism is the same, but it is not verified. The check cannot run on this PC.

**Pre-existing, not changed:** the library needs dotprod and fp16 while minSdk is 26. A phone without them would crash; which phones those are was not checked.
