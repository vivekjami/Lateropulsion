// Root build script. Each module declares its own plugins explicitly; there is
// deliberately no convention-plugin indirection so that a reviewer can read any
// module's build file top to bottom and see exactly what it does.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.junit5) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            subprojects.flatMap { p ->
                listOf("${p.projectDir}/src/main/kotlin", "${p.projectDir}/src/test/kotlin")
            },
        ),
    )
    parallel = true
}

tasks.register("phiLogScan", Exec::class) {
    group = "verification"
    description = "Fails if any source file logs a field that could carry PHI (REQ-SEC-004)."
    commandLine("python3", "$rootDir/tools/ci/phi_log_scan.py", "$rootDir")
}

tasks.register("soupCheck", Exec::class) {
    group = "verification"
    description = "Fails if a dependency in the version catalog has no SOUP entry (REQ-QMS-002)."
    commandLine("python3", "$rootDir/tools/ci/soup_check.py", "$rootDir")
}
