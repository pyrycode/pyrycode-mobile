package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.conversations.thread.ThreadEffortChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadModelChoice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Real-device viewport and pixel evidence for the current dark Run configuration design. */
class RunConfigurationCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 0)
    val viewport =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val size = overrideOf(shell("wm size"))
                    val density = overrideOf(shell("wm density"))
                    shell("wm density 160")
                    shell("wm size 412x892")
                    try {
                        instrumentation.waitForIdleSync()
                        base.evaluate()
                    } finally {
                        shell("wm size $size")
                        shell("wm density $density")
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun darkRunConfigurationAt412By892() {
        val models =
            listOf("Fable", "Opus", "Sonnet", "Haiku").map { label ->
                ThreadModelChoice(label.lowercase(), label, "", emptyList())
            }
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                StatusSheet(
                    choices = models,
                    menuAvailable = true,
                    notListedModels = 0,
                    selectedModel = "sonnet",
                    onModelSelected = {},
                    effortChoices = listOf("Low", "Medium", "High", "Max").map { ThreadEffortChoice(it.lowercase(), it) },
                    selectedEffort = "high",
                    onEffortSelected = {},
                    pending = false,
                    enabled = true,
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Run configuration").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("Model").assertIsDisplayed()
        rule.onNodeWithText("Effort").assertIsDisplayed()
        rule.waitForIdle()
        SystemClock.sleep(600)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(412, image.width)
        assertEquals(892, image.height)
        val colors = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
            assertTrue("capture must contain rendered content", colors.toSet().size > 10)
        }
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "status-1195",
            ).apply { mkdirs() }
        File(output, "run-configuration-412x892.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
