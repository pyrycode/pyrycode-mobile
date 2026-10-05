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
 *
 * On an emulator every run also switches Bluetooth off, hides system crash dialogs and closes any already
 * showing (2026-10-05). The API 33 ATD image's Bluetooth service can crash-loop after boot, and its "keeps
 * stopping" dialog took window focus from whichever test ran then (#1135, #1157, #1166, #1217, #1232, #1235,
 * #1277). The app declares no Bluetooth permission and no test uses it. The Gradle managed device takes no
 * emulator hardware settings, so this runs here, after boot. The dialog setting is put back when the run
 * finishes. Bluetooth stays off: switching it back on would restart the crash loop on an emulator that
 * scripted-all reuses for its next scenario.
 */
class E2eInstrumentationRunner : AndroidJUnitRunner() {
    private var disableAnimations = false
    private var onEmulator = false
    private var restoreScales: Map<String, String> = emptyMap()
    private var restoreDialogs: String? = null

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        Log.i("E2E", "runner.newApplication → installing E2eTestApplication (was $className)")
        return super.newApplication(cl, E2eTestApplication::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle?) {
        onEmulator = Build.HARDWARE in EMULATOR_HARDWARE
        disableAnimations = arguments?.getString("disableAnimations") == "true" && onEmulator
        super.onCreate(arguments)
    }

    override fun onStart() {
        if (onEmulator) quietSystem()
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
        restoreDialogs?.let(::shell)
        super.finish(resultCode, results)
    }

    /** Bluetooth off and no system crash dialogs for the run; see the class comment. */
    private fun quietSystem() {
        val hideDialogs = shell("settings get global $HIDE_ERROR_DIALOGS").trim()
        shell("settings put global $HIDE_ERROR_DIALOGS 1")
        if (shell("settings get global bluetooth_on").trim() == "1") shell("cmd bluetooth_manager disable")
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        restoreDialogs =
            if (hideDialogs.isEmpty() || hideDialogs == "null") {
                "settings delete global $HIDE_ERROR_DIALOGS"
            } else {
                "settings put global $HIDE_ERROR_DIALOGS $hideDialogs"
            }
    }

    /** Runs [command] as the shell user and waits for it to finish. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        val ANIMATION_SCALES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")
        const val HIDE_ERROR_DIALOGS = "hide_error_dialogs"
    }
}
