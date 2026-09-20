package de.pyryco.mobile.e2e

import android.app.Application
import android.content.Context
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
 */
class E2eInstrumentationRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        Log.i("E2E", "runner.newApplication → installing E2eTestApplication (was $className)")
        return super.newApplication(cl, E2eTestApplication::class.java.name, context)
    }
}
