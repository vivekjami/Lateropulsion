# Implementation status

Honest inventory of what exists in this repository, what has been verified and how, and what remains. Updated at each checkpoint. Phase numbers refer to `IMPLEMENTATION.md`.

## Verified by automated tests (run `./gradlew test testDebugUnitTest`)

| Area | Module | Evidence |
|---|---|---|
| Domain model, JSON config parsing, gain schedules, validation | `core:model`, `core:common` | 34 unit tests |
| Metrics engine: Butterworth, accumulator, episodes, summariser, baseline comparison, MDC | `feature:metrics` | 44 tests incl. 22 golden traces against the independent Python reference and closed-form truth |
| Protocol engine: every state and abort path, stop rules, session cap, mandatory rest, gates, Mode B rules, gain invariants, protocol library validation | `feature:protocol` | 25 tests; the shipped `config/protocols` validate against `config/app.json` |
| Scales (8 shipped), SSQ weighting, SVV, baseline capture, contraindications | `feature:assessment` | 8 tests |
| `.lpx` log round trip, trailer hash, crash recovery, ring overflow accounting | `core:timeseries` | 5 tests |
| IMU fusion: quaternion algebra, complementary filter convergence, roll extraction, bias/drift/mount-shift/disagreement monitors, jig fit, sign calibration, allocation-free loop, lock-free triple buffer, simulated patient | `engine:sensor` | 18 tests |
| Camera frame clock, stall watchdog | `engine:vision` | 2 tests |
| Correction transform, render watchdog, distortion mesh, gravity-locked overlays, tessellator, band edges | `engine:render` | 7 tests |
| Encrypted-DB schema, DAOs, audited repositories, erasure, v1→v2 migration | `core:database` | 5 Robolectric tests (plain SQLite; SQLCipher only on device) |
| Config loading with generic device fallback | `core:datastore` | 3 tests |
| Chart geometry, CSV/JSON exporters (de-identified) | `feature:report` | 6 tests |
| PIN hashing (PBKDF2), session history mapping | `app` | 3 tests |

## Static quality gates
`./gradlew detekt` (0 issues at the tuned thresholds in `config/detekt/detekt.yml`), `./gradlew :app:lintClinicalDebug` (0 errors), `tools/ci/phi_log_scan.py` and `tools/ci/soup_check.py` all pass; the clinical flavour is verified to request no INTERNET permission.

## Implemented, compiles, needs device verification (Phases 3–4 exit criteria)

- Stereo passthrough renderer, render thread, Mode B correction, cues, HMD activity, abort controls, therapist mirror on a second display.
- SQLCipher + Keystore database on a real phone (pull the DB file and confirm it does not open without the key).
- Full session flow end to end on hardware: register → baseline → protocol setup → pre-check → calibration → blocks → summary → PDF.
- Camera fps/size selection across phones; Redmi Note 10S caps at 30 fps in normal sessions.
- PDF generation time (≤ 2 s target).

## Not done (needs hardware, people or approvals)

| Item | Phase | Why it is open |
|---|---|---|
| Rotary jig accuracy report and device qualification | 2 | Needs the physical jig; `tools/jig/analyse.py` and the acceptance limits are ready |
| Motion-to-photon measurement | 3 | Needs the photodiode/high-speed-camera rig (`docs/verification/latency-procedure.md`) |
| Volunteer SSQ tolerability, plumb-line overlay check | 3 | Needs people and the headset |
| Shader golden-image tests | 3 | Needs an offscreen GL context on a device farm |
| Formative/summative usability, fault-injection campaign, soak test, security review, release evidence pack | 7 | Needs therapists, devices, a reviewer |
| Ethics approval, CTRI registration, clinical pilot, MDC from test–retest | 0/8 | Human processes; templates are in `docs/clinical/` |
| Literature citations | 0 | Must be filled by the clinical lead from the primary papers; nothing is invented here |
| Scale licensing confirmation (SCP, BLS, 4PPS, PASS) | 0 | Copyright holders |
| Backend sync, FHIR, trunk IMU, OpenXR port | 9 | Out of scope for v1 |

## Known limitations to state on every report
Head roll is a proxy for trunk lateropulsion (ADR-010). The development phone is unqualified (30 fps camera). Field calibration without a jig yields an UNQUALIFIED flag.
