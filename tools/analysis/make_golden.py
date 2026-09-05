#!/usr/bin/env python3
"""Generate golden fixtures for feature/metrics tests from the independent Python reference.

Usage: python3 tools/analysis/make_golden.py [output_dir]
Traces are rounded to 4 decimals BEFORE processing so that Kotlin reads exactly the same numbers.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import gen_trace  # noqa: E402
import reference_metrics as ref  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "..", "feature", "metrics", "src", "test", "resources", "golden")
os.makedirs(OUT, exist_ok=True)

FS = 50.0
TARGET = 0.0
TOLERANCE = 5.0


def clean(v):
    if isinstance(v, float) and (v != v):  # NaN
        return "NaN"
    return v


for name, gen in gen_trace.GENERATORS.items():
    t, theta, flags, truth = gen()
    t = [round(x, 6) for x in t]
    theta = [round(x, 4) for x in theta]
    filt, valid, metrics, episodes = ref.process(t, theta, flags, FS, TARGET, TOLERANCE)
    # causal (live) filter output checksum for the 2nd-order filter as well
    live = ref.causal_filter(ref.butter_lowpass_sos(2, 5.0, FS), ref.hold_fill(theta, valid))
    fixture = {
        "name": name,
        "fs": FS,
        "target_deg": TARGET,
        "tolerance_deg": TOLERANCE,
        "t": t,
        "theta": theta,
        "flags": flags,
        "expected": {
            "metrics": {k: clean(v) for k, v in metrics.items()},
            "episodes": episodes,
            "filtered_sum": sum(filt),
            "filtered_abs_sum": sum(abs(x) for x in filt),
            "filtered_first": filt[:5],
            "filtered_last": filt[-5:],
            "live_sum": sum(live),
            "live_last": live[-1] if live else None,
        },
        "truth": truth,
    }
    with open(os.path.join(OUT, f"{name}.json"), "w") as fh:
        json.dump(fixture, fh, separators=(",", ":"))
    print(f"{name:28s} n={len(t):5d} mad={metrics.get('mad_deg', float('nan')):7.3f} episodes={len(episodes)}")
print("written to", os.path.abspath(OUT))
