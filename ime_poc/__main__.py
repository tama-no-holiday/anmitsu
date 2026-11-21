"""Offline CLI: ``python -m ime_poc {demo,benchmark,convert}``. Input text is never saved."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .benchmark import MODES, ROOT, run_benchmark, write_report

DEMO_ID = "dev-representative"
DEFAULT_OUTPUT = ROOT / ".astra/local/benchmark.json"


def _positive(value: str) -> int:
    n = int(value)
    if n < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return n


def _demo(args) -> int:
    from .engine import Engine
    row = None
    with open(ROOT / "data/evaluation.jsonl", encoding="utf-8") as fh:
        for line in fh:  # keep only the one demo row
            if line.strip() and (item := json.loads(line)).get("id") == DEMO_ID:
                row = item
                break
    if row is None:
        print(f"error: {DEMO_ID} not found", file=sys.stderr)
        return 1
    result = Engine().convert(row["input"], k=10)
    print(f"input: {row['input']}")
    for rank, cand in enumerate(result.candidates[:5], 1):
        print(f"{rank}. {cand.surface}  cost={cand.cost:.3f}")
    print("timings_ms (single first call): "
          + ", ".join(f"{name}={ms:.2f}" for name, ms in result.timings_ms.items()))
    top1 = result.candidates[0].surface if result.candidates else None
    match = top1 in row["surfaces"]
    print(f"expected_top1_match: {match}")
    if not match:
        print(f"MISMATCH: expected one of {row['surfaces']}, got {top1!r}")
        return 1
    return 0


def _rate(m: dict) -> str:
    return f"{m['numerator']}/{m['denominator']}"


def _benchmark(args) -> int:
    report = run_benchmark(repeats=args.repeats)
    write_report(report, args.output)
    print(report["warning"])
    for mode in MODES:
        res = report["results"][mode]
        a, w = res["all"], res["warm_wall"]
        print(f"{mode}: top1={_rate(a['top1_exact'])} surface@10={_rate(a['surface_recall@10'])} "
              f"reading@10={_rate(a['reading_recall@10'])} identity={_rate(a['identity_preserved'])} "
              f"overcorrection={_rate(a['overcorrection'])} "
              f"p50={w['p50_ms']:.2f}ms p95={w['p95_ms']:.2f}ms max={w['max_ms']:.2f}ms n={w['n']}")
    print(f"report: {Path(args.output).resolve()}")
    return 0


def _convert(args) -> int:
    from .engine import Engine
    raw = getattr(sys.stdin, "buffer", None)
    try:
        text = raw.read().decode("utf-8-sig") if raw is not None else sys.stdin.read()
    except UnicodeDecodeError:
        print("error: stdin is not valid UTF-8", file=sys.stderr)
        return 2
    result = Engine().convert(text, k=10, sensitive=args.sensitive)
    for cand in result.candidates:
        print(cand.surface)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m ime_poc",
                                     description="Offline noisy-kana Japanese conversion PoC.")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("demo", help=f"convert the {DEMO_ID} fixture and check its expected Top-1")
    bench = sub.add_parser("benchmark", help="run the synthetic benchmark and write a JSON report")
    bench.add_argument("--repeats", type=_positive, default=3)
    bench.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    conv = sub.add_parser("convert", help="read text from stdin and print candidates (not saved)")
    conv.add_argument("--sensitive", action="store_true", help="identity only, no personalization")
    args = parser.parse_args(argv)
    if args.command is None:
        parser.print_help()
        return 0
    return {"demo": _demo, "benchmark": _benchmark, "convert": _convert}[args.command](args)


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
