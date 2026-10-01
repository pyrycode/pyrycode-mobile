package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.design.Viewport
import de.pyryco.mobile.design.ViewportRule
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real fixed-dark pixels for the thread's five status readings at the Figma viewport. */
@RunWith(AndroidJUnit4::class)
class ThreadActivityIndicatorCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var reading by mutableIntStateOf(0)
    private var contentView: View? = null

    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test fun fiveReadingsAndMenuAt412By892() {
        showThread()
        val readings =
            listOf(
                Triple(0, "Claude is thinking, about 184 tokens into its current reasoning step", "thinking"),
                Triple(6, "Agent is thinking", "thinking-plain"),
                Triple(1, "Claude is retrying, attempt 3 of 10", "retry"),
                Triple(2, "Claude is compacting the conversation", "compacting"),
                Triple(3, "Restarting", "reset"),
                Triple(4, "Turn interrupted", "outcome"),
            )
        readings.forEach { (value, description, name) ->
            reading = value
            rule.onNodeWithContentDescription(description).assertIsDisplayed()
            capture("412x892-$name.png", 412, 892, 1f)
        }
        reading = 0
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithContentDescription("Claude is thinking, about 184 tokens into its current reasoning step").assertIsDisplayed()
        capture("412x892-menu.png", 412, 892, 1f)
    }

    @Viewport("320x692")
    @Test
    fun compactLargeTextKeepsOutcomeInTheStatusArea() {
        showThread(fontScale = 1.5f)
        listOf(
            Triple(6, "Agent is thinking", "thinking"),
            Triple(1, "Claude is retrying, attempt 3 of 10", "retry"),
            Triple(2, "Claude is compacting the conversation", "compacting"),
            Triple(7, "Claude is writing a handoff note for the next session", "reset-wrap"),
            Triple(5, "Turn failed · Claude reports prompt_too_long, API error invalid_request", "outcome-tasks"),
        ).forEach { (value, description, name) ->
            reading = value
            rule.onNodeWithContentDescription(description).assertIsDisplayed()
            capture("320x692-large-text-$name.png", 320, 692, 1.5f)
        }
    }

    private fun showThread(fontScale: Float = 1f) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    contentView = LocalView.current
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "visual_fixture",
                                displayName = "pyrycode discord integration",
                                isPromoted = true,
                                agent = ConversationAgent.Claude,
                                backgroundTaskCount = if (reading == 5) 2 else 0,
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        isThinking = reading == 0 || reading == 6,
                        thinkingProgress = if (reading == 0) ThinkingProgress(184, 64) else null,
                        apiRetry = if (reading == 1) ApiRetryStatus.Attempt(3, 10) else ApiRetryStatus.NotRetrying,
                        isCompacting = reading == 2,
                        resetting =
                            when (reading) {
                                3 -> ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Pending)
                                7 -> ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
                                else -> null
                            },
                        turnOutcome =
                            when (reading) {
                                4 -> TurnOutcomeReport(TurnOutcomeReport.Kind.Interrupted, emptyList(), null)
                                5 -> TurnOutcomeReport(TurnOutcomeReport.Kind.Failed, listOf("prompt_too_long"), "invalid_request")
                                else -> null
                            },
                    )
                }
            }
        }
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(contentView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val samples = (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }
        assertTrue("capture must contain rendered content", samples.toSet().size > 10)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "thread-activity-1209",
            ).apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText(
            "api=${Build.VERSION.SDK_INT} build=${shell("getprop ro.build.version.incremental").trim()} " +
                "sizeDp=${width}x$height density=1.0 fontScale=$fontScale staticDark=true " +
                "capture=decorView.draw design=533:1957,347:6618,16:8 date=2026-09-29\n",
        )
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
