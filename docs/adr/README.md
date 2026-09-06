# Architecture decision records

ADR-001 … ADR-010 are recorded in `docs/ARCHITECTURE.md` §19. Decisions taken during implementation:

## ADR-011 — Parquet export is produced off-device
**Decision.** The app exports CSV and JSON; Parquet is produced by `tools/analysis/lpx_reader.py` (pyarrow) from the CSV/`.lpx` files.
**Rationale.** Parquet writers for Android drag in Hadoop-sized dependencies (SOUP burden, APK size) for a research-only format.
**Consequence.** Researchers run one script; the in-app export remains dependency-free.

## ADR-012 — Fusion and rendering in Kotlin, no NDK in v1
**Decision.** The complementary filter, roll extraction and GLES renderer are Kotlin. C++/JNI is deferred until profiling shows a need.
**Rationale.** IMPLEMENTATION Phase 2: "Do not start in C++." The hot loops are allocation-free and O(1) per sample; the allocation-free property is unit-tested (REQ-SEN-031).
**Consequence.** No NDK/CMake toolchain required to build; an iOS/KMP port shares more code.

## ADR-013 — No game engine (Godot/Unity) for the passthrough renderer
**Decision.** Camera2 → `SurfaceTexture` → custom GLES 3.0 stereo renderer, as ADR-002.
**Rationale.** A game engine adds at least one frame of latency in its own pipeline, a large SOUP surface, and awkward Camera2 zero-copy access on Android. The latency budget (≤ 45 ms) is the second-ranked architectural driver.
**Consequence.** Stereo, distortion and overlays are ~1 k lines of our own GL code with pure-Kotlin geometry that is unit-tested; Godot remains an option for future gamified functional tasks rendered *inside* our passthrough, not as the host.

## ADR-014 — `.lpx` record is 22 bytes
**Decision.** The record layout listed in ARCHITECTURE §11.1 sums to 22 bytes (u32 + 2×i16 + 3×i16 + 3×i16 + u16); the text said 20. The implementation uses 22 and the architecture text has been corrected.
**Consequence.** ~1.1 MB per 20-minute session at 50 Hz.

## ADR-015 — Roll sign, mount offset and render-rotation sign are per-device data resolved empirically
**Decision.** `DeviceProfile` carries `roll_sign`, `theta_mount_deg`, `scale_error` (jig) and `render_rotation_sign` (lens-calibration screen). A `generic-android` fallback profile lets the app run on any Android 10+ phone with a gyroscope after on-device field calibration, flagged UNQUALIFIED until a jig report exists.
**Rationale.** Vendor sensor axes, camera orientation and headset mounting differ across phones; assuming any of them is how a measurement device silently reports the wrong sign.

## ADR-016 — Phone compatibility floor
**Decision.** minSdk 29 (Android 10), GLES 3.0, any back camera with a `SurfaceTexture` output, raw gyroscope + accelerometer required (`ImuCapabilities.measurementCapable`). Preview size and fps range are chosen at runtime from `CameraCharacteristics`; 30 fps phones run but are flagged for latency.
**Consequence.** Runs on the large majority of active Android phones; clinical qualification remains per model.

## ADR-017 — Landscape mounting is a calibration property, not a code path
**Decision.** Android sensor axes are fixed to the device body and never rotate with the screen. The headset holds the phone in landscape, so raw roll reads about ±90° when the head is upright; the device profile's `theta_mount_deg` (from the jig or the in-app field calibration performed *with the phone mounted*) absorbs this, and `roll_sign` is resolved empirically in the same posture. Nothing in the fusion changes with orientation. The camera image, by contrast, does depend on the phone: the renderer rotates it by whole quarter turns computed from `SENSOR_ORIENTATION` and the display rotation (`CameraOrientation.quarterTurns`), with a manual override on the lens-calibration screen.
**Consequence.** Calibration instructions insist on the mounted, landscape posture; overlays are drawn in landscape screen space and are unaffected; the HMD activity is landscape-locked while the clinician UI rotates freely.

## ADR-018 — Three device qualification tiers
**Decision.** `NONE` (no calibration: blocks clinical sessions unless research mode, flagged UNQUALIFIED on reports), `FIELD` (in-app calibration in the mounted posture, vendor rotation-vector cross-check within 2°; sessions allowed; reports state "field-calibrated, no jig report"), `JIG` (rotary-jig report within ARCHITECTURE §16 limits).
**Rationale.** "Qualified" originally meant only the jig report, which most sites will not have on day one; blocking every phone read as "my device is not supported". The field tier keeps the accuracy claim honest while letting supervised use proceed.
**Consequence.** The jig remains the requirement for any accuracy statement (REQ-SEN-040); FIELD data is labelled as such everywhere.

## ADR-019 — Visor (mono) display is the default; the stereo lens path is kept as an option
**Decision.** `HeadsetProfile.display_mode` selects between `MONO_VISOR` (default profile `phone-visor-mono-v1`: the phone is strapped on top of the head or to a cap/visor with the screen tilted toward the eyes, no lenses; one full-screen camera image, gravity-locked overlays drawn once, Mode B rotates the picture about the screen centre) and `STEREO_LENS` (`generic-open-bottom-v1`: per-eye viewports, barrel pre-distortion, chromatic correction). `PassthroughRenderer` (formerly `StereoRenderer`) implements both; the fusion, protocol engine, metrics and overlays are identical. The camera image is never stretched (`ViewMapping`), because a stretched image would show a 30° real roll as a different angle while the overlays stay exact. `rotation_fit` chooses what happens when Mode B tilts the picture: `CROP` (lens default: the picture keeps filling the eye, corners are lost to grey) or `FIT` (visor default: the whole camera frame stays visible and shrinks smoothly with the slew-limited angle, so no information is lost).
**Rationale.** The clinical kit in hand is a phone on a visor bracket, not a lens headset. Lenses buy immersion and field of view at the cost of IPD fitting, distortion tuning per headset unit, hygiene, and a fully occluded patient; for supervised balance and walking practice a visor that the patient reads like a normal display is simpler, cheaper and safer to abort (the therapist can see the screen too).
**Consequence.** Settings hide IPD and the lens-calibration wording in visor mode and show a display-alignment check instead; the pre-session text says "mount the phone on the visor"; the phone still runs landscape-locked with the same abort controls; the stereo path stays available for sites that own lens headsets, but the plumb-line acceptance check (REQ-VIS-020) and SSQ tolerability must be re-run per display mode.
