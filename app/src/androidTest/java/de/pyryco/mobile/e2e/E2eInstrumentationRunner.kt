package de.pyryco.mobile.e2e

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumentation runner for the interactive-stream e2e prototype (#337 / #642 rung 3).
 *
 * When the run carries the e2e relay arguments (`-e relayUrl …`), it substitutes [E2eTestApplication]
 * — paired + relay-backed — for the production app. Otherwise it is a pass-through to the stock
 * [AndroidJUnitRunner], so every existing component test keeps its unchanged environment (the default
 * fake repository, no pre-written pairing). Wired as the module's `testInstrumentationRunner`.
 *
 * Reading [InstrumentationRegistry.getArguments] in [newApplication] is safe: the runner's own
 * `onCreate` registers the argument bundle before the app under test is instantiated.
 */
class E2eInstrumentationRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        val isE2eRun = InstrumentationRegistry.getArguments().getString(E2eTestApplication.ARG_RELAY_URL) != null
        return super.newApplication(
            cl,
            if (isE2eRun) E2eTestApplication::class.java.name else className,
            context,
        )
    }
}
