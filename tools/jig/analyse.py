#!/usr/bin/env python3
"""Rotary-jig accuracy analysis (REQ-SEN-040, docs/verification/jig-procedure.md).

Input directory with CSV files (header: t_s,commanded_deg,theta_raw_deg):
  static_up.csv, static_down.csv          ascending / descending 5° steps over ±40°
  dyn_*.csv                               sinusoidal sweeps (name carries the frequency, e.g. dyn_1hz.csv)
  drift.csv                               20-minute static hold at 0°

Outputs a JSON summary with the device-profile fields plus Bland–Altman statistics.
Pure Python (no numpy) so it runs anywhere.
"""
import csv
import glob
import json
import math
import os
import sys


def read(path):
    t, cmd, raw = [], [], []
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            t.append(float(row["t_s"]))
            cmd.append(float(row["commanded_deg"]))
            raw.append(float(row["theta_raw_deg"]))
    return t, cmd, raw


def wrap(d):
    d = d % 360.0
    if d <= -180.0:
        d += 360.0
    if d > 180.0:
        d -= 360.0
    return d


def fit(cmd, raw):
    """theta_raw = a*cmd + b  ->  s = sign(a), scale = |a|, mount = -b/a (mirrors JigFit.kt)."""
    n = len(cmd)
    mx = sum(cmd) / n
    my = sum(raw) / n
    sxy = sum((c - mx) * (r - my) for c, r in zip(cmd, raw))
    sxx = sum((c - mx) ** 2 for c in cmd)
    a = sxy / sxx
    b = my - a * mx
    s = 1 if a >= 0 else -1
    scale = abs(a)
    mount = wrap(-b / a)
    errs = [wrap(s * r / scale + mount - c) for c, r in zip(cmd, raw)]
    rms = math.sqrt(sum(e * e for e in errs) / n)
    return dict(sign=s, scale_error=scale, theta_mount_deg=mount, residual_rms_deg=rms, max_error_deg=max(abs(e) for e in errs), n=n), errs


def step_means(t, cmd, raw, hold_s=5.0, settle_s=1.0):
    """Average theta_raw over the settled part of each commanded step."""
    steps = []
    i = 0
    while i < len(t):
        j = i
        while j < len(t) and cmd[j] == cmd[i]:
            j += 1
        seg_t0 = t[i]
        vals = [raw[k] for k in range(i, j) if t[k] - seg_t0 >= settle_s]
        if vals:
            steps.append((cmd[i], sum(vals) / len(vals)))
        i = j
    return steps


def bland_altman(cmd, meas):
    diffs = [m - c for c, m in zip(cmd, meas)]
    mean = sum(diffs) / len(diffs)
    sd = math.sqrt(sum((d - mean) ** 2 for d in diffs) / (len(diffs) - 1)) if len(diffs) > 1 else float("nan")
    return dict(bias_deg=mean, sd_deg=sd, loa_low_deg=mean - 1.96 * sd, loa_high_deg=mean + 1.96 * sd)


def drift_rate(t, raw):
    n = len(t)
    mx = sum(t) / n
    my = sum(raw) / n
    sxy = sum((a - mx) * (b - my) for a, b in zip(t, raw))
    sxx = sum((a - mx) ** 2 for a in t)
    return sxy / sxx * 60.0  # deg per minute


def main(d):
    out = {}
    up = os.path.join(d, "static_up.csv")
    down = os.path.join(d, "static_down.csv")
    if not os.path.exists(up):
        print("static_up.csv required", file=sys.stderr)
        return 1
    t, c, r = read(up)
    steps_up = step_means(t, c, r)
    cmd_u = [s[0] for s in steps_up]
    raw_u = [s[1] for s in steps_up]
    profile, errs = fit(cmd_u, raw_u)
    out["static"] = profile
    head_u = [wrap(profile["sign"] * x / profile["scale_error"] + profile["theta_mount_deg"]) for x in raw_u]
    out["bland_altman_static"] = bland_altman(cmd_u, head_u)
    if os.path.exists(down):
        t2, c2, r2 = read(down)
        steps_down = step_means(t2, c2, r2)
        by_cmd = {s[0]: s[1] for s in steps_down}
        hyst = [wrap((ru - by_cmd[cu]) * profile["sign"] / profile["scale_error"]) for cu, ru in steps_up if cu in by_cmd]
        out["hysteresis_mean_deg"] = sum(hyst) / len(hyst) if hyst else None
        out["hysteresis_max_deg"] = max(abs(h) for h in hyst) if hyst else None
    dyn = {}
    for f in sorted(glob.glob(os.path.join(d, "dyn_*.csv"))):
        t3, c3, r3 = read(f)
        head = [wrap(profile["sign"] * x / profile["scale_error"] + profile["theta_mount_deg"]) for x in r3]
        e = [wrap(h - c) for h, c in zip(head, c3)]
        dyn[os.path.basename(f)] = dict(rms_deg=math.sqrt(sum(x * x for x in e) / len(e)), max_deg=max(abs(x) for x in e))
    out["dynamic"] = dyn
    dr = os.path.join(d, "drift.csv")
    if os.path.exists(dr):
        t4, _, r4 = read(dr)
        out["drift_deg_per_min"] = drift_rate(t4, r4)
    limits = dict(residual_rms_deg=1.0, max_error_deg=2.0, hysteresis=1.0, dynamic_rms_1hz=2.0, drift_deg_per_min=0.5)
    passes = profile["residual_rms_deg"] <= limits["residual_rms_deg"] and profile["max_error_deg"] <= limits["max_error_deg"]
    if out.get("hysteresis_max_deg") is not None:
        passes = passes and out["hysteresis_max_deg"] <= limits["hysteresis"]
    for name, v in dyn.items():
        if "1hz" in name:
            passes = passes and v["rms_deg"] <= limits["dynamic_rms_1hz"]
    if "drift_deg_per_min" in out:
        passes = passes and abs(out["drift_deg_per_min"]) < limits["drift_deg_per_min"]
    out["qualified"] = passes
    out["device_profile_fields"] = dict(
        roll_sign=profile["sign"], theta_mount_deg=round(profile["theta_mount_deg"], 3), scale_error=round(profile["scale_error"], 5),
        residual_rms_deg=round(profile["residual_rms_deg"], 3), max_error_deg=round(profile["max_error_deg"], 3),
        dynamic_rms_deg_1hz=next((round(v["rms_deg"], 3) for k, v in dyn.items() if "1hz" in k), None),
        drift_deg_per_min=round(out.get("drift_deg_per_min", float("nan")), 4) if "drift_deg_per_min" in out else None,
        qualified=passes,
    )
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
