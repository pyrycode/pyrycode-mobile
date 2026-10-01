package de.pyryco.mobile.ui.conversations.thread

import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.AttachmentAction
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentTarget
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.components.MESSAGE_ATTACHMENT_FILE_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #1329: in the thread, a file not fetched yet asks the view model for itself on a tap or long-press, and the
 * open or save its load delivers runs once through the thread's attachment actions.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenAttachmentLoadTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Stands in for the system's create-document picker: records each launch and never answers. */
    private class PickerRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Intent>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input)
        }
    }

    private val loads = Channel<AttachmentLoaded>(Channel.BUFFERED)
    private val registry = PickerRegistry()
    private val requested = mutableListOf<Pair<MessageAttachment, AttachmentAction>>()
    private val markdownOpened = mutableListOf<String>()

    private fun show(
        attachment: MessageAttachment,
        states: Map<String, AttachmentViewState> = emptyMap(),
    ) {
        val message =
            Message(
                id = "m1",
                sessionId = "s1",
                role = Role.Assistant,
                content = "Here you go",
                timestamp = Instant.parse("2026-10-01T12:00:00Z"),
                isStreaming = false,
                attachments = listOf(attachment),
            )
        val state =
            ThreadUiState(
                conversationId = "conversation",
                displayName = "files",
                isPromoted = true,
                hasMessages = true,
                items = listOf(ThreadItem.MessageItem(message)),
            )
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state = state,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        attachmentStates = states,
                        onRequestAttachment = { attachment, action -> requested += attachment to action },
                        attachmentLoads = loads.receiveAsFlow(),
                        onOpenMarkdownAttachment = { markdownOpened += it },
                    )
                }
            }
        }
    }

    private fun deliver(load: AttachmentLoaded) {
        loads.trySend(load)
        composeTestRule.waitForIdle()
    }

    @Test
    fun aFileNotFetchedYet_asksTheThreadForItself_onTapAndLongPress() {
        val pdf = MessageAttachment(ID, "report.pdf", "application/pdf")
        show(pdf)

        val row = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)
        row.performClick()
        row.performTouchInput { longClick() }

        assertEquals(listOf(pdf to AttachmentAction.OPEN, pdf to AttachmentAction.SAVE), requested)
        assertEquals(emptyList<Intent>(), registry.launches)
    }

    @Test
    fun aDeliveredOpen_runsOnce_withTheSourceItCarries_beforeTheStatesHoldIt() {
        show(MessageAttachment(ID, "Plan.md"))

        // The states still have no entry for the file: the open uses the loaded source, so it is not missed.
        deliver(AttachmentLoaded(AttachmentTarget(ID, "Plan.md", "text/markdown"), KEPT, AttachmentAction.OPEN))

        assertEquals(listOf(ID), markdownOpened)
        assertEquals(emptyList<Intent>(), registry.launches)
    }

    @Test
    fun aDeliveredSave_opensTheSavePickerOnce_withTheFilesNameAndType() {
        show(MessageAttachment(ID, "report.pdf", "application/pdf"))

        deliver(AttachmentLoaded(AttachmentTarget(ID, "report.pdf", "application/pdf"), KEPT, AttachmentAction.SAVE))

        val launch = registry.launches.single()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.action)
        assertEquals("application/pdf", launch.type)
        assertEquals("report.pdf", launch.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(emptyList<String>(), markdownOpened)
    }

    @Test
    fun aFailedLoad_showsRetry_andTheRowNeitherOpensNorSaves() {
        show(MessageAttachment(ID, "Plan.md", "text/markdown"), mapOf(ID to AttachmentViewState.Failed))

        composeTestRule.onNodeWithText("Retry").assertExists()
        val row = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)
        row.assertHasNoClickAction()
        row.performTouchInput { longClick() }

        assertEquals(emptyList<Pair<MessageAttachment, AttachmentAction>>(), requested)
        assertEquals(emptyList<String>(), markdownOpened)
        assertEquals(emptyList<Intent>(), registry.launches)
    }

    private companion object {
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val KEPT = AttachmentSource.Kept(File("/kept/report"))
    }
}
