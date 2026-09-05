# SOUP list (IEC 62304 §8.1.2)

Every third-party component in `gradle/libs.versions.toml`, its version, purpose and whether it ships in the clinical APK. `tools/ci/soup_check.py` fails the build if a catalog entry is missing here.
Anomaly review: before each release gate, check each runtime component's issue tracker for open defects affecting data integrity, timing or security, and record the outcome in `docs/gates/phase-N.md`.

| Coordinate | Version | Purpose | Scope | Ships in clinical APK | Known-anomaly review |
|---|---|---|---|---|---|
| `androidx.core:core-ktx` | 1.16.0 | Kotlin extensions for Android framework APIs | runtime | yes | pending (Phase 7) |
| `androidx.annotation:annotation` | 1.9.1 | Nullability/threading annotations | runtime | yes | pending (Phase 7) |
| `androidx.activity:activity-compose` | 1.10.1 | Compose host activity integration | runtime | yes | pending (Phase 7) |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.1 | Lifecycle-aware state collection in Compose | runtime | yes | pending (Phase 7) |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.1 | ViewModel integration for Compose | runtime | yes | pending (Phase 7) |
| `androidx.lifecycle:lifecycle-process` | 2.9.1 | Process lifecycle (auto-lock on background) | runtime | yes | pending (Phase 7) |
| `androidx.navigation:navigation-compose` | 2.9.0 | Screen navigation | runtime | yes | pending (Phase 7) |
| `androidx.compose:compose-bom` | 2025.06.01 | Version alignment for Compose artifacts | runtime | yes | pending (Phase 7) |
| `androidx.compose.ui:ui` | (BOM 2025.06.01) | Compose UI core | runtime | yes | pending (Phase 7) |
| `androidx.compose.ui:ui-graphics` | (BOM 2025.06.01) | Compose graphics (charts, dials) | runtime | yes | pending (Phase 7) |
| `androidx.compose.ui:ui-tooling-preview` | (BOM 2025.06.01) | Preview annotations (debug only) | runtime | no | pending (Phase 7) |
| `androidx.compose.ui:ui-tooling` | (BOM 2025.06.01) | Layout inspector (debug only) | runtime | no | pending (Phase 7) |
| `androidx.compose.ui:ui-test-junit4` | (BOM 2025.06.01) | Compose UI tests | runtime | yes | pending (Phase 7) |
| `androidx.compose.ui:ui-test-manifest` | (BOM 2025.06.01) | Compose UI test manifest | runtime | yes | pending (Phase 7) |
| `androidx.compose.material3:material3` | (BOM 2025.06.01) | Material 3 components | runtime | yes | pending (Phase 7) |
| `androidx.compose.material:material-icons-extended` | (BOM 2025.06.01) | Icon set | runtime | yes | pending (Phase 7) |
| `androidx.room:room-runtime` | 2.7.2 | SQLite ORM runtime | runtime | yes | pending (Phase 7) |
| `androidx.room:room-ktx` | 2.7.2 | Room coroutine support | runtime | yes | pending (Phase 7) |
| `androidx.room:room-compiler` | 2.7.2 | Room annotation processor (build-time only) | build | no | pending (Phase 7) |
| `androidx.room:room-testing` | 2.7.2 | MigrationTestHelper (tests only) | test | no | pending (Phase 7) |
| `androidx.sqlite:sqlite` | 2.5.2 | SupportSQLite abstraction | runtime | yes | pending (Phase 7) |
| `androidx.sqlite:sqlite-framework` | 2.5.2 | Framework SQLite driver (tests, unencrypted) | runtime | yes | pending (Phase 7) |
| `net.zetetic:sqlcipher-android` | 4.9.0 | AES-256 database encryption (REQ-SEC-001) | runtime | yes | pending (Phase 7) |
| `androidx.work:work-runtime-ktx` | 2.10.2 | Retention worker scheduling | runtime | yes | pending (Phase 7) |
| `androidx.biometric:biometric` | 1.1.0 | BiometricPrompt for clinician unlock | runtime | yes | pending (Phase 7) |
| `androidx.datastore:datastore-preferences` | 1.1.7 | Device settings storage (no PHI) | runtime | yes | pending (Phase 7) |
| `com.google.dagger:hilt-android` | 2.57.1 | Dependency injection runtime | runtime | yes | pending (Phase 7) |
| `com.google.dagger:hilt-android-compiler` | 2.57.1 | Hilt annotation processor (build-time only) | build | no | pending (Phase 7) |
| `com.google.dagger:hilt-android-testing` | 2.57.1 | Hilt test support (tests only) | test | no | pending (Phase 7) |
| `androidx.hilt:hilt-navigation-compose` | 1.2.0 | Hilt ViewModel injection in Compose navigation | runtime | yes | pending (Phase 7) |
| `androidx.hilt:hilt-work` | 1.2.0 | Hilt injection into WorkManager workers | runtime | yes | pending (Phase 7) |
| `androidx.hilt:hilt-compiler` | 1.2.0 | AndroidX Hilt annotation processor (build-time only) | build | no | pending (Phase 7) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Structured concurrency | runtime | yes | pending (Phase 7) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Android main-thread dispatcher | runtime | yes | pending (Phase 7) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.10.2 | Coroutine test dispatchers (tests only) | test | no | pending (Phase 7) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | JSON for config files and exports | runtime | yes | pending (Phase 7) |
| `org.junit.jupiter:junit-jupiter` | 5.13.4 | Unit test framework (tests only) | test | no | pending (Phase 7) |
| `org.junit.jupiter:junit-jupiter-api` | 5.13.4 | JUnit 5 API (tests only) | test | no | pending (Phase 7) |
| `org.junit.jupiter:junit-jupiter-engine` | 5.13.4 | JUnit 5 engine (tests only) | test | no | pending (Phase 7) |
| `org.junit.jupiter:junit-jupiter-params` | 5.13.4 | Parameterised tests (tests only) | test | no | pending (Phase 7) |
| `org.junit.vintage:junit-vintage-engine` | 5.13.4 | Runs JUnit 4 Robolectric tests on the JUnit 5 platform (tests only) | test | no | pending (Phase 7) |
| `org.junit.platform:junit-platform-launcher` | 1.13.4 | Test launcher (tests only) | test | no | pending (Phase 7) |
| `junit:junit` | 4.13.2 | JUnit 4 for Robolectric tests (tests only) | test | no | pending (Phase 7) |
| `de.mannodermaus.junit5:android-test-core` | 1.8.0 | JUnit 5 instrumented test support (tests only) | test | no | pending (Phase 7) |
| `de.mannodermaus.junit5:android-test-runner` | 1.8.0 | JUnit 5 instrumented runner (tests only) | test | no | pending (Phase 7) |
| `io.kotest:kotest-property` | 5.9.1 | Property-based testing (tests only) | test | no | pending (Phase 7) |
| `io.kotest:kotest-assertions-core` | 5.9.1 | Assertions (tests only) | test | no | pending (Phase 7) |
| `io.mockk:mockk` | 1.14.5 | Mocking (tests only) | test | no | pending (Phase 7) |
| `app.cash.turbine:turbine` | 1.2.1 | Flow testing (tests only) | test | no | pending (Phase 7) |
| `org.robolectric:robolectric` | 4.15.1 | JVM Android runtime for DB tests (tests only) | test | no | pending (Phase 7) |
| `androidx.test:core-ktx` | 1.6.1 | AndroidX test core (tests only) | test | no | pending (Phase 7) |
| `androidx.test.ext:junit` | 1.2.1 | AndroidJUnit4 runner (tests only) | test | no | pending (Phase 7) |
| `androidx.test:runner` | 1.6.2 | Instrumented test runner (tests only) | test | no | pending (Phase 7) |
| `androidx.test.espresso:espresso-core` | 3.6.1 | UI tests (tests only) | test | no | pending (Phase 7) |
| `com.android.application` | 8.13.0 | Android Gradle plugin (build-time only) | build | no | pending (Phase 7) |
| `com.android.library` | 8.13.0 | Android Gradle plugin (build-time only) | build | no | pending (Phase 7) |
| `org.jetbrains.kotlin.android` | 2.2.10 | Kotlin compiler plugin (build-time only) | build | no | pending (Phase 7) |
| `org.jetbrains.kotlin.jvm` | 2.2.10 | Kotlin compiler plugin (build-time only) | build | no | pending (Phase 7) |
| `org.jetbrains.kotlin.plugin.compose` | 2.2.10 | Compose compiler plugin (build-time only) | build | no | pending (Phase 7) |
| `org.jetbrains.kotlin.plugin.serialization` | 2.2.10 | Serialization compiler plugin (build-time only) | build | no | pending (Phase 7) |
| `com.google.devtools.ksp` | 2.2.10-2.0.2 | Symbol processing (build-time only) | build | no | pending (Phase 7) |
| `com.google.dagger.hilt.android` | 2.57.1 | Hilt Gradle plugin (build-time only) | build | no | pending (Phase 7) |
| `de.mannodermaus.android-junit5` | 1.13.1.0 | JUnit 5 for Android modules (build-time only) | build | no | pending (Phase 7) |
| `io.gitlab.arturbosch.detekt` | 1.23.8 | Static analysis (build-time only) | build | no | pending (Phase 7) |

Runtime components with native code: `net.zetetic:sqlcipher-android` (SQLCipher/OpenSSL). Its ABI set (arm64-v8a, armeabi-v7a, x86, x86_64) defines the supported device ABIs.
