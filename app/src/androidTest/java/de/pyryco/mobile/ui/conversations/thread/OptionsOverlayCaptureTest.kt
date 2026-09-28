package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device pixels for the Actions and slash overlays at the design's 412 × 892 viewport. */
@RunWith(AndroidJUnit4::class)
class OptionsOverlayCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private var draft by mutableStateOf("")
    private var contentView: View? = null

    @Before fun setViewport() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        shell("wm density 160")
        shell("wm size 412x892")
        instrumentation.waitForIdleSync()
    }

    @After fun restoreViewport() {
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        instrumentation.waitForIdleSync()
    }

    @Test fun actionsAt412By892() {
        setThread()
        rule.onNodeWithText("Actions").performClick()
        rule.onNodeWithText("Reset session").assertExists()
        capture("actions-412x892.png")
    }

    @Test fun slashDetailAt412By892() {
        setThread()
        rule.onNode(hasSetTextAction()).performTextReplacement("/")
        rule.onNodeWithText("Switch the model").assertExists()
        capture("slash-412x892.png")
    }

    private fun setThread() {
        draft = ""
        val models = listOf(ThreadModelChoice("opus", "Opus", "", emptyList()))
        val commands =
            listOf(
                SlashCommandMenuRow("clear", "", "Start a new session", emptyList(), null),
                SlashCommandMenuRow("model", "<model>", "Switch the model", emptyList(), null),
                SlashCommandMenuRow("compact", "", "Compact the session", emptyList(), null),
            )
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                contentView = LocalView.current
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "capture",
                            displayName = "Test channel",
                            isPromoted = true,
                            runConfig =
                                ThreadRunConfig(
                                    choices = models,
                                    menuAvailable = true,
                                    settingsAvailable = true,
                                    savedModel = "opus",
                                    sessionId = "capture-session",
                                ),
                            slashCommands = commands,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    draft = draft,
                    onDraftChange = { draft = it },
                )
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(contentView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)
        val colors = (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }
        assertTrue("capture must contain rendered content", colors.toSet().size > 10)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "options-1257",
            ).apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
