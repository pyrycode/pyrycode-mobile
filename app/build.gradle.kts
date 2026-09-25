import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase client configuration (#579). Applied only when the file is present, so builds
// without it (CI, fresh worktrees) still succeed, with push disabled.
if (file("google-services.json").exists()) {
    pluginManager.apply(
        libs.plugins.google.services
            .get()
            .pluginId,
    )
}

abstract class GitShaValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String =
        try {
            val stdout = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                    standardOutput = stdout
                    isIgnoreExitValue = true
                }
            val sha = stdout.toString(Charsets.UTF_8).trim()
            if (result.exitValue == 0 && sha.isNotEmpty()) sha else "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
}

// Play rejects an upload whose version code is not above the last one (#1025). The commit count
// rises with every commit on main; it needs a full clone. Falls back to 1 so builds without git work.
abstract class GitCommitCountValueSource : ValueSource<Int, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Int =
        try {
            val stdout = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    commandLine("git", "rev-list", "--count", "HEAD")
                    standardOutput = stdout
                    isIgnoreExitValue = true
                }
            val count = stdout.toString(Charsets.UTF_8).trim().toIntOrNull()
            if (result.exitValue == 0 && count != null && count > 0) count else 1
        } catch (_: Throwable) {
            1
        }
}

// Release upload key (#1025). The four properties can come from -P, ~/.gradle/gradle.properties or
// ORG_GRADLE_PROJECT_-prefixed environment variables; the keystore and passwords never enter the repo.
// Any missing: release stays debug-signed so local release builds work, and bundleRelease fails.
val uploadSigningPropertyNames =
    listOf("pyry.upload.storeFile", "pyry.upload.storePassword", "pyry.upload.keyAlias", "pyry.upload.keyPassword")
val uploadSigningProperties = uploadSigningPropertyNames.associateWith { providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank) }
val missingUploadSigningProperties = uploadSigningProperties.filterValues { it == null }.keys.toList()

android {
    namespace = "de.pyryco.mobile"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "de.pyryco.mobile"
        minSdk = 33
        targetSdk = 36
        // -PversionCode=N overrides the commit count.
        versionCode =
            providers
                .gradleProperty("versionCode")
                .map { it.toInt() }
                .orElse(providers.of(GitCommitCountValueSource::class.java) {})
                .get()
        versionName = "1.0.0"

        val gitSha = providers.of(GitShaValueSource::class.java) {}
        buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"")

        // Real by default; -PuseRelayRepository=false builds the demo repository binding.
        // Compile-time only: runtime input cannot change the repository selection.
        val useRelayRepository = providers.gradleProperty("useRelayRepository").map { it.toBooleanStrict() }.orElse(true)
        buildConfigField("boolean", "USE_RELAY_REPOSITORY", useRelayRepository.get().toString())

        // Custom runner for the interactive-stream e2e prototype (#337/#642 rung 3). Installs
        // the test Application for every instrumented run; it selects fake unless the run carries
        // the e2e relay args (-e relayUrl …), which select the paired, tapped relay repository. Safe
        // for `connectedAndroidTest` as well as the managed-device run.
        testInstrumentationRunner = "de.pyryco.mobile.e2e.E2eInstrumentationRunner"
        // #1131: a failing device test logs the window manager's focus state for the gate to print.
        testInstrumentationRunnerArguments["listener"] = "de.pyryco.mobile.e2e.FocusRecordListener"
    }

    if (missingUploadSigningProperties.isEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(uploadSigningProperties.getValue("pyry.upload.storeFile").orEmpty())
                storePassword = uploadSigningProperties.getValue("pyry.upload.storePassword")
                keyAlias = uploadSigningProperties.getValue("pyry.upload.keyAlias")
                keyPassword = uploadSigningProperties.getValue("pyry.upload.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
    }
    // Screen tests live in sharedTest and compile into both runs: on the JVM under Robolectric for
    // every check, and on the emulator for an in-depth device run.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            // Robolectric reads FileDescriptor internals when it sets up Android 16 shared memory.
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // Independent expectation lets the binding test catch an incorrectly generated flag.
            it.systemProperty("expectedUseRelayRepository", providers.gradleProperty("useRelayRepository").orElse("true").get())
        }
        managedDevices {
            // Headless Automated Test Device (ATD): GPU off, no window. Generates the Gradle task
            // `pixel2Api33AtdDebugAndroidTest`, which creates, runs, and tears down the emulator with no
            // display — the e2e harness target driven by scripts/e2e-emulator.sh. The Google ATD image
            // carries Play services (#955): the live push scenarios need a real FCM token, which the
            // aosp-atd image cannot obtain. AGP auto-provisions the system image on first run; that needs
            // the image licence accepted.
            localDevices {
                create("pixel2Api33Atd") {
                    device = "Pixel 2"
                    apiLevel = 33
                    systemImageSource = "google-atd"
                }
            }
        }
    }
}

// A bundle is only built for Play upload, so it must carry the upload key. bundleRelease is a
// lifecycle task, so the guard gates packageReleaseBundle, which runs before any .aab is written.
val checkReleaseUploadSigning =
    tasks.register("checkReleaseUploadSigning") {
        val missing = missingUploadSigningProperties
        doLast {
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release bundles must be signed with the upload key. Missing Gradle properties: " +
                        missing.joinToString(", "),
                )
            }
        }
    }
// tasks.named fails configuration if an AGP upgrade renames the task, so the guard cannot silently lapse.
afterEvaluate {
    tasks.named("packageReleaseBundle") { dependsOn(checkReleaseUploadSigning) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.koin.bom))
    // #361: push only. No firebase-analytics; builds without google-services.json run with push off.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jetbrains.markdown)
    implementation(libs.okhttp)
    implementation(libs.snipme.highlights)
    lintChecks(libs.compose.lint.checks)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.espresso.core)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // The custom E2eInstrumentationRunner subclasses AndroidJUnitRunner — pull the runner artifact in
    // explicitly rather than rely on a transitive of espresso-core.
    androidTestImplementation(libs.androidx.test.runner)
    // Shared screen tests carry Robolectric's annotations; the device run only needs them to compile.
    androidTestImplementation(libs.robolectric.annotations)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
