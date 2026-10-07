package de.pyryco.mobile.ui.conversations.thread

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.ModalUiState
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
import org.junit.Before
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
        val requestCodes = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            requestCodes += requestCode
            launches += contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input)
        }
    }

    private val loads = Channel<AttachmentLoaded>(Channel.BUFFERED)
    private val registry = PickerRegistry()
    private val requested = mutableListOf<Pair<MessageAttachment, AttachmentAction>>()
    private val markdownOpened = mutableListOf<String>()
    private val errors = Channel<Unit>(Channel.BUFFERED)
    private var modal by mutableStateOf<ModalUiState>(ModalUiState.Hidden)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearCachedProviderRoots() {
        val cache =
            FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    private fun show(
        attachment: MessageAttachment,
        states: Map<String, AttachmentViewState> = emptyMap(),
        noViewer: Boolean = false,
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
            val baseContext = LocalContext.current
            val actionContext =
                if (noViewer) {
                    object : ContextWrapper(baseContext) {
                        override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException("private platform text")
                    }
                } else {
                    baseContext
                }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides actionContext) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state = state,
                        modalState = modal,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        attachmentStates = states,
                        newSessionErrors = errors.receiveAsFlow(),
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

    private fun awaitNotice(notice: AttachmentNotice) {
        val text = context.getString(notice.message)
        composeTestRule.waitUntil(5_000) { composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithText(text).assert(hasTestTag("transient_error_notice")).assertHasNoClickAction()
    }

    @Test fun anInvalidOpenSource_usesTheOpenFailedPill() {
        show(MessageAttachment(ID, "secret-path.pdf"))
        deliver(
            AttachmentLoaded(
                AttachmentTarget(ID, "secret-path.pdf", "application/pdf"),
                AttachmentSource.Original("file:///secret-path.pdf"),
                AttachmentAction.OPEN,
            ),
        )
        awaitNotice(AttachmentNotice.OPEN_FAILED)
        composeTestRule.onNodeWithText("secret-path.pdf").assertIsDisplayed()
    }

    @Test fun noViewer_usesTheNoAppPill() {
        val file =
            File(context.noBackupFilesDir, "attachments/h/c/report").apply {
                parentFile?.mkdirs()
                writeText("text")
            }
        show(MessageAttachment(ID, "report.unknown"), noViewer = true)
        deliver(
            AttachmentLoaded(
                AttachmentTarget(ID, "report.unknown", "application/x-nothing-opens-this"),
                AttachmentSource.Kept(file),
                AttachmentAction.OPEN,
            ),
        )
        awaitNotice(AttachmentNotice.NO_APP)
    }

    @Test fun aFailedSave_usesTheSaveFailedPill() {
        show(MessageAttachment(ID, "report.pdf"), mapOf(ID to AttachmentViewState.Ready(KEPT, null, null)))
        deliver(AttachmentLoaded(AttachmentTarget(ID, "report.pdf", "application/pdf"), KEPT, AttachmentAction.SAVE))
        composeTestRule.runOnIdle {
            registry.dispatchResult(registry.requestCodes.single(), Uri.fromFile(File(context.cacheDir, "missing/report.pdf")))
        }
        awaitNotice(AttachmentNotice.SAVE_FAILED)
    }

    @Test fun savedUsesADefaultPill_andCoexistsWithAnErrorPill() {
        val source = File(context.cacheDir, "save-source").apply { writeText("saved bytes") }
        val kept = AttachmentSource.Kept(source)
        show(MessageAttachment(ID, "report.pdf"), mapOf(ID to AttachmentViewState.Ready(kept, null, null)))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle { errors.trySend(Unit) }
        composeTestRule.mainClock.advanceTimeBy(64)
        composeTestRule.onNodeWithTag("transient_error_notice").assertIsDisplayed()
        deliver(AttachmentLoaded(AttachmentTarget(ID, "report.pdf", "application/pdf"), kept, AttachmentAction.SAVE))
        val destination = File(context.cacheDir, "saved-report.pdf")
        composeTestRule.runOnIdle { registry.dispatchResult(registry.requestCodes.single(), Uri.fromFile(destination)) }
        val saved = context.getString(AttachmentNotice.SAVED.message)
        composeTestRule.waitUntil(5_000) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.onAllNodesWithText(saved).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule
            .onNodeWithText(saved)
            .assertIsDisplayed()
            .assert(hasTestTag("transient_confirmation_notice"))
            .assertHasNoClickAction()
        composeTestRule.onNodeWithTag("transient_error_notice").assertIsDisplayed()
        assertEquals("saved bytes", destination.readText())
    }

    @Test fun savedAndDismissals_shareArrivalOrder_acrossModalIdChanges() {
        val source = File(context.cacheDir, "mixed-save-source").apply { writeText("bytes") }
        val kept = AttachmentSource.Kept(source)
        show(MessageAttachment(ID, "report.pdf"), mapOf(ID to AttachmentViewState.Ready(kept, null, null)))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle { modal = ModalUiState.Dismissed("first", "allow", "remote") }
        composeTestRule.mainClock.advanceTimeBy(64)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        deliver(AttachmentLoaded(AttachmentTarget(ID, "report.pdf", "application/pdf"), kept, AttachmentAction.SAVE))
        val destination = File(context.cacheDir, "mixed-saved-report.pdf")
        composeTestRule.runOnIdle { registry.dispatchResult(registry.requestCodes.last(), Uri.fromFile(destination)) }
        composeTestRule.waitUntil(5_000) { destination.exists() }
        composeTestRule.mainClock.advanceTimeBy(64)
        composeTestRule.runOnIdle { modal = ModalUiState.Dismissed("second", "allow", "timeout") }
        composeTestRule.mainClock.advanceTimeBy(64)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Resolved on another device").assert(hasTestTag("transient_confirmation_notice"))
        composeTestRule.onNodeWithText("File saved").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(4_000)
        composeTestRule.onNodeWithText("File saved").assert(hasTestTag("transient_confirmation_notice"))
        composeTestRule.onNodeWithText("Request timed out").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(4_000)
        composeTestRule.onNodeWithText("Request timed out").assert(hasTestTag("transient_confirmation_notice"))
        composeTestRule.mainClock.advanceTimeBy(4_000)
        composeTestRule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
    }

    private companion object {
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val KEPT = AttachmentSource.Kept(File("/kept/report"))
    }
}
