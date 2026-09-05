import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit5)
}

android {
    namespace = "com.lateropulsion.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lateropulsion.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // clinical: offline, no INTERNET permission. sync: adds the network permission for the optional backend (ADR-006).
    flavorDimensions += "distribution"
    productFlavors {
        create("clinical") { dimension = "distribution"; isDefault = true }
        create("sync") { dimension = "distribution"; applicationIdSuffix = ".sync"; versionNameSuffix = "-sync" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

/** Copies the repository's `config/` tree into the APK as `assets/config/**` so protocols and scales ship as data (ADR-007). */
abstract class ConfigAssetsTask : DefaultTask() {
    @get:InputDirectory abstract val configDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.resolve("config")
        out.deleteRecursively(); out.mkdirs()
        configDir.get().asFile.copyRecursively(out, overwrite = true)
        out.resolve("detekt").deleteRecursively()
    }
}

val configAssets = tasks.register<ConfigAssetsTask>("configAssets") {
    configDir.set(rootProject.layout.projectDirectory.dir("config"))
    outputDir.set(layout.buildDirectory.dir("generated/configAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(configAssets, ConfigAssetsTask::outputDir)

        // REQ-SEC-011: the clinical flavour must never request network access.
        if (variant.flavorName == "clinical") {
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            tasks.register("verifyNoInternetPermission$cap") {
                group = "verification"
                description = "Fails if the merged manifest of ${variant.name} declares android.permission.INTERNET"
                inputs.file(manifest)
                doLast {
                    val text = manifest.get().asFile.readText()
                    if (text.contains("android.permission.INTERNET")) {
                        throw GradleException("Clinical flavour must not request INTERNET (REQ-SEC-011)")
                    }
                    println("verifyNoInternetPermission$cap: OK, no INTERNET permission")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:timeseries"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":engine:sensor"))
    implementation(project(":engine:vision"))
    implementation(project(":engine:render"))
    implementation(project(":feature:assessment"))
    implementation(project(":feature:protocol"))
    implementation(project(":feature:metrics"))
    implementation(project(":feature:report"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.android.junit5.core)
    androidTestRuntimeOnly(libs.android.junit5.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
