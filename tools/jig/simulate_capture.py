#!/usr/bin/env python3
"""Creates a synthetic jig capture directory so analyse.py can be exercised without hardware."""
import csv
import math
import os
import random
import sys

out = sys.argv[1] if len(sys.argv) > 1 else "jig/simulated"
os.makedirs(out, exist_ok=True)
rnd = random.Random(3)
S, MOUNT, SCALE, NOISE = -1, -90.0, 1.01, 0.25


def raw_for(cmd):
    return SCALE * S * (cmd - MOUNT) + rnd.gauss(0, NOISE)


def write_static(name, angles):
    with open(os.path.join(out, name), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["t_s", "commanded_deg", "theta_raw_deg"])
        t = 0.0
        for a in angles:
            for _ in range(250):
                w.writerow([f"{t:.3f}", a, f"{raw_for(a):.4f}"])
                t += 0.02


write_static("static_up.csv", list(range(-40, 41, 5)))
write_static("static_down.csv", list(range(40, -41, -5)))
with open(os.path.join(out, "dyn_1hz.csv"), "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["t_s", "commanded_deg", "theta_raw_deg"])
    for i in range(3000):
        t = i * 0.01
        cmd = 20 * math.sin(2 * math.pi * 1.0 * t)
        w.writerow([f"{t:.3f}", f"{cmd:.4f}", f"{raw_for(cmd) + 0.5 * math.cos(2 * math.pi * t):.4f}"])
with open(os.path.join(out, "drift.csv"), "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["t_s", "commanded_deg", "theta_raw_deg"])
    for i in range(20 * 60 * 10):
        t = i * 0.1
        w.writerow([f"{t:.1f}", 0, f"{raw_for(0) + 0.2 * t / 60:.4f}"])
print("simulated capture written to", out)
