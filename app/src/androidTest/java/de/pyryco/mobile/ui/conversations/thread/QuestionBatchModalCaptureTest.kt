package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Actual dark 412 × 892 device pixels for comparison with Figma's question components. */
class QuestionBatchModalCaptureTest {
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
    fun darkQuestionBatchAtFigmaViewport() {
        val choices =
            listOf(
                QuestionOption("Kotlin", "The JVM language"),
                QuestionOption("Rust", "A systems language"),
            )
        val state =
            QuestionModalState(
                batch =
                    QuestionBatch(
                        conversationId = "capture",
                        questionBatchId = "capture",
                        questions =
                            listOf(
                                Question("Which language should you learn next?", "Language", choices, multiSelect = false),
                                Question("Which targets matter?", "Targets", choices, multiSelect = true),
                            ),
                    ),
                selections =
                    listOf(
                        QuestionSelection(optionIndices = setOf(0)),
                        QuestionSelection(optionIndices = setOf(0), otherTicked = true, otherText = "Web"),
                    ),
            )
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                QuestionBatchModal(state = state, onEvent = {})
            }
        }
        rule.onNodeWithText("Continue").assertIsDisplayed()
        rule.onNodeWithText("Which language should you learn next?").assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") != "true") return
        SystemClock.sleep(600)
        // The gate uses FLAG_SECURE, so SurfaceFlinger screenshots are blank. Draw the actual
        // emulator dialog view instead, without changing the window's security policy.
        val dialog = WindowInspector.getGlobalWindowViews().last()
        val location = IntArray(2)
        dialog.getLocationOnScreen(location)
        val image = Bitmap.createBitmap(412, 892, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        canvas.translate(location[0].toFloat(), location[1].toFloat())
        dialog.draw(canvas)
        assertEquals(412, image.width)
        assertEquals(892, image.height)
        val samples = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        assertTrue("real emulator capture must contain rendered content", samples.toSet().size > 10)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "question-1299",
            ).apply { mkdirs() }
        File(output, "question-batch-412x892.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
