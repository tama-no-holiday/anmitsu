# Addendum, after the holdout: the score variation was found and fixed

`protocol.md` and the case files are unchanged (`manifest.json`). This file changes no gate and no option, and `margin2` was not reselected.

`addendum-after-dev.md` left the cause open. It was batch shape following the prompt cache, not a stale cache: `reports/zenz-prompt-cache-0.1.28.md`. The native scorer changed, so the
model's numbers changed, and the frozen evaluation was rerun unchanged as regression validation only (`reports/context-policy-0.1.28-canonical-*`). This holdout was seen before that rerun, so the rerun cannot count as a new held-out result.

All 7 gates hold, the same four gains, 0 of 144 cases changed any rank. A later tuning still needs a new held-out set.
