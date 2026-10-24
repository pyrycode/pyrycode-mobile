package de.pyryco.mobile.e2e

import android.os.Looper
import android.os.ParcelFileDescriptor
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

    fun select() {
        if (restore != null) return
        val resolver = instrumentation.targetContext.contentResolver
        val id = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
        val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val enabled =
            instrumentation.targetContext
                .getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList
                .any { it.id == id }
        restore = {
            if (!previous.isNullOrEmpty()) shell("ime set $previous")
            if (!enabled) shell("ime disable $id")
            if (previous.isNullOrEmpty()) shell("settings delete secure default_input_method")
        }
        val caller = Thread.currentThread()
        val stage = AtomicReference("enable")
        val diagnostics = CoroutineScope(Dispatchers.Default)
        diagnostics.launch {
            delay(5_000)
            Log.i("TestIme", "event=selection_blocked stage=${stage.get()}")
            for ((name, thread) in listOf("instrumentation" to caller, "main" to Looper.getMainLooper().thread)) {
                thread.stackTrace.take(24).forEach { frame -> Log.i("TestIme", "event=selection_stack thread=$name frame=$frame") }
            }
            val selected = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id
            val currentlyEnabled =
                instrumentation.targetContext
                    .getSystemService(InputMethodManager::class.java)
                    .enabledInputMethodList
                    .any { it.id == id }
            Log.i("TestIme", "event=selection_state test_ime_selected=$selected test_ime_enabled=$currentlyEnabled")
        }
        try {
            Log.i("TestIme", "event=selection_initial_state test_ime_selected=${previous == id} test_ime_enabled=$enabled")
            Log.i("TestIme", "event=selection_stage stage=enable")
            shell("ime enable $id")
            stage.set("set")
            Log.i("TestIme", "event=selection_stage stage=set")
            shell("ime set $id")
            stage.set("idle")
            Log.i("TestIme", "event=selection_stage stage=idle")
            instrumentation.waitForIdleSync()
            Log.i("TestIme", "event=selection_stage stage=complete")
        } finally {
            diagnostics.cancel()
        }
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    if (selectBeforeTest) select()
                    base.evaluate()
                } finally {
                    restore?.invoke()
                    restore = null
                }
            }
        }

    private fun shell(command: String) {
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
    }
}
