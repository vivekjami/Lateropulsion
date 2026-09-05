# Threat model (STRIDE, abbreviated) — implementation status

| Threat | Control | Where | Status |
|---|---|---|---|
| Device theft | Full-DB encryption (SQLCipher AES-256), passphrase wrapped by an Android Keystore AES-GCM key (StrongBox when available), no-backup storage | `core/database/security/DatabaseKeyManager.kt` | implemented; verify by pulling the DB file (Phase 1 exit) |
| Unauthorised viewing | Per-clinician PIN (PBKDF2-HMAC-SHA256, 210k iterations, per-user salt) + BiometricPrompt; auto-lock after 2 min in background; `FLAG_SECURE` on every window; `allowBackup=false` | app `auth/` | implemented in app module |
| Data tampering | Immutable locked baselines (ADR-004), append-only `.lpx` with SHA-256 trailer, audit table without update/delete DAO methods, metrics engine version stamped | core:database, core:timeseries | implemented, tested |
| Repudiation | Audit events carry clinician ID and timestamp for every view/create/edit/export/erase/override | `Auditor` | implemented, tested |
| Information disclosure | Exports de-identified by default; identified export needs a second confirmation and is audited; PHI log scanner in CI; `PhiGuard` at runtime | feature:report, core:common, tools/ci | implemented |
| Elevation | App-private storage only; no exported components except the launcher activity; no `INTERNET` permission in the clinical flavour (build-time check) | app manifest + Gradle task | implemented |
| Supply chain | Pinned versions, SOUP list gate, dependency scanning in CI (OSV) | gradle/libs.versions.toml, docs/traceability/soup.md, CI | implemented (OSV scan job) |

Not yet done: third-party penetration test (Phase 7), certificate pinning (sync flavour, Phase 9), MDM remote wipe guidance.
