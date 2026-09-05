#!/usr/bin/env python3
"""Synthetic angle traces with known ground truth (ARCHITECTURE §17).

Each generator returns (t, theta, flags, truth) where `truth` holds analytic expectations that the
Kotlin tests check with a tolerance (the low-pass filter perturbs them slightly), independent of
the reference implementation.
"""
import math
import random

VALID = 1
PITCH = 2
TRACKING = 4


def constant(fs=50.0, dur=60.0, offset=8.0):
    n = int(fs * dur)
    t = [i / fs for i in range(n)]
    return t, [offset] * n, [VALID] * n, dict(mad=abs(offset), rms=abs(offset), episodes=0, tib5=0.0 if abs(offset) > 5 else 100.0)


def sinusoid(fs=50.0, dur=60.0, amp=6.0, freq=0.2, phase=0.0):
    n = int(fs * dur)
    t = [i / fs for i in range(n)]
    th = [amp * math.sin(2 * math.pi * freq * ti + phase) for ti in t]
    return t, th, [VALID] * n, dict(mad=2 * amp / math.pi, rms=amp / math.sqrt(2), max=amp)


def episodes(fs=50.0, dur=60.0, base=2.0, count=4, ep_amp=15.0, ep_dur=3.0, gap=8.0, seed=1):
    """Square episodes of known count on a small baseline, smoothed edges to avoid ringing."""
    rnd = random.Random(seed)
    n = int(fs * dur)
    t = [i / fs for i in range(n)]
    th = [base + rnd.gauss(0, 0.3) for _ in range(n)]
    start = 5.0
    starts = []
    for k in range(count):
        s = start + k * (ep_dur + gap)
        starts.append(s)
        sign = 1 if k % 2 == 0 else -1
        for i in range(n):
            ti = t[i]
            if s <= ti <= s + ep_dur:
                # raised-cosine edges over 0.4 s
                e = 1.0
                if ti - s < 0.4:
                    e = 0.5 - 0.5 * math.cos(math.pi * (ti - s) / 0.4)
                elif s + ep_dur - ti < 0.4:
                    e = 0.5 - 0.5 * math.cos(math.pi * (s + ep_dur - ti) / 0.4)
                th[i] += sign * ep_amp * e
    return t, th, [VALID] * n, dict(episodes=count, episode_starts=starts, approx_duration=ep_dur)


def with_gaps(fs=50.0, dur=60.0, seed=2):
    """Noise plus two invalid regions (pitch guard, tracking loss); one episode overlaps a gap."""
    rnd = random.Random(seed)
    n = int(fs * dur)
    t = [i / fs for i in range(n)]
    th = [3.0 + rnd.gauss(0, 0.5) for _ in range(n)]
    flags = [VALID] * n
    for i in range(n):
        if 10.0 <= t[i] < 14.0:
            flags[i] = VALID | PITCH
            th[i] = 60.0  # nonsense while looking up
        if 30.0 <= t[i] < 31.0:
            flags[i] = TRACKING
    # episode from 29 to 33 s spans the tracking gap -> partial
    for i in range(n):
        if 29.0 <= t[i] <= 33.0:
            th[i] += 14.0
    return t, th, flags, dict(valid_pct=100.0 * (n - int(4 * fs) - int(1 * fs)) / n, partial_episodes=1)


def random_walk(fs=50.0, dur=60.0, seed=3, sigma=0.4, pull=0.02):
    rnd = random.Random(seed)
    n = int(fs * dur)
    t = [i / fs for i in range(n)]
    th = []
    x = 0.0
    for _ in range(n):
        x += rnd.gauss(0, sigma) - pull * x
        th.append(x)
    return t, th, [VALID] * n, dict()


def drift(fs=50.0, dur=60.0, rate_deg_per_min=1.5, seed=4):
    rnd = random.Random(seed)
    n = int(fs * dur)
    t = [i / fs for i in range(n)]
    th = [rate_deg_per_min * ti / 60.0 + rnd.gauss(0, 0.2) for ti in t]
    return t, th, [VALID] * n, dict(drift_deg_per_min=rate_deg_per_min)


GENERATORS = {
    "constant_pos8": lambda: constant(offset=8.0),
    "constant_neg3": lambda: constant(offset=-3.0),
    "constant_zero": lambda: constant(offset=0.0),
    "sine_6deg_0p2hz": lambda: sinusoid(amp=6.0, freq=0.2),
    "sine_12deg_0p1hz": lambda: sinusoid(amp=12.0, freq=0.1),
    "sine_3deg_0p5hz": lambda: sinusoid(amp=3.0, freq=0.5, phase=1.0),
    "sine_20deg_0p05hz": lambda: sinusoid(amp=20.0, freq=0.05),
    "episodes_4": lambda: episodes(count=4),
    "episodes_2_long": lambda: episodes(count=2, ep_dur=6.0, gap=12.0, seed=5),
    "episodes_6_short": lambda: episodes(count=6, ep_dur=1.5, gap=6.0, seed=6, ep_amp=13.0),
    "episodes_borderline": lambda: episodes(count=3, ep_dur=0.9, gap=8.0, seed=7, ep_amp=12.0),
    "episodes_left": lambda: episodes(count=3, ep_dur=2.5, gap=9.0, seed=8, ep_amp=-16.0),
    "gaps_partial": lambda: with_gaps(),
    "gaps_seed9": lambda: with_gaps(seed=9),
    "walk_seed3": lambda: random_walk(seed=3),
    "walk_seed10": lambda: random_walk(seed=10, sigma=0.8),
    "walk_seed11_pull": lambda: random_walk(seed=11, sigma=1.0, pull=0.05),
    "drift_1p5": lambda: drift(),
    "drift_neg4": lambda: drift(rate_deg_per_min=-4.0, seed=12),
    "short_20s_sine": lambda: sinusoid(dur=20.0, amp=5.0, freq=0.3),
    "very_short_10_samples": lambda: sinusoid(dur=0.2, amp=5.0, freq=0.3),
    "all_invalid": lambda: (lambda r: (r[0], r[1], [PITCH] * len(r[0]), dict(valid=0)))(constant(dur=5.0, offset=4.0)),
}
