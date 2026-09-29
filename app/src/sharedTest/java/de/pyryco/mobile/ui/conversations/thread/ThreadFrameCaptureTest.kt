package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Deterministic thread fixtures for real-emulator frame captures and compact-text reachability. */
@RunWith(AndroidJUnit4::class)
class ThreadFrameCaptureTest {
    @get:Rule val rule = createComposeRule()

    private var state by mutableStateOf(ThreadUiState("frame", "pyrycode discord integration", isPromoted = true))
    private var thinking by mutableStateOf(false)
    private var attachments by mutableStateOf(emptyList<PendingAttachment>())
    private var composeView: View? = null

    @OptIn(ExperimentalTestApi::class)
    private fun show(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    PyrycodeMobileTheme(darkTheme = true) {
                        composeView = LocalView.current
                        ThreadScreen(
                            state = state,
                            onBack = {},
                            onSendMessage = {},
                            connectionState = ConnectionState.Connected,
                            onRetry = {},
                            isThinking = thinking,
                            attachments = attachments,
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        rule.waitForIdle()
        val bitmap =
            if (name.endsWith("menu")) {
                checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            } else {
                rule.runOnIdle {
                    val view = checkNotNull(composeView)
                    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                        view.draw(Canvas(it))
                    }
                }
            }
        val file = File(directory, "frame-1206-$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "frame-1206-$name.txt").writeText("capture=${bitmap.width}x${bitmap.height} fixture=$name\n")
        bitmap.recycle()
    }

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val message =
        ThreadItem.MessageItem(
            Message(
                id = "m1",
                sessionId = "s1",
                role = Role.Assistant,
                content = "A fixed assistant reply for frame comparison.",
                timestamp = Instant.parse("2026-09-29T10:00:00Z"),
                isStreaming = false,
            ),
        )

    @Test
    fun referenceFrame_emptyPopulatedTaskAndMenu() {
        show(412, 892)
        rule.onNodeWithTag("thread-message-region").assertIsDisplayed()
        capture("412x892-empty")

        state = state.copy(hasMessages = true, items = listOf(message))
        rule.onNodeWithText("A fixed assistant reply for frame comparison.").assertIsDisplayed()
        capture("412x892-populated")

        state =
            state.copy(
                backgroundTasks =
                    BackgroundTaskRoster(
                        listOf(BackgroundTask("t1", "toolu_t1", "local_bash", "sleep 300", null, null, null, false)),
                        0,
                    ),
                backgroundTaskCount = 2,
            )
        thinking = true
        attachments = (1L..4L).map { PendingAttachment(it, "content://frame/$it", "fixture-$it.pdf", "application/pdf", 1) }
        rule.onNodeWithText("2 tasks running").assertIsDisplayed()
        capture("412x892-task")

        rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        rule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        capture("412x892-menu")
    }

    @Test
    fun compactWidthAndEnlargedText_keepFrameControlsReachable() {
        state =
            state.copy(
                displayName = "A very long conversation name that must truncate before the overflow control",
                hasMessages = true,
                items = listOf(message),
            )
        show(320, 640, fontScale = 1.5f)
        val back = rule.onNodeWithContentDescription(string(R.string.cd_back)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val overflow = rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val messageRegion = rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        assertTrue(back.right <= overflow.left)
        assertTrue(messageRegion.height > 0.dp)
        capture("320x640-large-text")
        rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        rule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        capture("320x640-large-text-menu")
    }
}
