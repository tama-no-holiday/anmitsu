"""Offline synthetic-fixture evaluation. Gold values never enter Engine.convert."""
from __future__ import annotations

import hashlib
import json
import math
import os
import platform
import random
import sys
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODES = ("A", "B", "C", "D")
KS = (1, 5, 10)
# Independent, invented past correction events; not fitted to evaluation rows.
# These test the interfaces, not measured motor trajectories or real users.
MOTOR_TRAIN = [("は", "ほ"), ("ふ", "ぷ"), ("か", "こ")] * 4
WORD_TRAIN = [("しりょう", "資料"), ("かいぎ", "会議")] * 3


def percentile(values, p):
    """Nearest-rank quantile; no interpolation, milliseconds in/out."""
    if not values or not 0 < p <= 1:
        raise ValueError("nonempty samples and 0 < p <= 1 required")
    return sorted(values)[math.ceil(len(values) * p) - 1]


def latency(values):
    return {"n": len(values), "p50_ms": percentile(values, .50),
            "p95_ms": percentile(values, .95), "max_ms": max(values)}


def metrics(result, row):
    readings = [c.reading for c in result.readings]
    surfaces = [c.surface for c in result.candidates]
    gold_surfaces = set(row["surfaces"])
    reading_rank = next((i + 1 for i, r in enumerate(readings) if r == row["reading"]), None)
    surface_rank = next((i + 1 for i, s in enumerate(surfaces) if s in gold_surfaces), None)
    clean = row["input"] == row["reading"]
    return {
        "id": row["id"], "split": row["split"], "kind": row["kind"],
        "reading_rank": reading_rank, "surface_rank": surface_rank,
        **{f"reading_recall@{k}": reading_rank is not None and reading_rank <= k for k in KS},
        **{f"surface_recall@{k}": surface_rank is not None and surface_rank <= k for k in KS},
        "top1_exact": surface_rank == 1,
        "identity_preserved": row["input"] in surfaces,
        "overcorrected": (bool(result.candidates) and result.candidates[0].reading != row["reading"]) if clean else None,
        "empty_output": not bool(result.candidates),
    }


def aggregate(rows):
    n = len(rows)
    flags = [f"{kind}_recall@{k}" for kind in ("reading", "surface") for k in KS]
    flags += ["top1_exact", "identity_preserved", "empty_output"]
    clean = [r for r in rows if r["overcorrected"] is not None]
    present = [r for r in rows if r["surface_rank"] is not None]
    return {
        "cases": n,
        **{f: {"numerator": sum(r[f] for r in rows), "denominator": n,
               "rate": sum(r[f] for r in rows) / n if n else None} for f in flags},
        "overcorrection": {"numerator": sum(r["overcorrected"] for r in clean),
                           "denominator": len(clean),
                           "rate": sum(r["overcorrected"] for r in clean) / len(clean) if clean else None},
        "ranking_top1_given_surface_present": {
            "numerator": sum(r["top1_exact"] for r in present), "denominator": len(present),
            "rate": sum(r["top1_exact"] for r in present) / len(present) if present else None},
    }


def load_rows(path):
    rows = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows or len({r["id"] for r in rows}) != len(rows):
        raise ValueError("dataset must contain unique nonempty cases")
    for r in rows:
        if not all(key in r for key in ("id", "split", "kind", "input", "reading", "surfaces")) or not r["surfaces"]:
            raise ValueError("invalid evaluation schema")
    return rows


def options(row, mode):
    if mode not in MODES:
        raise ValueError("unknown ablation mode")
    args = {}
    if mode != "A":
        args["touch_hints"] = {int(i): dict(ps) for i, ps in row.get("touch_hints", {}).items()}
    if mode in ("C", "D"):
        from collections import Counter
        args["motor_counts"] = dict(Counter(MOTOR_TRAIN))
    if mode == "D":
        from collections import Counter
        args["language_counts"] = dict(Counter(surface for _, surface in WORD_TRAIN))
    return args


def fingerprint(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run_benchmark(dataset=None, repeats=3, seed=20261026):
    if not isinstance(repeats, int) or isinstance(repeats, bool) or repeats < 1:
        raise ValueError("repeats must be a positive integer")
    from .engine import Engine
    dataset = Path(dataset) if dataset else ROOT / "data/evaluation.jsonl"
    rows = load_rows(dataset)
    start = time.perf_counter_ns()
    engine = Engine(beam_width=32)
    construction_ms = (time.perf_counter_ns() - start) / 1e6
    start = time.perf_counter_ns()
    engine.convert("きょう", k=10)
    first_call_ms = (time.perf_counter_ns() - start) / 1e6
    # Explicit independent warmup, excluded from all timed repetitions.
    for mode in MODES:
        engine.convert("かいぎ", k=10, **options({}, mode))
    observed = {mode: {} for mode in MODES}
    wall = defaultdict(list)
    stage = defaultdict(lambda: defaultdict(list))
    schedule = [(mode, index) for _ in range(repeats) for mode in MODES for index in range(len(rows))]
    random.Random(seed).shuffle(schedule)
    cpu_start = time.process_time_ns()
    for mode, index in schedule:
        row = rows[index]
        args = options(row, mode)
        start = time.perf_counter_ns()
        result = engine.convert(row["input"], k=10, **args)
        elapsed_ms = (time.perf_counter_ns() - start) / 1e6
        wall[mode].append(elapsed_ms)
        for name, value in result.timings_ms.items():
            stage[mode][name].append(value)
        measured = metrics(result, row)
        if index in observed[mode] and observed[mode][index] != measured:
            raise RuntimeError("nondeterministic benchmark result")
        observed[mode][index] = measured
    cpu_ms = (time.process_time_ns() - cpu_start) / 1e6
    results = {}
    for mode in MODES:
        cases = [observed[mode][i] for i in range(len(rows))]
        results[mode] = {
            "all": aggregate(cases),
            "by_split": {s: aggregate([r for r in cases if r["split"] == s]) for s in sorted({r["split"] for r in cases})},
            "by_kind": {s: aggregate([r for r in cases if r["kind"] == s]) for s in sorted({r["kind"] for r in cases})},
            "warm_wall": latency(wall[mode]),
            "warm_stages": {name: latency(values) for name, values in stage[mode].items()},
            "cases": cases,
        }
    source_paths = sorted((ROOT / "ime_poc").glob("*.py"))
    return {
        "schema": 1, "fixture_only": True, "neural_model": None, "real_touch": False,
        "warning": "Exploratory closed-vocabulary synthetic evaluation; not a blind holdout or Android measurement.",
        "python": sys.version.split()[0], "platform": platform.platform(), "machine": platform.machine(),
        "processor": platform.processor(), "logical_cpus": os.cpu_count(),
        "seed": seed, "repeats": repeats, "k": 10, "beam_width": 32, "cases_per_mode": len(rows),
        "quantile": "nearest-rank", "construction_ms": construction_ms, "first_call_ms": first_call_ms,
        "total_cpu_ms": cpu_ms, "rss_bytes": None, "android_pss_bytes": None, "energy_joules": None,
        "hashes": {"dataset": fingerprint(dataset), "lexicon": fingerprint(ROOT / "data/lexicon.json"),
                   "source": {p.name: fingerprint(p) for p in source_paths}},
        "ablation": {
            "A": "String only", "B": "A + handwritten synthetic kana hypotheses, only where present",
            "C": "B + separate invented prior substitution counts", "D": "C + separate invented vocabulary counts",
            "motor_training": MOTOR_TRAIN, "word_training": WORD_TRAIN,
            "limitation": "No measured trajectory decoder, no user-specific train/test cohort. Gold is evaluation-only."},
        "results": results,
    }


def write_report(report, path):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
