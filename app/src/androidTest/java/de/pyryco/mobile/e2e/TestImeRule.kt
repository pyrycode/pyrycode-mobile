package de.pyryco.mobile.e2e

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.components.MobileModalTestIme
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * ATD images omit LatinIME, so a method asserting real keyboard visibility calls [select] to use the test
 * APK's keyboard. Methods that never call it keep the device's own setting; the rule restores it after any
 * method that did.
 */
class TestImeRule : TestRule {
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
        shell("ime enable $id")
        shell("ime set $id")
        instrumentation.waitForIdleSync()
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
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
