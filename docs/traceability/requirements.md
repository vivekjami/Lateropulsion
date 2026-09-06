# Requirement register

IDs are referenced in test names (`REQ-MET-014_episode_hysteresis`) and in the risk file. Status: **I** implemented and unit-verified, **P** partially implemented, **D** designed / documented only, **V** requires device or bench verification (Phase 3/8).

| ID | Requirement | Source | Status | Verification |
|---|---|---|---|---|
| REQ-PAT-001 | Patient record validation (age, diagnosis, consent timestamp) | README §4.2 | I | `EntityValidationTest`, `DatabaseRoundTripTest` |
| REQ-PAT-002 | Display IDs of the form LP-YYYY-NNNN, allocated from a per-year sequence | README §4.2 | I | `DisplayIdFormatTest`, `DatabaseRoundTripTest` |
| REQ-PAT-010 | Clinical scales are versioned data; every assessment records the scale version | README §8 | I | `AssessmentTest` |
| REQ-PAT-020 | 60 s baseline capture produces mean/MAD/RMS/drift/histogram, refused below 30 s valid | README §4.2 | I | `AssessmentTest` |
| REQ-SES-001 | Protocols load from versioned JSON; strict parsing rejects unknown keys | ARCH §9.1 | I | `ProtocolJsonTest`, `AssetConfigRepositoryTest` |
| REQ-SES-002 | Protocol semantic validation (durations, checkpoints, gates, metrics, positions) | ARCH §9 | I | `ValidatorAndPreconditionsTest` |
| REQ-SES-010 | Mode B refuses to start without a fading gain schedule | README §2 | I | `ValidatorAndPreconditionsTest` |
| REQ-SES-011 | Linear gain schedule decrements to a floor | ARCH §7.3 | I | `GainScheduleTest` |
| REQ-SES-012 | Performance-driven fading only after N consecutive gated sessions; increases >1 step need a note | ARCH §7.3 | I | `GainScheduleTest`, `ValidatorAndPreconditionsTest` |
| REQ-SES-013 | Mode A always renders truthfully (gain forced to 0) | README §2 | I | `ProtocolEngineTest` |
| REQ-SES-020 | Session state machine covers every state and transition of ARCH §9.2 | ARCH §9.2 | I | `ProtocolEngineTest` |
| REQ-SES-021 | All in-session timing on the monotonic clock; wall clock recorded once | ARCH §15 | I | `Clock`, `ProtocolEngine` (nowNs) |
| REQ-SES-022 | Paused time excluded from block elapsed | ARCH §9.2 | I | `ProtocolEngineTest` |
| REQ-SES-023 | Block `abort_if` stop rules evaluated from live metrics | ARCH §9.1 | I | `ProtocolEngineTest` |
| REQ-SES-030 | Append-only `.lpx` log with header, fixed records, hash trailer | ARCH §11.1 | I | `LpxRoundTripTest` |
| REQ-SES-031 | Log without valid trailer is recovered, never discarded | ARCH §15 | I | `LpxRoundTripTest` |
| REQ-MET-001 | Streaming MAD/RMS/max/SD/TIB/path length equal brute-force formulas | ARCH §8.2 | I | `AccumulatorAndMergeTest`, `RunningStatsTest` |
| REQ-MET-010 | 4th-order Butterworth, −3 dB at fc, unity DC gain | ARCH §8.1 | I | `ButterworthTest` |
| REQ-MET-011 | Summary filtering is zero-phase | ARCH §8.1 | I | `ButterworthTest` |
| REQ-MET-014 | Episode hysteresis: enter 10°, exit 7°, min 1 s | ARCH §8.3 | I | `EpisodeDetectorTest` |
| REQ-MET-015 | Re-entry during recovery continues the episode | ARCH §8.3 | I | `EpisodeDetectorTest` |
| REQ-MET-016 | Episodes overlapping invalid data are partial: counted, excluded from means | ARCH §8.3 | I | `EpisodeDetectorTest` |
| REQ-MET-020 | Samples flagged invalid are excluded from metrics; valid % reported | ARCH §8.1 | I | `EntityValidationTest`, `AnalyticTruthTest` |
| REQ-MET-030 | Metrics equal the independent Python implementation on ≥ 20 traces | ARCH §17 | I | `GoldenFileTest` (22 traces) |
| REQ-MET-031 | Metrics match closed-form ground truth on synthetic traces | ARCH §17 | I | `AnalyticTruthTest` |
| REQ-MET-040 | Session summary pools block metrics exactly and stamps the engine version | ARCH §10 | I | `AccumulatorAndMergeTest` |
| REQ-RPT-001 | Session PDF generates in ≤ 2 s | ARCH §16 | V | Phase 5 bench on device |
| REQ-RPT-003 | Baseline comparison refused across positions/exercise families | ARCH §8.4 | I | `AccumulatorAndMergeTest` |
| REQ-RPT-004 | Improvement % with MDC flag and low-confidence rule | ARCH §8.4 | I | `AccumulatorAndMergeTest` |
| REQ-RPT-010 | Head-as-proxy limitation printed on every report | ADR-010 | I | `ReportPureTest`, PDF footer |
| REQ-SEN-001 | Complementary filter converges to accelerometer within 3 s | ARCH §6.3 | I | `FusionTest` |
| REQ-SEN-002 | Gyro and accelerometer paths agree on roll direction | ARCH §6.3 | I | `FusionTest` |
| REQ-SEN-003 | Roll extraction with device profile sign and mount offset; pitch guard | ARCH §6.2 | I | `FusionTest` |
| REQ-SEN-004 | Scale error corrected in roll extraction | ARCH §6.4 | I | `FusionTest` |
| REQ-SEN-010 | Gyro bias from a 3 s stillness window; motion restarts the window | ARCH §6.3 | I | `FusionTest` |
| REQ-SEN-011 | Drift monitor in deg/min | ARCH §6.3 | I | `FusionTest` |
| REQ-SEN-012 | Fusion disagreement with vendor rotation vector logged after persisting | ARCH §6.3 | I | `FusionTest` |
| REQ-SEN-020 | Jig fit recovers sign, scale, mount and residual error | ARCH §6.4 | I | `FusionTest`, `tools/jig/analyse.py` |
| REQ-SEN-021 | Sign calibration refuses ambiguous tilts and blocks session start | ARCH §6.2 | I | `FusionTest`, `ValidatorAndPreconditionsTest` |
| REQ-SEN-030 | Fusion pipeline tracks simulated roll within 1°; flags IMU dropout | ARCH §15 | I | `FusionTest` |
| REQ-SEN-031 | Fusion loop is allocation-free | ARCH §5 | I | `FusionTest` |
| REQ-SEN-040 | Roll accuracy RMS ≤ 1.0°, max ≤ 2.0° over ±40° on the jig | ARCH §16 | V | Phase 2 jig report |
| REQ-VIS-001 | Mode B counter-rotation slew-limited; pose prediction clamped to 50 ms | ARCH §5, §7.2 | I | `RenderMathTest` |
| REQ-VIS-002 | An invalid pose (pitch guard, tracking lost, mount shift) relaxes the Mode B correction to neutral at the slew limit | ARCH §15 | I | `RenderMathTest` |
| REQ-VIS-010 | Lens distortion mesh: centre fixed, radial growth, chromatic aberration | ARCH §7.1 | I | `RenderMathTest` |
| REQ-VIS-011 | Camera image covers the viewport without stretching in both display modes; rotated buffers use the reciprocal aspect | ARCH §7.1, ADR-019 | I | `RenderMathTest` |
| REQ-VIS-012 | Visor (mono) headset profile parses, validates without lens data, overlays stay gravity-locked with a viewport-wide horizon | ADR-019 | I | `RenderMathTest` |
| REQ-VIS-013 | `rotation_fit = FIT` keeps the whole rotated camera frame inside the viewport at every angle, never stretched, always maximal | ADR-019 | I | `RenderMathTest` |
| REQ-VIS-020 | Overlay cues are gravity-locked | ARCH §7.4 | I | `RenderMathTest` (+ plumb line vs physical plumb, Phase 3) |
| REQ-VIS-021 | Tolerance band colour follows deviation and validity; idle draws nothing | ARCH §7.4 | I | `RenderMathTest` |
| REQ-VIS-030 | Motion-to-photon ≤ 45 ms measured | ARCH §16 | V | Phase 3 latency rig |
| REQ-SAF-001 | Pre-session checklist incl. supervision attestation blocks start | README §15 | I | `EntityValidationTest`, `ValidatorAndPreconditionsTest` |
| REQ-SAF-002 | Harness confirmation required for standing/walking | README §15 | I | `EntityValidationTest` |
| REQ-SAF-003 | Sitting → standing → walking enforced; override audited; walking not overridable | ARCH §9.3 | I | `ValidatorAndPreconditionsTest` |
| REQ-SAF-004 | Abort reaches neutral passthrough within one frame, bypassing the protocol layer | ARCH §1 | I/V | `ProtocolEngineTest`, `AbortController`; capture verification Phase 3 |
| REQ-SAF-005 | Hard session cap (20 min) aborts in any active state | README §15 | I | `ProtocolEngineTest` |
| REQ-SAF-006 | Mandatory rest after 5 min of exercise | README §15 | I | `ProtocolEngineTest` |
| REQ-SAF-010 | SSQ scored per Kennedy; two consecutive flags lock the patient to Mode A | README §15 | I | `AssessmentTest`, `ValidatorAndPreconditionsTest` |
| REQ-SAF-012 | Device self-check (camera, IMU, battery, thermal, storage) before sessions | README §4.1 | P | `DeviceSelfCheck`; UI wiring in app |
| REQ-SAF-020 | Device qualification tiers: NONE blocks unless research mode (flagged), FIELD allowed with a report note, JIG clean (ADR-018) | ARCH §18 | I | `ValidatorAndPreconditionsTest`, `AssetConfigRepositoryTest` |
| REQ-SAF-030 | Mount-shift detection flags data after a headset slip | ARCH §15 | I | `FusionTest` |
| REQ-SAF-031 | Camera stall detected within 200 ms | ARCH §15 | I | `FrameClockTest` |
| REQ-SAF-032 | Render watchdog: 3 frames over threshold → neutral + alert | ARCH §5 | I | `RenderMathTest` |
| REQ-SEC-001 | Database encrypted at rest with SQLCipher; key wrapped in Android Keystore | ARCH §14 | P/V | `DatabaseKeyManager`; verify by pulling the DB file (Phase 1 exit) |
| REQ-SEC-002 | Identifiers in a separate table joined by UUID | ARCH §10 | I | schema, `DatabaseRoundTripTest` |
| REQ-SEC-003 | Clinician PIN/biometric login, auto-lock | ARCH §14 | P | app module |
| REQ-SEC-004 | No PHI in logs | ARCH §14 | I | `LoggingTest`, `tools/ci/phi_log_scan.py` |
| REQ-SEC-005 | Append-only audit log on every read/write/export/delete | ARCH §14 | I | `DatabaseRoundTripTest` |
| REQ-SEC-006 | Exports de-identified by default | README §14 | I | `ReportPureTest` |
| REQ-SEC-010 | Patient erasure removes identifiers and media; de-identified series optionally retained | README §14 | I | `DatabaseRoundTripTest` |
| REQ-SEC-011 | Clinical flavour has no INTERNET permission | ARCH §14 | I | Gradle `verifyNoInternetPermission` task |
| REQ-DAT-003 | Every schema bump ships a migration verified against the exported schema history | IMPL Phase 1 | I | `MigrationTest` |
| REQ-QMS-002 | Every third-party dependency has a SOUP entry | IEC 62304 | I | `tools/ci/soup_check.py` |
