package de.pyryco.mobile.e2e

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.components.MobileModalTestIme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicReference

/**
 * ATD images omit LatinIME, so a method asserting real keyboard visibility calls [select] to use the test
 * APK's keyboard. Methods that never call it keep the device's own setting; the rule restores it after any
 * method that did.
 * [selectBeforeTest] selects before inner rules set up, then restores after their cleanup.
 */
class TestImeRule(
    private val selectBeforeTest: Boolean = false,
) : TestRule {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var restore: (() -> Unit)? = null
    private var diagnostics: Diagnostics? = null

    fun select() {
        if (restore != null) return
        val trace = diagnostics ?: Diagnostics().also { diagnostics = it }
        trace.phase("selection_started")
        val resolver = instrumentation.targetContext.contentResolver
        val id = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
        trace.phase("settings_read")
        val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        trace.phase("settings_read_complete")
        trace.phase("service_read")
        val enabled =
            instrumentation.targetContext
                .getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList
                .any { it.id == id }
        trace.phase("service_read_complete")
        restore = {
            if (!previous.isNullOrEmpty()) shell("ime set $previous", "restore_set")
            if (!enabled) shell("ime disable $id", "restore_disable")
            if (previous.isNullOrEmpty()) shell("settings delete secure default_input_method", "restore_delete")
        }
        Log.i("TestIme", "event=selection_initial_state test_ime_selected=${previous == id} test_ime_enabled=$enabled")
        shell("ime enable $id", "enable")
        shell("ime set $id", "set")
        trace.phase("idle_sync")
        instrumentation.waitForIdleSync()
        trace.phase("selection_complete")
    }

    /** Observe the existing first Compose wait without adding synchronization or extending its deadline. */
    fun observeFirstComposeWait(wait: () -> Unit) {
        diagnostics?.phase("first_compose_wait")
        wait()
        diagnostics?.phase("first_compose_wait_complete")
        diagnostics?.phase("test_body")
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                Log.i("TestIme", "event=rule_started method=${description.methodName} pid=${Process.myPid()}")
                try {
                    if (selectBeforeTest) select()
                    base.evaluate()
                } finally {
                    diagnostics?.phase("restoration_started")
                    try {
                        restore?.invoke()
                        diagnostics?.phase("restoration_complete")
                    } finally {
                        restore = null
                        diagnostics?.close()
                        diagnostics = null
                    }
                }
            }
        }

    private fun shell(
        command: String,
        phase: String,
    ) {
        diagnostics?.phase("${phase}_shell_start")
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        diagnostics?.phase("${phase}_shell_submitted")
        ParcelFileDescriptor
            .AutoCloseInputStream(output)
            .bufferedReader()
            .use { it.readText() }
        diagnostics?.phase("${phase}_shell_complete")
    }

    /** Bounded passive stacks: no diagnostic binder/shell calls can hide the stalled operation. */
    private class Diagnostics {
        private val caller = Thread.currentThread()
        private val stage = AtomicReference("selection_started" to SystemClock.uptimeMillis())
        private val scope = CoroutineScope(Dispatchers.Default)

        init {
            scope.launch {
                var samples = 0
                var sampled: Pair<String, Long>? = null
                while (samples < 4) {
                    delay(1_000)
                    val current = stage.get()
                    if (current == sampled || SystemClock.uptimeMillis() - current.second < 5_000) continue
                    sampled = current
                    samples++
                    Log.i("TestIme", "event=phase_blocked stage=${current.first} sample=$samples pid=${Process.myPid()}")
                    for ((name, thread) in listOf("instrumentation" to caller, "main" to Looper.getMainLooper().thread)) {
                        Log.i("TestIme", "event=phase_thread thread=$name state=${thread.state}")
                        thread.stackTrace.take(24).forEach { frame -> Log.i("TestIme", "event=phase_stack thread=$name frame=$frame") }
                    }
                }
            }
        }

        fun phase(name: String) {
            val now = SystemClock.uptimeMillis()
            stage.set(name to now)
            Log.i("TestIme", "event=selection_stage stage=$name uptime_ms=$now pid=${Process.myPid()}")
        }

        fun close() {
            scope.cancel()
            Log.i("TestIme", "event=diagnostics_closed pid=${Process.myPid()}")
        }
    }
}
