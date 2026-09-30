package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.ui.components.MobileModalTestIme
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
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    val size = overrideOf(shell("wm size"))
                    val density = overrideOf(shell("wm density"))
                    shell("wm density 160")
                    val compact = description.methodName.contains("Compact")
                    shell(if (compact) "wm size 320x700" else "wm size 412x892")
                    try {
                        instrumentation.waitForIdleSync()
                        if (description.methodName.contains("Keyboard")) withTestIme { base.evaluate() } else base.evaluate()
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
    fun darkQuestionBatchAtFigmaViewport() = capture("normal", compact = false, keyboard = false)

    @Test
    fun darkCompactQuestionBatchAtFigmaViewport() = capture("compact", compact = true, keyboard = false)

    @Test
    fun darkKeyboardQuestionBatchAtFigmaViewport() = capture("keyboard", compact = false, keyboard = true)

    private fun capture(
        name: String,
        compact: Boolean,
        keyboard: Boolean,
    ) {
        val choices =
            listOf(
                QuestionOption("Kotlin", "The JVM language"),
                QuestionOption("Rust", "A systems language"),
            )
        var state by mutableStateOf(
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
            ),
        )
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.5f else 1f)) {
                    ThreadScreen(
                        state = ThreadUiState("capture", "Client planning", runConfig = ThreadRunConfig(contextPercent = 84)),
                        onBack = {},
                        onSendMessage = {},
                        draft = "My message",
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        questionState = state,
                        onQuestionEvent = { event, _ ->
                            if (event is QuestionModalEvent.OtherTextChanged) {
                                state =
                                    state.copy(
                                        selections =
                                            state.selections.mapIndexed { index, selection ->
                                                if (index ==
                                                    event.questionIndex
                                                ) {
                                                    selection.copy(otherText = event.text, otherTicked = true)
                                                } else {
                                                    selection
                                                }
                                            },
                                    )
                            }
                        },
                    )
                }
            }
        }
        val list = rule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Continue"))
        rule.onNodeWithText("Continue").assertIsDisplayed()
        if (!compact && !keyboard) rule.onNodeWithTag("question-batch-title").assertIsDisplayed()
        val view = rule.activity.window.decorView
        var ime = 0
        if (keyboard) {
            list.performScrollToNode(hasTestTag("question_other_1"))
            rule.onNodeWithTag("question_other_1").performClick()
            rule.runOnIdle { view.windowInsetsController?.show(WindowInsets.Type.ime()) }
            rule.waitUntil(10_000) {
                rule.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            }
            rule.runOnIdle { ime = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0 }
            assertTrue("real keyboard inset", ime > 0)
            rule.onNodeWithTag("question_other_1").assertIsDisplayed().assertTextContains("Web")
        }
        SystemClock.sleep(300)
        val width = if (compact) 320 else 412
        val fullHeight = if (compact) 700 else 892
        val image = Bitmap.createBitmap(width, fullHeight - ime, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        rule.runOnIdle {
            assertTrue(
                "prompt stays capture protected",
                rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
            view.draw(canvas)
        }
        assertEquals(width, image.width)
        val samples = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        assertTrue("real emulator capture must contain rendered content", samples.toSet().size > 10)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "question-1305",
            ).apply { mkdirs() }
        File(output, "question-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(
            output,
            "question-$name.txt",
        ).writeText(
            "staticFixture=true width=$width fullHeight=$fullHeight fontScale=${if (compact) 1.5 else 1.0} imeInset=$ime secure=true\n",
        )
        image.recycle()
        if (keyboard) {
            list.performScrollToNode(hasText("Cancel"))
            rule.onNodeWithText("Cancel").assertIsDisplayed()
            list.performScrollToNode(hasText("Continue"))
            rule.onNodeWithText("Continue").assertIsDisplayed()
            rule.runOnIdle { view.windowInsetsController?.hide(WindowInsets.Type.ime()) }
            rule.waitUntil(
                10_000,
            ) { rule.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true } }
            list.performScrollToNode(hasTestTag("question_other_1"))
            rule.onNodeWithTag("question_other_1").assertTextContains("Web")
        }
    }

    private fun withTestIme(block: () -> Unit) {
        val resolver = instrumentation.targetContext.contentResolver
        val imeId = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
        val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val manager = instrumentation.targetContext.getSystemService(InputMethodManager::class.java)
        val wasEnabled = manager.enabledInputMethodList.any { it.id == imeId }
        try {
            shell("ime enable $imeId")
            shell("ime set $imeId")
            instrumentation.waitForIdleSync()
            block()
        } finally {
            if (!previous.isNullOrEmpty()) shell("ime set $previous")
            if (!wasEnabled) shell("ime disable $imeId")
            if (previous.isNullOrEmpty()) shell("settings delete secure default_input_method")
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
