package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
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
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** Deterministic thread fixtures for real-emulator frame captures and compact-text reachability. */
@RunWith(AndroidJUnit4::class)
class ThreadFrameCaptureTest {
    @get:Rule val rule = createComposeRule()

    private val clipboard =
        object : ClipboardManager {
            var copiedText: String? = null

            override fun setText(annotatedString: AnnotatedString) {
                copiedText = annotatedString.text
            }

            override fun getText(): AnnotatedString? = copiedText?.let(::AnnotatedString)

            override fun hasText(): Boolean = copiedText != null
        }

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
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalClipboardManager provides clipboard,
                ) {
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

    private fun capture(
        name: String,
        prefix: String = "frame-1206",
    ) {
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
        val file = File(directory, "$prefix-$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$prefix-$name.txt").writeText("capture=${bitmap.width}x${bitmap.height} fixture=$name\n")
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

    private val figmaLongText =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Mauris at quam euismod, porta arcu vel, " +
            "dictum est. Aenean est tellus, sodales sed ante vitae, condimentum volutpat mauris. In luctus justo " +
            "massa, ac pulvinar massa ornare vitae.\n\n" +
            "Morbi efficitur scelerisque augue, in pretium erat tempor in."

    private fun presentationMessage(
        id: String,
        role: Role,
        content: String,
        sessionId: String = "s1",
    ) = ThreadItem.MessageItem(
        Message(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            timestamp = Instant.parse("2026-01-13T11:55:00Z"),
            isStreaming = false,
        ),
    )

    @Test
    fun messagePresentation_longTextMarkdownAndBoundaryAtReferenceSize() {
        val priorLocale = Locale.getDefault()
        val priorZone = TimeZone.getDefault()
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Helsinki"))
        try {
            state =
                state.copy(
                    hasMessages = true,
                    items =
                        listOf(
                            presentationMessage("a1", Role.Assistant, figmaLongText),
                            presentationMessage("u1", Role.User, figmaLongText),
                        ),
                )
            show(412, 892)
            rule.onAllNodesWithText("Morbi efficitur", substring = true)[0].assertIsDisplayed()
            capture("412x892-long-text", prefix = "message-1207")

            state =
                state.copy(
                    items =
                        listOf(
                            presentationMessage("a2", Role.Assistant, "Mauris at quam euismod.", sessionId = "s0"),
                            ThreadItem.SessionBoundary(
                                previousSessionId = "s0",
                                newSessionId = "s1",
                                reason = BoundaryReason.Clear,
                                occurredAt = Instant.parse("2026-01-13T11:55:00Z"),
                                workspaceCwd = null,
                            ),
                            presentationMessage("u2", Role.User, "Lorem ipsum dolor sit amet."),
                            presentationMessage(
                                "a3",
                                Role.Assistant,
                                "Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n\n" +
                                    "```typescript\nfunction migrateLegacyOrders(legacy: LegacyOrder[]): Order[] {\n" +
                                    "  return legacy.map(o => o.toModern())\n}\n```\n\n" +
                                    "Morbi efficitur scelerisque augue, in pretium erat tempor in.",
                            ),
                        ),
                )
            rule.onNodeWithText("typescript").assertIsDisplayed()
            capture("412x892-code-boundary", prefix = "message-1207")
        } finally {
            Locale.setDefault(priorLocale)
            TimeZone.setDefault(priorZone)
        }
    }

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
        capture("320x640-before-copy", prefix = "message-1207")
        val copy =
            rule
                .onNodeWithContentDescription(string(R.string.cd_thread_copy_message))
                .performScrollTo()
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val messageRegion = rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        assertTrue(back.right <= overflow.left)
        assertTrue(messageRegion.height > 0.dp)
        assertTrue(copy.left >= messageRegion.left && copy.right <= messageRegion.right)
        // Tap outside the 16dp visual row: Compose must still route the pointer to copy at 1.5x text.
        rule.onNodeWithContentDescription(string(R.string.cd_thread_copy_message)).performTouchInput {
            click(Offset(center.x, bottom + 14.dp.toPx()))
        }
        assertEquals(message.message.content, clipboard.copiedText)
        capture("320x640-large-text")
        rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        rule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        capture("320x640-large-text-menu")
    }
}
