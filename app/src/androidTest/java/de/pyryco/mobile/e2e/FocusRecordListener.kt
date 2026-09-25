package de.pyryco.mobile.e2e

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/**
 * Logs what the window manager reports when a device test fails (#1131): the window with input focus, the
 * focused app, and any application-not-responding dialog that is showing.
 *
 * Registered for every instrumented run through the runner's `listener` argument in `app/build.gradle.kts`.
 * The one line it logs under tag [TAG] lands in the failing test's per-test logcat, and
 * `scripts/android-test-gate.py` prints it on the gate's stderr under the test's name.
 *
 * Recording never changes a result: JUnit turns an exception thrown by a listener into an extra failure, so
 * every error is caught and logged as an `error=` record instead. `dumpsys` bounds its own dump time.
 */
class FocusRecordListener : RunListener() {
    override fun testFailure(failure: Failure) {
        val test = testName(failure.description)
        val record =
            try {
                focusRecord(test, windowDump())
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

        /** One record line from a `dumpsys window` dump; each field lists its distinct values. */
        fun focusRecord(
            test: String,
            dump: String,
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
            return "test=$test focus=${values("mCurrentFocus")} focusedApp=${values("mFocusedApp")} anr=$anr"
        }
    }
}
