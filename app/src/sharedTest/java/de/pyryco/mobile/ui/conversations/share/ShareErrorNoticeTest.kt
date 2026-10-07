package de.pyryco.mobile.ui.conversations.share

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.R
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.ui.assertDpEquals
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.AttachmentSendFailure
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.PickedAttachment
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.conversations.thread.text
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareErrorNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val drafts = GlobalContext.get().get<ComposerDraftStore>()
    private val target = HostConversationTarget("demo", "seed-channel-personal")
    private lateinit var intake: ShareIntakeViewModel
    private lateinit var nav: NavHostController

    private fun show(
        accessibility: AccessibilityManager? = null,
        capture: suspend (Uri, (AttachmentSendFailure) -> Unit) -> PickedAttachment? = { uri, _ -> file(uri) },
    ) {
        intake = ShareIntakeViewModel(drafts, capture, Dispatchers.Main.immediate)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    ShareErrorNoticeHost(intake) {
                        nav = rememberNavController()
                        PyryNavHost(Routes.CHANNEL_LIST, navController = nav, shareIntake = intake)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Personal")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
    }

    private fun accept(
        count: Int = 1,
        shortcut: String? = null,
    ) {
        compose.runOnIdle {
            intake.accept(SharePayload("shared text", List(count) { Uri.parse("content://foreign/$it") }, shortcut))
        }
        // Start and settle NavHost's 700ms fade before querying the active picker.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(750)
    }

    private fun assertPill(text: String) {
        compose.mainClock.advanceTimeByFrame()
        compose
            .onNodeWithTag("transient_error_notice")
            .assertIsDisplayed()
            .assertTextEquals(text)
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Dismiss))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(hasAnyAncestor(hasTestTag("thread_confirmation_snackbar")).not())
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("Dismiss notice").assertDoesNotExist()
    }

    private fun expire() {
        compose.mainClock.advanceTimeBy(4_100)
        compose.onNodeWithTag("transient_error_notice").assertDoesNotExist()
    }

    private fun choose() {
        compose.onNodeWithText("Personal").performClick()
        repeat(30) {
            if (nav.currentDestination?.route != Routes.CONVERSATION_THREAD) compose.mainClock.advanceTimeBy(32)
        }
        compose.runOnIdle { assertEquals(Routes.CONVERSATION_THREAD, nav.currentDestination?.route) }
        compose.mainClock.advanceTimeBy(750)
    }

    @Test fun captureFailureOnPicker_isInertPoliteAndExpiresWithoutMovingRows() {
        show(capture = { _, failure ->
            failure(AttachmentSendFailure.UNREADABLE)
            null
        })
        val row = compose.onNodeWithText("Personal").getUnclippedBoundsInRoot()
        accept()
        val copy = AttachmentSendFailure.UNREADABLE.text(context.resources)
        assertPill(copy)
        val picker = compose.onNodeWithTag("share-ready").getUnclippedBoundsInRoot()
        val pill = compose.onNodeWithTag("transient_error_notice").getUnclippedBoundsInRoot()
        assertDpEquals(28.dp, pill.top - picker.bottom)
        assertDpEquals(20.dp, compose.onNodeWithTag("channel-list").getUnclippedBoundsInRoot().right - pill.right)
        val shownRow = compose.onNodeWithText("Personal").getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(3_000)
        assertPill(copy)
        expire()
        assertEquals(shownRow, compose.onNodeWithText("Personal").getUnclippedBoundsInRoot())
        // The share header changes the list position; notice expiry never does.
        org.junit.Assert.assertTrue(row != shownRow)
    }

    @Test fun repeatedCaptureFailures_keepSeparateOccurrencesAndSizeFormatting() {
        show(capture = { _, failure ->
            failure(AttachmentSendFailure.TOO_LARGE)
            null
        })
        accept(2)
        val copy = AttachmentSendFailure.TOO_LARGE.text(context.resources)
        assertPill(copy)
        val first = compose.onNodeWithTag("transient_error_notice").fetchSemanticsNode().id
        compose.mainClock.advanceTimeBy(4_000)
        assertPill(copy)
        assertNotEquals(first, compose.onNodeWithTag("transient_error_notice").fetchSemanticsNode().id)
        compose.mainClock.advanceTimeBy(3_000)
        assertPill(copy)
        expire()
    }

    @Test fun intakeCountRefusal_preservesPluralCountOnPicker() {
        show()
        accept(MessageAttachmentIds.MAX + 2)
        assertPill(context.resources.getQuantityString(R.plurals.thread_attachments_too_many, 2, 2))
        compose.runOnIdle {
            assertEquals(
                MessageAttachmentIds.MAX,
                intake.state.value
                    ?.files
                    ?.size,
            )
        }
        expire()
    }

    @Test fun selectionCountRefusal_survivesPickerToThreadAndKeepsUnsentDraft() {
        repeat(
            MessageAttachmentIds.MAX,
        ) { drafts.addAttachment(target.serverId, target.conversationId, "old-$it", "old-$it", "text/plain", 1) }
        drafts.setDraft(target.serverId, target.conversationId, "existing")
        show()
        accept(2)
        choose()
        assertPill(context.resources.getQuantityString(R.plurals.thread_attachments_too_many, 2, 2))
        compose.runOnIdle {
            assertEquals("existing\nshared text", drafts.draftFor(target.serverId, target.conversationId))
            assertEquals(MessageAttachmentIds.MAX, drafts.attachmentsFor(target.serverId, target.conversationId).size)
        }
        val header = compose.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        assertDpEquals(28.dp, compose.onNodeWithTag("transient_error_notice").getUnclippedBoundsInRoot().top - header.bottom)
        expire()
    }

    @Test fun selectionSizeRefusal_survivesPickerToThreadWithoutAddingFile() {
        show(capture = { uri, _ -> file(uri, AttachmentUploadLimit.MAX_BYTES.toLong() + 1) })
        accept()
        choose()
        assertPill(context.resources.getQuantityString(R.plurals.thread_attachments_too_large, 1, 1))
        compose.runOnIdle { assertEquals(0, drafts.attachmentsFor(target.serverId, target.conversationId).size) }
        expire()
    }

    @Test fun directShareCaptureFailure_isVisibleOnTheSelectedThread() {
        val shortcuts = GlobalContext.get().get<SharingShortcuts>()
        runBlocking { shortcuts.opened(target) }
        show(capture = { _, failure ->
            failure(AttachmentSendFailure.UNREADABLE)
            null
        })
        accept(shortcut = RecentShareTargets(4).id(target))
        repeat(30) {
            if (nav.currentDestination?.route != Routes.CONVERSATION_THREAD) compose.mainClock.advanceTimeBy(32)
        }
        compose.runOnIdle { assertEquals(Routes.CONVERSATION_THREAD, nav.currentDestination?.route) }
        compose.mainClock.advanceTimeBy(750)
        compose.onNodeWithText("Share to…").assertDoesNotExist()
        assertPill(AttachmentSendFailure.UNREADABLE.text(context.resources))
        expire()
    }

    @Test fun accessibilityExtendsLifetimeWithMaterialShortPolicy() {
        val calls = mutableListOf<List<Any>>()
        val manager =
            object : AccessibilityManager {
                override fun calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis: Long,
                    containsIcons: Boolean,
                    containsText: Boolean,
                    containsControls: Boolean,
                ): Long {
                    calls += listOf(originalTimeoutMillis, containsIcons, containsText, containsControls)
                    return 12_000
                }
            }
        show(manager, capture = { _, failure ->
            failure(AttachmentSendFailure.UNREADABLE)
            null
        })
        accept()
        assertPill(AttachmentSendFailure.UNREADABLE.text(context.resources))
        assertEquals(listOf(listOf(4_000L, true, true, false)), calls)
        compose.mainClock.advanceTimeBy(4_100)
        assertPill(AttachmentSendFailure.UNREADABLE.text(context.resources))
        compose.mainClock.advanceTimeBy(8_100)
        compose.onNodeWithTag("transient_error_notice").assertDoesNotExist()
    }

    @Test fun directShareIntakeCountRefusal_isVisibleAfterAutomaticDestinationSelection() {
        runBlocking { GlobalContext.get().get<SharingShortcuts>().opened(target) }
        show()
        accept(MessageAttachmentIds.MAX + 1, RecentShareTargets(4).id(target))
        repeat(30) {
            if (nav.currentDestination?.route != Routes.CONVERSATION_THREAD) compose.mainClock.advanceTimeBy(32)
        }
        compose.mainClock.advanceTimeBy(750)
        compose.runOnIdle { assertEquals(Routes.CONVERSATION_THREAD, nav.currentDestination?.route) }
        assertPill(context.resources.getQuantityString(R.plurals.thread_attachments_too_many, 1, 1))
        expire()
    }

    @Test fun shareFailureFollowsPersistentNoticesWith12dpGapAndCannotTapRetry() {
        var retries = 0
        intake =
            ShareIntakeViewModel(drafts, { _, failure ->
                failure(AttachmentSendFailure.UNREADABLE)
                null
            }, Dispatchers.Main.immediate)
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                ShareErrorNoticeHost(intake) {
                    ThreadScreen(
                        state = ThreadUiState("thread", "Thread"),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Offline,
                        onRetry = { retries++ },
                        sessionError = "session.blocked",
                    )
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val persistent = context.getString(R.string.thread_session_blocked)
        val before = compose.onNodeWithContentDescription(persistent).getUnclippedBoundsInRoot()
        accept()
        assertPill(AttachmentSendFailure.UNREADABLE.text(context.resources))
        val pill = compose.onNodeWithTag("transient_error_notice").getUnclippedBoundsInRoot()
        assertDpEquals(12.dp, pill.top - before.bottom)
        compose.onNodeWithTag("transient_error_notice").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, retries) }
        expire()
        assertEquals(before, compose.onNodeWithContentDescription(persistent).getUnclippedBoundsInRoot())
    }

    @After fun releaseShareAndDrafts() {
        if (::intake.isInitialized) intake.cancel()
        drafts.clearHost(target.serverId)
    }

    private fun file(
        uri: Uri,
        size: Long = 1,
    ) = PickedAttachment(uri.toString(), "shared.pdf", "application/pdf", size)
}
