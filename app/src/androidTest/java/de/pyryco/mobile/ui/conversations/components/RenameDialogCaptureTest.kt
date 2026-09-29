package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Actual device pixels of Rename at the shared components' 412 × 892 viewport. */
class RenameDialogCaptureTest {
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
    fun renameAtFigmaViewport() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                RenameDialog(initialName = "Pyrybox", onSubmit = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.waitForIdle()
        SystemClock.sleep(600)

        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)
        val sampledColors =
            (0 until bitmap.height step 16).flatMap { y ->
                (0 until bitmap.width step 16).map { x -> bitmap.getPixel(x, y) }
            }
        // The managed ATD image can return a black framebuffer despite rendered Compose semantics.
        // The full pixel8Api35 image is the pixel-evidence run.
        assumeTrue("capture must contain rendered content", sampledColors.toSet().size > 10)
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "rename-1278")
        output.mkdirs()
        File(output, "rename-412x892.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
