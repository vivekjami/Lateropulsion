# Rotary-jig roll accuracy verification (REQ-SEN-040)

Required evidence for device qualification (IMPLEMENTATION Phase 2, ARCHITECTURE §6.4, §17).

## Equipment
- Rotary jig with protractor, ≥ 0.5° resolution, axis horizontal, phone clamp reproducing the headset mount (landscape, screen toward the axis).
- Phone under test with the debug sensor screen (Settings → Sensor debug) or the `jigVerify` capture in research mode.
- Optional: stepper drive for dynamic sweeps (0.5 / 1 / 2 Hz sinusoids, ±20°).

## Procedure
1. Level the jig (bubble level); zero the protractor at the clamp's upright.
2. Mount the phone; start the sensor debug screen; wait for the gyro-bias window to complete (3 s still).
3. **Static sweep, ascending:** −40° → +40° in 5° steps. Hold each step 5 s; record the mean `theta_raw` shown (or export the `.lpx` capture).
4. **Static sweep, descending:** +40° → −40°, same steps (hysteresis).
5. **Dynamic:** drive 0.5, 1 and 2 Hz sinusoids at ±20° for 30 s each; export the capture and the commanded trace.
6. **Drift:** 20 minute static hold at 0°; export.
7. Save captures as `jig/<model>/static_up.csv`, `static_down.csv`, `dyn_1hz.csv`, `drift.csv` (columns: `t_s, commanded_deg, theta_raw_deg`).
8. Run `python3 tools/jig/analyse.py jig/<model>/` and paste the JSON summary into `config/devices/<model>.json` (`roll_sign`, `theta_mount_deg`, `scale_error`, `residual_rms_deg`, `max_error_deg`, `dynamic_rms_deg_1hz`, `drift_deg_per_min`, `qualified_at`, `qualified_by`).

## Acceptance (ARCHITECTURE §16)
| Quantity | Limit |
|---|---|
| Static residual RMS over ±40° | ≤ 1.0° |
| Static max error | ≤ 2.0° |
| Hysteresis (mean up−down) | ≤ 1.0° |
| Dynamic RMS at 1 Hz | ≤ 2.0° |
| Drift over 20 min | < 0.5°/min |
| Effective pose rate | ≥ 100 Hz |

A device failing any limit stays `qualified: false` and can only be used in research mode with the UNQUALIFIED DEVICE flag on every report.
