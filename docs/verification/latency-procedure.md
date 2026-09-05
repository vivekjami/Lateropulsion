# Motion-to-photon latency measurement (REQ-VIS-030)

Target ≤ 45 ms; the in-app watchdog aborts above 60 ms for 3 frames. The app's own estimate (pose age + render time + 2 vsync periods) is an estimate; this rig measures the real number.

## Rig (choose one)
**A. High-speed camera.** Phone in the headset on the rotary jig; a 240 fps camera frames both the jig's protractor pointer and the phone screen (or a mirror of it). Rotate the jig with a sharp step. Count frames from pointer movement to the first visible change of the plumb-line overlay (Mode A) or the image rotation (Mode B). Latency = frames × 4.17 ms. Repeat 20 steps; report median and 95th percentile.

**B. Photodiode.** Tape a photodiode to a screen region the app flips between black and white on an IMU trigger (Settings → Latency test: the app toggles the region on every gyro sample above 50°/s). Feed the jig's encoder or a second accelerometer and the photodiode into a two-channel scope; latency = time from motion onset to the luminance edge.

## Report
`docs/verification/latency-<model>-<date>.md`: rig used, camera fps range chosen by the app, preview size, display refresh, N steps, median / p95 latency, dropped-frame % over a 20 min run, thermal status at end. Paste `motion_to_photon_ms` into the device profile.

## Known result for the development phone
Redmi Note 10S (Android 13): normal capture sessions cap at 30 fps, so camera exposure + readout alone is ≈ 33 ms. The 45 ms target is unlikely to be met; expect ~55–65 ms. This is why the device profile is `qualified: false` and why a 60 fps-preview phone (or a standalone HMD, ADR/README Phase 9) is recommended for the pilot.
