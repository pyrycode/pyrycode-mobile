package de.pyryco.mobile.design

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The display size and system font scale a capture test runs at; without it [ViewportRule] applies 412x892 at 1.0. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Viewport(
    val size: String,
    val fontScale: Float = 1f,
)

/**
 * Applies density 160 (1 px per dp), the method's [Viewport] size and font scale, then restores all three.
 *
 * Resizing or rescaling under a running activity can recreate or refocus it (#1402), so use this as the
 * outermost rule (`order = 0`): the viewport settles before a compose rule or the test launches an activity.
 */
class ViewportRule : TestRule {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val viewport = description.getAnnotation(Viewport::class.java)
                val size = overrideOf(shell("wm size"))
                val density = overrideOf(shell("wm density"))
                val fontScale = shell("settings get system font_scale").trim()
                try {
                    shell("wm density 160")
                    shell("wm size ${viewport?.size ?: "412x892"}")
                    shell("settings put system font_scale ${viewport?.fontScale ?: 1f}")
                    instrumentation.waitForIdleSync()
                    base.evaluate()
                } finally {
                    shell("wm size $size")
                    shell("wm density $density")
                    if (fontScale.isEmpty() || fontScale == "null") {
                        shell("settings delete system font_scale")
                    } else {
                        shell("settings put system font_scale $fontScale")
                    }
                    instrumentation.waitForIdleSync()
                }
            }
        }

    private fun overrideOf(output: String): String =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}

/** Runs [command] through the instrumentation's shell and returns its output; shared by the design rules. */
internal fun shell(command: String): String =
    ParcelFileDescriptor
        .AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader()
        .use { it.readText() }
