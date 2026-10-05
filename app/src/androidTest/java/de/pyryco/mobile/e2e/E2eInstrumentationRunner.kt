package de.pyryco.mobile.e2e

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumentation runner for the interactive-stream e2e prototype (#337 / #642 rung 3).
 *
 * It substitutes [E2eTestApplication] for the production app in **every** instrumented run. The
 * decision of whether to do e2e setup (pre-pair + bind the relay-backed repository) or to select
 * the fake repository is made inside [E2eTestApplication.onCreate], based on whether the
 * run carries the e2e relay arguments. So this is safe for ordinary `connectedAndroidTest` runs too.
 *
 * Why not read the arguments here: Android calls [newApplication] **before** the instrumentation
 * registers its arguments, so `InstrumentationRegistry.getArguments()` would throw at this point.
 * Reading them is deferred to [E2eTestApplication.onCreate], which runs after registration.
 *
 * `disableAnimations=true` sets the window, transition and animator scales to 0 for the run and puts the old
 * values back when it finishes (2026-10-05). `scripts/android-test-gate.py` passes it for the device-only
 * screen tests and the scripted scenarios on its own emulators. It never applies on a physical phone.
 */
class E2eInstrumentationRunner : AndroidJUnitRunner() {
    private var disableAnimations = false
    private var restoreScales: Map<String, String> = emptyMap()

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        Log.i("E2E", "runner.newApplication → installing E2eTestApplication (was $className)")
        return super.newApplication(cl, E2eTestApplication::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle?) {
        disableAnimations = arguments?.getString("disableAnimations") == "true" && Build.HARDWARE in EMULATOR_HARDWARE
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (disableAnimations) {
            restoreScales = ANIMATION_SCALES.associateWith { shell("settings get global $it").trim() }
            ANIMATION_SCALES.forEach { shell("settings put global $it 0") }
        }
        super.onStart()
    }

    override fun finish(
        resultCode: Int,
        results: Bundle?,
    ) {
        restoreScales.forEach { (name, value) ->
            shell(if (value.isEmpty() || value == "null") "settings delete global $name" else "settings put global $name $value")
        }
        super.finish(resultCode, results)
    }

    /** Runs [command] as the shell user and waits for it to finish. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        val ANIMATION_SCALES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")
    }
}
