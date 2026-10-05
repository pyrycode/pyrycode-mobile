package de.pyryco.mobile.e2e

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Logs what the window manager reports when a device test fails (#1131): the window with input focus, the
 * focused app, and any application-not-responding dialog that is showing.
 *
 * Registered for every instrumented run through the runner's `listener` argument in `app/build.gradle.kts`.
 * The one line it logs under tag [TAG] lands in the failing test's per-test logcat, and
 * `scripts/android-test-gate.py` prints it on the gate's stderr under the test's name.
 *
 * JUnit reports a failure only after the test's rules have run, so a Compose or activity rule has usually
 * closed the test's activity already, and `focus` then names whatever the teardown left focused, normally the
 * emulator's launcher. The `activities` field says so on the record itself (#1809): it is each activity the
 * failing test opened, with the last lifecycle stage it reached. `DESTROYED` means the launcher focus is a
 * teardown leftover, not a focus race. `RESUMED` beside a foreign `focus` is a real loss of focus.
 *
 * Recording never changes a result: JUnit turns an exception thrown by a listener into an extra failure, so
 * every error is caught and logged as an `error=` record instead. `dumpsys` bounds its own dump time.
 */
class FocusRecordListener : RunListener() {
    // Written by lifecycle callbacks on the main thread, read here on the instrumentation thread.
    private val stages = ConcurrentHashMap<String, Stage>()
    private val tracker = ActivityLifecycleCallback { activity, stage -> stages[activityName(activity)] = stage }

    override fun testRunStarted(description: Description?) {
        try {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(tracker)
        } catch (error: Throwable) {
            Log.w("E2E", "FocusRecordListener: activity tracking unavailable: ${error.javaClass.simpleName}")
        }
    }

    override fun testStarted(description: Description?) {
        stages.clear()
    }

    override fun testFailure(failure: Failure) {
        val test = testName(failure.description)
        val record =
            try {
                focusRecord(test, windowDump(), stages.toMap())
            } catch (error: Throwable) {
                "test=$test error=${error.javaClass.simpleName}: ${error.message?.take(VALUE_LIMIT)}"
            }
        Log.w(TAG, record)
    }

    private fun windowDump(): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window")
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    companion object {
        const val TAG = "FocusRecord"
        private const val VALUE_LIMIT = 400
        private val ANR_WINDOW = Regex("""Window\{\S+ u\d+ (Application Not Responding: [^}]+)\}""")

        fun testName(description: Description): String =
            description.methodName?.let { "${description.className}#$it" } ?: description.displayName

        private fun activityName(activity: Any) =
            "${activity.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(activity))}"

        /**
         * One record line from a `dumpsys window` dump; each field lists its distinct values. [stages] maps each
         * activity the test opened to the last lifecycle stage it reached.
         */
        fun focusRecord(
            test: String,
            dump: String,
            stages: Map<String, Stage> = emptyMap(),
        ): String {
            fun values(key: String) =
                dump
                    .lineSequence()
                    .mapNotNull { line -> line.substringAfter("$key=", "").trim().ifEmpty { null } }
                    .distinct()
                    .joinToString(" | ")
                    .ifEmpty { "unknown" }
                    .take(VALUE_LIMIT)
            val anr =
                ANR_WINDOW
                    .findAll(dump)
                    .map { it.groupValues[1] }
                    .distinct()
                    .joinToString(" | ")
                    .ifEmpty { "none" }
                    .take(VALUE_LIMIT)
            val activities =
                stages.entries
                    .joinToString(" | ") { (name, stage) -> "$name:$stage" }
                    .ifEmpty { "none" }
                    .take(VALUE_LIMIT)
            return "test=$test focus=${values("mCurrentFocus")} focusedApp=${values("mFocusedApp")} anr=$anr " +
                "activities=$activities"
        }
    }
}
