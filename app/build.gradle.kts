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
        versionCode = 1
        versionName = "1.0"

        val gitSha = providers.of(GitShaValueSource::class.java) {}
        buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"")

        // #350: selects the bound ConversationRepository. OFF = FakeConversationRepository (the
        // default — previews, tests, unchanged behaviour); ON = the relay-backed StableConversationRepository
        // facade. A compile-time constant with no runtime setter — not flippable by any untrusted input.
        // Stays "false" until the relay backend is functional end-to-end (depends on #346/#347/#348, #336, #337).
        buildConfigField("boolean", "USE_RELAY_REPOSITORY", "false")

        // Custom runner for the interactive-stream e2e prototype (#337/#642 rung 3). Pass-through to
        // the stock AndroidJUnitRunner for every existing component test; only swaps in the paired,
        // relay-backed test Application when the run carries the e2e relay args (-e relayUrl …). Safe
        // for `connectedAndroidTest` as well as the managed-device run.
        testInstrumentationRunner = "de.pyryco.mobile.e2e.E2eInstrumentationRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
    testOptions {
        managedDevices {
            // Headless Automated Test Device (ATD): GPU off, no window, no Play services. Generates the
            // Gradle task `pixel2Api33AtdDebugAndroidTest`, which creates, runs, and tears down the
            // emulator with no display — the e2e harness target driven by scripts/e2e-emulator.sh.
            // If the paired happy-path ever needs Play services, switch systemImageSource to
            // "google-atd" (still headless). AGP auto-provisions the system image on first run; that
            // needs the SDK cmdline-tools installed and the image licence accepted.
            localDevices {
                create("pixel2Api33Atd") {
                    device = "Pixel 2"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.koin.bom))
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // The custom E2eInstrumentationRunner subclasses AndroidJUnitRunner — pull the runner artifact in
    // explicitly rather than rely on a transitive of espresso-core.
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
