#!/usr/bin/env python3
"""Independent reference implementation of the Lateropulsion metrics pipeline (pure Python).

This file must stay algorithmically identical to feature/metrics (Kotlin) — that is the point:
the Kotlin engine is validated against it by golden files, not against itself (ARCHITECTURE §17).

Pipeline
--------
1. validity mask from flags (VALID set and no invalidating flag),
2. hold-fill invalid samples with the previous valid value (first valid value for a leading gap),
3. zero-phase 4th-order Butterworth low-pass (SOS, bilinear with pre-warp; odd padding of
   3*(2*nsec+1); all sections forward, reverse, all sections forward, reverse),
4. deviation metrics on valid samples only,
5. hysteresis episode detector.
"""
import math

VALID = 1 << 0
PITCH_OUT_OF_RANGE = 1 << 1
TRACKING_LOST = 1 << 2
MOUNT_SHIFT = 1 << 3
IN_BAND = 1 << 4
PREDICTED = 1 << 5
FUSION_DISAGREEMENT = 1 << 6
CALIBRATING = 1 << 7
PAUSED = 1 << 8
INVALIDATING = PITCH_OUT_OF_RANGE | TRACKING_LOST | MOUNT_SHIFT | CALIBRATING | PAUSED


def is_valid(flags):
    return (flags & VALID) != 0 and (flags & INVALIDATING) == 0


# ----------------------------------------------------------------------------- filter design
def butter_lowpass_sos(order, fc, fs):
    assert order > 0 and order % 2 == 0
    w0 = math.tan(math.pi * fc / fs)
    sos = []
    for k in range(order // 2):
        angle = math.pi * (2.0 * k + order + 1.0) / (2.0 * order)
        inv_q = -2.0 * math.cos(angle)
        a0 = 1.0 + inv_q * w0 + w0 * w0
        b0 = w0 * w0 / a0
        sos.append((b0, 2.0 * b0, b0, (2.0 * w0 * w0 - 2.0) / a0, (1.0 - inv_q * w0 + w0 * w0) / a0))
    return sos


def _forward(sos, v):
    for (b0, b1, b2, a1, a2) in sos:
        z0 = 0.0
        z1 = 0.0
        for i in range(len(v)):
            x = v[i]
            y = b0 * x + z0
            z0 = b1 * x - a1 * y + z1
            z1 = b2 * x - a2 * y
            v[i] = y


def pad_len(sos):
    return 3 * (2 * len(sos) + 1)


def filtfilt(sos, x):
    n = len(x)
    if n == 0:
        return []
    pad = pad_len(sos) if n > pad_len(sos) else 0
    ext = [2.0 * x[0] - x[pad - i] for i in range(pad)] + list(x) + [2.0 * x[n - 1] - x[n - 2 - i] for i in range(pad)]
    _forward(sos, ext)
    ext.reverse()
    _forward(sos, ext)
    ext.reverse()
    return ext[pad:pad + n]


def causal_filter(sos, x):
    """Live filter: primed at steady state on the first sample (matches CausalFilter.kt)."""
    if not x:
        return []
    states = []
    v = x[0]
    for (b0, b1, b2, a1, a2) in sos:
        g = (b0 + b1 + b2) / (1.0 + a1 + a2)
        y = v * g
        z1 = b2 * v - a2 * y
        z0 = b1 * v - a1 * y + z1
        states.append([z0, z1])
        v = y
    out = []
    for xi in x:
        v = xi
        for si, (b0, b1, b2, a1, a2) in enumerate(sos):
            z = states[si]
            y = b0 * v + z[0]
            z[0] = b1 * v - a1 * y + z[1]
            z[1] = b2 * v - a2 * y
            v = y
        out.append(v)
    return out


# ----------------------------------------------------------------------------- metrics
def hold_fill(x, valid):
    out = list(x)
    first = next((i for i, v in enumerate(valid) if v), -1)
    if first < 0:
        return [0.0] * len(x)
    for i in range(first):
        out[i] = x[first]
    last = x[first]
    for i in range(first, len(x)):
        if valid[i]:
            last = x[i]
        else:
            out[i] = last
    return out


def deviation_metrics(d_filtered, valid, target, tolerance, dt, band1=5.0, band2=10.0):
    n = 0
    total = len(d_filtered)
    s = sa = sq = 0.0
    max_abs = 0.0
    in1 = in2 = out_tol = 0
    path = 0.0
    pairs = 0
    last_valid = False
    last_d = 0.0
    ds = []
    for i in range(total):
        if not valid[i]:
            last_valid = False
            continue
        d = d_filtered[i] - target
        ds.append(d)
        n += 1
        s += d
        ad = abs(d)
        sa += ad
        sq += d * d
        max_abs = max(max_abs, ad)
        if ad <= band1:
            in1 += 1
        if ad <= band2:
            in2 += 1
        if ad > tolerance:
            out_tol += 1
        if last_valid:
            path += abs(d - last_d)
            pairs += 1
        last_valid = True
        last_d = d
    if n == 0:
        return dict(valid_samples=0, total_samples=total)
    mean = s / n
    sd = math.sqrt(sum((d - mean) ** 2 for d in ds) / (n - 1)) if n >= 2 else float("nan")
    return dict(
        mean_deg=mean,
        mad_deg=sa / n,
        rms_deg=math.sqrt(sq / n),
        max_deg=max_abs,
        sd_deg=sd,
        tib5_pct=100.0 * in1 / n,
        tib10_pct=100.0 * in2 / n,
        time_out_of_band_s=out_tol * dt,
        path_length_deg=path,
        mean_velocity_deg_s=(path / (pairs * dt)) if pairs else float("nan"),
        symmetry_index=(s / sa) if sa != 0.0 else 0.0,
        valid_samples=n,
        total_samples=total,
        valid_duration_s=n * dt,
    )


def detect_episodes(t, d_filtered, valid, target=0.0, enter=10.0, exit_=7.0, min_dur=1.0, in_band=5.0, hold=0.5):
    IN_BAND_, CAND, EPI, REC = range(4)
    state = IN_BAND_
    episodes = []
    onset = peak = 0.0
    direction = "RIGHT"
    exit_t = None
    hold_start = None
    saw_invalid = False
    last_t = 0.0
    for i in range(len(t)):
        ti = t[i]
        last_t = ti
        if not valid[i]:
            if state != IN_BAND_:
                saw_invalid = True
            continue
        d = d_filtered[i] - target
        ad = abs(d)
        if state == IN_BAND_:
            if ad > enter:
                state = CAND
                onset, peak = ti, ad
                direction = "RIGHT" if d >= 0 else "LEFT"
                exit_t = None
                hold_start = None
                saw_invalid = False
        elif state == CAND:
            peak = max(peak, ad)
            if ad < exit_:
                state = IN_BAND_
            elif ti - onset >= min_dur:
                state = EPI
        elif state == EPI:
            peak = max(peak, ad)
            if ad < exit_:
                state = REC
                exit_t = ti
                hold_start = ti if ad <= in_band else None
        elif state == REC:
            peak = max(peak, ad)
            if ad > enter:
                state = EPI
                exit_t = None
                hold_start = None
            elif ad <= in_band:
                if hold_start is None:
                    hold_start = ti
                if ti - hold_start >= hold:
                    episodes.append(dict(start_s=onset, end_s=exit_t, peak_deg=peak, partial=saw_invalid,
                                         recovery_s=ti - onset, direction=direction))
                    state = IN_BAND_
            else:
                hold_start = None
    if state == EPI:
        episodes.append(dict(start_s=onset, end_s=last_t, peak_deg=peak, partial=True, recovery_s=None, direction=direction))
    elif state == REC:
        episodes.append(dict(start_s=onset, end_s=exit_t, peak_deg=peak, partial=True, recovery_s=None, direction=direction))
    return episodes


def process(t, theta, flags, fs, target, tolerance, cutoff=5.0, band1=5.0, band2=10.0,
            enter=10.0, exit_=7.0, min_dur=1.0, hold=0.5):
    valid = [is_valid(f) for f in flags]
    filled = hold_fill(theta, valid)
    sos = butter_lowpass_sos(4, cutoff, fs)
    filt = filtfilt(sos, filled)
    dt = 1.0 / fs
    metrics = deviation_metrics(filt, valid, target, tolerance, dt, band1, band2)
    episodes = detect_episodes(t, filt, valid, target, enter, exit_, min_dur, band1, hold)
    return filt, valid, metrics, episodes


if __name__ == "__main__":
    # Smoke test: -3 dB at cutoff.
    sos = butter_lowpass_sos(4, 5.0, 100.0)
    import cmath
    w = 2 * math.pi * 5.0 / 100.0
    h = 1
    for (b0, b1, b2, a1, a2) in sos:
        z = cmath.exp(-1j * w)
        h *= (b0 + b1 * z + b2 * z * z) / (1 + a1 * z + a2 * z * z)
    print("gain at fc (dB):", 20 * math.log10(abs(h)))
