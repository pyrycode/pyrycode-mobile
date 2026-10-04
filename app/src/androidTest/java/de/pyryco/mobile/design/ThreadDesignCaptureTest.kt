package de.pyryco.mobile.design

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.AttachmentContent
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.LiveRefusalEvent
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.di.ConversationViewing
import de.pyryco.mobile.e2e.ActivityIntentStub
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.PickedAttachment
import de.pyryco.mobile.ui.conversations.thread.ThreadHistoryTail
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * Thread, composer and thread status states of the assembled app at the Figma frames' viewports
 * (design-1220/thread). Each state is reached through [DesignInputs] or, for thread rows the fake graph
 * cannot emit (banner, refusal, attachment messages, idle and clear delimiters) and the linked-note reader,
 * through this class's own thread override. Every frame state waits strictly for its marker, so a state
 * that never renders fails the run instead of being captured under the frame's node.
 */
@RunWith(AndroidJUnit4::class)
class ThreadDesignCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    private val inputs get() = design.inputs
    private val extraItems = MutableStateFlow<List<ThreadItem>>(emptyList())
    private val refusals = MutableSharedFlow<LiveRefusalEvent>(replay = 1)
    private val turnPhase = MutableStateFlow(LiveSessionEvent.TurnState.Phase.Idle)
    private val usageLimit = MutableStateFlow<UsageLimitReading?>(null)
    private val hostAvailable = MutableStateFlow(true)

    /** While set, the override's `requestHistory` answers with this gate instead of the fake's page. */
    @Volatile private var historyGate: CompletableDeferred<HistoryPage>? = null

    /** While set, the override's `setSessionSettings` awaits this gate instead of the fake's write. */
    @Volatile private var settingsGate: CompletableDeferred<Unit>? = null
    private var readerNote = NOTE
    private val images = mutableListOf<Uri>()
    private var photo: File? = null

    /** Clears what outlives one test in this process: staged files, the draft, and the fake's per-channel readings. */
    @After fun clearStaged() {
        inputs.thread.value?.let { vm ->
            vm.pendingAttachments.value.forEach { vm.removeAttachment(it.key) }
            vm.onDraftChange("")
        }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        images.forEach { resolver.delete(it, null, null) }
        photo?.delete()
        fake().setModelMenu(CONVERSATION, null)
        fake().setSessionSettingsReading(CONVERSATION, null)
        fake().setSlashCommandMenu(CONVERSATION, null)
    }

    /** Hardware-only backdrop proof: explicit rows under both bars, then the same state with a real IME. */
    @Test fun translucentChromeAt412By892() {
        openThread()
        stageAttachments()
        extraItems.value = (1..12).map { n -> message("chrome-$n", Role.Assistant, chromeText(n), n + 10) }
        thinking()
        await(chromeText(12))
        chromeList().performScrollToIndex(4)
        chromeList().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 48f) }
        assertRowsUnderChrome()
        design.capture("chrome-1646", "reference", "16:8")
        design.capture("chrome-1646", "rows-behind-composer", "620:1577")
        design.capture("chrome-1646", "rows-behind-top-bar", "696:4677")

        stageNone()
        keyboard()
        chromeList().performScrollToIndex(4)
        chromeList().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 48f) }
        assertRowsUnderChrome()
        design.capture("chrome-1646", "keyboard", "675:6160")
        val actionsTop = rule.onNodeWithText("Actions").getUnclippedBoundsInRoot().top
        openActions()
        assertTrue(
            "footer menu remains above its keyboard-lifted anchor",
            rule.onNodeWithText("Knowledge capture").getUnclippedBoundsInRoot().bottom < actionsTop,
        )
        design.capture("chrome-1646", "keyboard-actions", "675:6160")
        // Back can be consumed by the IME instead of the footer overlay. Close that overlay explicitly
        // before editing: suggestions intentionally stay hidden while a footer menu is open.
        rule.onNodeWithContentDescription("Close options").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Background tasks", substring = true).fetchSemanticsNodes().isEmpty()
        }
        fake().setSlashCommandMenu(
            CONVERSATION,
            SlashCommandMenu(listOf(SlashCommandMenuRow("clear", "", "Start a new session", emptyList(), null)), 0),
        )
        val composer = rule.onNode(hasSetTextAction())
        design.openKeyboard(composer)
        composer.performTextReplacement("/cl")
        await("Start a new session")
        assertTrue(
            "suggestion remains above the keyboard-lifted input",
            rule.onNodeWithText("Start a new session").getUnclippedBoundsInRoot().bottom < composer.getUnclippedBoundsInRoot().top,
        )
        design.capture("chrome-1646", "keyboard-suggestions", "675:6160")
        design.closeKeyboard()
    }

    private fun chromeList() = rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("thread-message-region")))

    private fun assertRowsUnderChrome() {
        rule.waitForIdle()
        val header = rule.onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot
        val rows = rule.onAllNodes(hasTestTag("message-bubble"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("a message is drawn beneath the header", rows.any { it.top < header.bottom && it.bottom > header.top })
        assertTrue("a message is drawn beneath the composer", rows.any { it.top < composer.bottom && it.bottom > composer.top })
    }

    private fun chromeText(n: Int) =
        "Backdrop row $n. " + (1..5).joinToString(" ") { "Lorem ipsum dolor sit amet, consectetur adipiscing elit." }

    @Test fun threadStatusFramesAt412By892() {
        openThread()
        stageAttachments()
        inputs.contextUsage.value = CONTEXT
        extraItems.value = listOf(imageMessage(), refusal(), pdfMessage())
        thinking()
        await("Show details")
        awaitImageLoaded()
        design.capture(FOLDER, "thread", "16:8")

        extraItems.value = emptyList()
        await("Read")
        design.capture(FOLDER, "tool-row", "674:5853")

        extraItems.value = listOf(pdfMessage())
        inputs.connectionState.value = ConnectionState.Connecting
        await("Connecting…")
        design.capture(FOLDER, "connecting", "627:1740")

        inputs.connectionState.value = ConnectionState.Reconnecting(12)
        await("Reconnecting in 12s")
        design.capture(FOLDER, "reconnecting", "627:4657")

        inputs.connectionState.value = ConnectionState.Offline
        await("Offline · Retry")
        design.capture(FOLDER, "offline", "627:4910")

        inputs.connectionState.value = ConnectionState.Connected
        inputs.pairingRejected.value = true
        usageLimit.value = UsageLimitReading("allowed_warning", "seven_day", 0, 0.94, null)
        inputs.backgroundTaskCount.value = 2
        thinking()
        await("2 tasks running")
        await("usage", substring = true)
        await("Pairing error", substring = true)
        design.capture(FOLDER, "task-count-pill", "568:3139")
    }

    @Test fun threadNoticeFramesAt412By892() {
        openThread()
        stageAttachments()
        inputs.contextUsage.value = CONTEXT
        extraItems.value = listOf(pdfMessage(), ThreadItem.Banner(BannerLevel.Warning, "A hook blocked this request.", false, at(2)))
        thinking()
        await("A hook blocked this request.", substring = true)
        design.capture(FOLDER, "session-notice", "627:5466")

        // #1494: the menu names both refusal models, so the row reads their menu labels as the frames draw them.
        fake().setModelMenu(CONVERSATION, ModelMenu(MODELS.map { (name, id) -> menuRow(name, id) }, 0))
        val refusal = refusal()
        extraItems.value = listOf(pdfMessage(), refusal)
        await("Refused on Opus, continued on Sonnet")
        design.capture(FOLDER, "notification-text", "620:1577")

        fake().setSessionSettingsReading(CONVERSATION, settings("claude-sonnet-5"))
        runBlocking { refusals.emit(LiveRefusalEvent.Refused(refusal, "session")) }
        await("Switch back to Opus")
        design.capture(FOLDER, "refusal-switch-back", "646:4707")
    }

    /** #1540: the Thread notification states `620:1570`, `646:4694` and `646:4700`, in the notice frames' fixture. */
    @Test fun refusalStateFramesAt412By892() {
        openThread()
        stageAttachments()
        inputs.contextUsage.value = CONTEXT
        fake().setModelMenu(CONVERSATION, ModelMenu(MODELS.map { (name, id) -> menuRow(name, id) }, 0))
        // The component's explanation; the row adds the "Claude: " attribution itself.
        val refusal =
            refusal().copy(
                banner = "This request was declined on Opus, so it was retried on Sonnet for the rest of this session.",
            )
        extraItems.value = listOf(pdfMessage(), refusal)
        await("Refused on Opus, continued on Sonnet")
        rule.onNodeWithText("Show details").performClick()
        await("Hide details")
        design.capture(FOLDER, "notification-expanded", "620:1570")
        rule.onNodeWithText("Hide details").performClick()
        await("Show details")

        fake().setSessionSettingsReading(CONVERSATION, settings("claude-sonnet-5"))
        runBlocking { refusals.emit(LiveRefusalEvent.Refused(refusal, "session")) }
        await("Switch back to Opus")
        val write = CompletableDeferred<Unit>()
        settingsGate = write
        rule.onNodeWithText("Switch back to Opus").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Switch back to Opus") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        design.capture(FOLDER, "refusal-switch-back-pending", "646:4694")

        // A failed write also shows the run-configuration error pill; the compared row capture waits it out.
        write.completeExceptionally(IllegalStateException("design: model write fails"))
        await("Could not change the model — try again.")
        await("Couldn't update the run configuration. Try again.")
        design.capture(FOLDER, "refusal-switch-back-failed-snackbar", "646:4700")
        expireErrorPill("Couldn't update")
        await("Could not change the model — try again.")
        design.capture(FOLDER, "refusal-switch-back-failed", "646:4700")
        settingsGate = null
    }

    @Test fun backgroundTaskPanelAt412By892() {
        openThread()
        inputs.backgroundTasks.value = populated()
        inputs.backgroundTaskCount.value = 2
        openPanel()
        await("npm run build")
        design.capture(FOLDER, "tasks-populated", "568:877")
        closePanel()

        inputs.backgroundTasks.value = capped()
        inputs.backgroundTaskCount.value = 11
        openPanel()
        await("python3 scripts/replay_capture.py")
        design.capture(FOLDER, "tasks-capped", "568:932")
        closePanel()

        inputs.backgroundTasks.value = BackgroundTaskRoster(emptyList(), 0)
        inputs.backgroundTaskCount.value = 0
        openPanel()
        await("No background tasks")
        design.capture(FOLDER, "tasks-empty", "568:981")
        closePanel()

        inputs.backgroundTasks.value = null
        openPanel()
        await("No background-task report yet")
        design.capture(FOLDER, "tasks-never-reported", "568:997")
    }

    @Test fun runConfigurationAndReaderAt412By892() {
        openThread()
        openRunConfiguration()
        design.capture(FOLDER, "run-configuration", "600:1694")
        Espresso.pressBack()
        rule.waitForIdle()

        checkNotNull(inputs.thread.value).onOpenMarkdownLink("docs/Builder Pipeline - Plan.md")
        await("Builder Pipeline Plan")
        // Reference coordinates are measured at the capture's density 1, below the real status bar.
        val restingBar = rule.onNodeWithTag("markdown-reader-top-bar").getUnclippedBoundsInRoot()
        val restingTitle = rule.onNodeWithText("Builder Pipeline - Plan.md").getUnclippedBoundsInRoot()
        val restingHeading = rule.onNodeWithText("Builder Pipeline Plan").getUnclippedBoundsInRoot()
        assertEquals(69f, (restingBar.bottom - restingBar.top).value, 0.5f)
        assertEquals(24f, (restingTitle.top - restingBar.top).value, 0.5f)
        assertEquals(97f, (restingHeading.top - restingBar.top).value, 0.5f)
        assertEquals(28f, (restingHeading.top - restingBar.bottom).value, 0.5f)
        assertEquals(20f, restingHeading.left.value, 0.5f)
        design.capture("reader-chrome-1647", "reference", "553:2574")
        Espresso.pressBack()
        rule.waitForIdle()
        readerNote = NOTE + "\n\n" + (1..40).joinToString("\n\n") { "Reader scroll paragraph $it" }
        checkNotNull(inputs.thread.value).onOpenMarkdownLink("docs/Builder Pipeline - Plan.md")
        await("Builder Pipeline Plan")
        val body = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 72f * design.view.resources.displayMetrics.density) }
        val heading = rule.onNodeWithText("Builder Pipeline Plan").fetchSemanticsNode().boundsInRoot
        val header = rule.onNodeWithTag("markdown-reader-top-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("reader heading is drawn underneath bar", heading.top < header.bottom && heading.bottom > header.top)
        design.capture("reader-chrome-1647", "scrolled-under-bar", "731:6010")
    }

    /** Real pixels and real IME insets for the revised footer; no daemon or live Claude needed. */
    @Test fun footerAt412By892() = captureFooter("reference")

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun footerAt320By700() = captureFooter("compact")

    private fun captureFooter(name: String) {
        openThread()
        inputs.contextUsage.value = CONTEXT
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription("Context usage warning, 84%").fetchSemanticsNodes().isNotEmpty()
        }
        assertFooterAboveKeyboard(name, keyboardVisible = false)
        design.capture("footer-1659", name, "533:1957")
        keyboard()
        assertFooterAboveKeyboard(name, keyboardVisible = true)
        design.capture("footer-1659", "$name-keyboard", "533:1957")
        design.closeKeyboard()
        // A pointer on the tune's visible pixels reaches only the existing sheet.
        rule.onNodeWithTag("footer_status_icon", useUnmergedTree = true).performTouchInput { click(center) }
        await("Run configuration")
        Espresso.pressBack()
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val picker =
            ActivityIntentStub().apply {
                answer(Intent.ACTION_OPEN_DOCUMENT) { Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }
            }
        instrumentation.addMonitor(picker)
        try {
            rule.onNodeWithTag("footer_attach_icon", useUnmergedTree = true).performTouchInput { click(center) }
            rule.waitUntil(5_000) { picker.answered.size == 1 }
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.answered.single().action)
            rule.onNodeWithText("Run configuration").assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(picker)
        }
    }

    private fun assertFooterAboveKeyboard(
        name: String,
        keyboardVisible: Boolean,
    ) {
        rule.waitForIdle()
        val insets = design.insets()
        assertEquals(keyboardVisible, insets.isVisible(WindowInsetsCompat.Type.ime()))
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (keyboardVisible) assertTrue("keyboard has a positive inset", ime > 0)
        val attach = rule.onNodeWithTag("footer_attach_visual", useUnmergedTree = true)
        val tune = rule.onNodeWithTag("footer_status_visual", useUnmergedTree = true)
        attach.assertIsDisplayed()
        tune.assertIsDisplayed()
        val a = attach.getUnclippedBoundsInRoot()
        val b = tune.getUnclippedBoundsInRoot()
        val actions = rule.onNodeWithText("Actions", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val context = rule.onNodeWithTag("thread_footer_context_usage", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(24f, a.right.value - a.left.value, 0.5f)
        assertEquals(16f, a.bottom.value - a.top.value, 0.5f)
        assertEquals(12f, b.left.value - a.right.value, 0.5f)
        assertEquals("icons align with first Actions row", actions.bottom.value, b.bottom.value, 0.5f)
        assertTrue("Actions stays separate from icons", actions.right < a.left)
        assertEquals("circle width", 15f, context.right.value - context.left.value, 0.5f)
        assertEquals("circle height", 15f, context.bottom.value - context.top.value, 0.5f)
        assertEquals("circle before Actions with visual gap", 16f, actions.left.value - context.right.value, 0.5f)
        val actionTarget = rule.onNode(hasText("Actions") and hasClickAction()).getUnclippedBoundsInRoot()
        val slotTop = actionTarget.top.value + (actionTarget.bottom.value - actionTarget.top.value - 12f - 16f) / 2f
        assertEquals("Context slot centred in left group", slotTop, context.top.value, 0.5f)
        val density = design.view.resources.displayMetrics.density
        rule
            .onNodeWithTag("thread_footer_context_usage", useUnmergedTree = true)
            .assertContentDescriptionEquals("Context usage warning, 84%")
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
            // Hardware drawing can trail semantics; a warning description alone cannot fence a snapshot.
            rule.waitUntil(5_000) {
                val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    var yellowPixels = 0
                    for (y in (context.top.value * density).toInt() until (context.bottom.value * density).toInt()) {
                        for (x in (context.left.value * density).toInt() until (context.right.value * density).toInt()) {
                            val pixel = bitmap.getPixel(x, y)
                            if (Color.red(pixel) > Color.blue(pixel) + 40 && Color.green(pixel) > Color.blue(pixel) + 40) yellowPixels++
                        }
                    }
                    yellowPixels >= 5
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val screen = design.view.resources.displayMetrics.heightPixels
        assertTrue("icons stay above IME", b.bottom.value * density < screen - ime)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "design-1220/footer-1659",
            ).apply { mkdirs() }
        File(output, "$name-geometry-${if (keyboardVisible) "ime" else "rest"}.txt").writeText(
            "attach=$a tune=$b actions=$actions context=$context imeVisible=$keyboardVisible imePx=$ime\n" +
                "footerCropPx=20,${(
                    minOf(
                        actions.top.value,
                        b.top.value,
                    ) - 4
                ).toInt()},${design.view.resources.displayMetrics.widthPixels - 20}," +
                "${maxOf(b.bottom.value, context.bottom.value).toInt()}\n",
        )
    }

    @Test fun menusAndKeyboardAt412By892() {
        openThread()
        inputs.contextUsage.value = CONTEXT
        extraItems.value = delimiters()
        await("Idle session ended", substring = true)
        rule.onNodeWithText("Vivamus sagittis lacus vel augue.").performScrollTo()
        rule.waitForIdle()
        design.capture(FOLDER, "session-delimiter", "675:3682")
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        noWorkspaceAction()
        design.capture(FOLDER, "overflow-menu", "675:5883")
        Espresso.pressBack()
        rule.waitForIdle()

        extraItems.value = emptyList()
        await("Read")
        openActions()
        noWorkspaceAction()
        design.capture(FOLDER, "actions-menu", "675:5938")
        Espresso.pressBack()
        rule.waitForIdle()

        keyboard()
        design.capture(FOLDER, "keyboard", "675:6160")
        design.closeKeyboard()
    }

    /** The frame's compact keyboard state plus every overlay, sheet and row that could clip at 320x700 and 1.5x. */
    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun compactAt320By700() {
        openThread()
        inputs.contextUsage.value = CONTEXT
        inputs.backgroundTaskCount.value = 2
        thinking()
        await("2 tasks running")
        design.capture(FOLDER, "compact-thread", "16:8")

        inputs.connectionState.value = ConnectionState.Offline
        await("Offline · Retry")
        design.capture(FOLDER, "compact-offline", "627:4910")
        inputs.pairingRejected.value = true
        await("Pairing error", substring = true)
        design.capture(FOLDER, "compact-offline-overlays", "627:4910")
        inputs.pairingRejected.value = false
        inputs.connectionState.value = ConnectionState.Connected

        stageAttachments()
        usageLimit.value = UsageLimitReading("allowed_warning", "seven_day", 0, 0.94, null)
        val refusal = refusal()
        extraItems.value = listOf(refusal)
        fake().setSessionSettingsReading(CONVERSATION, settings("claude-sonnet-5"))
        runBlocking { refusals.emit(LiveRefusalEvent.Refused(refusal, "session")) }
        await("Switch back to", substring = true)
        rule.onNodeWithText("Switch back to", substring = true).performScrollTo()
        await("usage", substring = true)
        design.capture(FOLDER, "compact-notices", "646:4707")
        stageNone()
        extraItems.value = emptyList()
        usageLimit.value = null

        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        noWorkspaceAction()
        design.capture(FOLDER, "compact-overflow-menu", "675:5883")
        Espresso.pressBack()
        rule.waitForIdle()
        openActions()
        noWorkspaceAction()
        design.capture(FOLDER, "compact-actions-menu", "675:5938")
        Espresso.pressBack()
        rule.waitForIdle()

        inputs.backgroundTasks.value = populated()
        openPanel()
        await("npm run build")
        design.capture(FOLDER, "compact-tasks", "568:877")
        closePanel()

        openRunConfiguration()
        rule.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        design.capture(FOLDER, "compact-run-configuration", "600:1694")
        Espresso.pressBack()
        rule.waitForIdle()

        // Last: the focused composer's cursor handle is its own popup root, which would confuse openMenu.
        inputs.backgroundTasks.value = null
        keyboard()
        design.capture(FOLDER, "compact-keyboard", "676:3981")
        design.closeKeyboard()
    }

    /** #1529: the unrecognized-row, turn-outcome, failure-notice and slash type-ahead frames of `685:3991`. */
    @Test fun rowAndNoticeFramesAt412By892() {
        openThread()
        inputs.contextUsage.value = CONTEXT
        extraItems.value = unrecognized()
        await("Unrecognized message", substring = true)
        rule.onNodeWithText("server_tool_use", substring = true).performClick()
        await("Payload truncated by the daemon.")
        design.capture(FOLDER, "unrecognized-message", "685:4112")

        // Today the outcome is the band's arm; the frame moves it to a top-overlay pill.
        extraItems.value = listOf(ThreadItem.StoppedTurn("design-turn", "prompt_too_long", "", at(10)))
        runBlocking {
            inputs.liveSessionEvents.emit(
                LiveSessionEvent.TurnEnd(CONVERSATION, "design-turn", "end_turn", isError = true, terminalReason = "prompt_too_long"),
            )
        }
        await("Stopped: context too long, compact or reset")
        await("Context too long", substring = true)
        design.capture(FOLDER, "turn-outcome", "685:3992")

        // A turn starting clears the outcome; Idle again leaves the band at the snowflake alone.
        runBlocking {
            inputs.liveSessionEvents.emit(LiveSessionEvent.TurnState(CONVERSATION, LiveSessionEvent.TurnState.Phase.Thinking))
            inputs.liveSessionEvents.emit(LiveSessionEvent.TurnState(CONVERSATION, LiveSessionEvent.TurnState.Phase.Idle))
        }
        extraItems.value = emptyList()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Context too long", substring = true).fetchSemanticsNodes().isEmpty() }
        // #1747: the archive failure overlays the thread below the measured header.
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        rule.onNodeWithText("Archive").performClick()
        await("Couldn't archive this conversation. Try again.")
        val header = rule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        val failure = rule.onNodeWithTag("transient_error_notice").getUnclippedBoundsInRoot()
        assertEquals(24f, (failure.bottom - failure.top).value, 0.5f)
        assertEquals(28f, (failure.top - header.bottom).value, 0.5f)
        assertEquals(20f, (header.right - failure.right).value, 0.5f)
        design.capture(FOLDER, "failure-notice", "685:4337")
        expireErrorPill("Couldn't archive")

        // Last: the focused composer's cursor handle is its own popup root.
        fake().setSlashCommandMenu(
            CONVERSATION,
            SlashCommandMenu(
                SLASH_COMMANDS.map { (name, hint, text) ->
                    SlashCommandMenuRow(name, hint, text, emptyList(), null)
                },
                0,
            ),
        )
        val composer = rule.onNode(hasSetTextAction())
        design.openKeyboard(composer)
        composer.performTextReplacement("/co")
        await("Open the config panel")
        await("Show the total cost and duration of the current session")
        design.capture(FOLDER, "slash-type-ahead", "685:4232")
        design.closeKeyboard()
    }

    /** #1529: the oldest-end row's four states, `689:4281`, `689:4330`, `689:4379` and `689:4427`. */
    @Test fun historyTailFramesAt412By892() {
        // Held before the open, whose newest-page ask is the walk's first page.
        val loading = CompletableDeferred<HistoryPage>()
        historyGate = loading
        openThread()
        inputs.contextUsage.value = CONTEXT
        awaitHistoryTail(ThreadHistoryTail.Loading, "Loading earlier messages")
        design.capture(FOLDER, "history-loading", "689:4281")

        loading.completeExceptionally(RelayErrorException("history.unavailable", true, ""))
        awaitHistoryTail(ThreadHistoryTail.Retry, "Try again")
        design.capture(FOLDER, "history-retry", "689:4330")

        val retry = CompletableDeferred<HistoryPage>()
        historyGate = retry
        checkNotNull(inputs.thread.value).onRetryOlderHistory()
        awaitHistoryTail(ThreadHistoryTail.Loading, "Loading earlier messages")
        retry.completeExceptionally(RelayErrorException("history.not_found", false, ""))
        awaitHistoryTail(ThreadHistoryTail.DeadEnd, "Earlier messages are unavailable")
        design.capture(FOLDER, "history-dead-end", "689:4379")

        hostAvailable.value = false
        awaitHistoryTail(ThreadHistoryTail.Offline, "Older messages require a connection.")
        design.capture(FOLDER, "history-offline", "689:4427")
    }

    /** #1529: the composer strip while its send uploads, `689:4475`; the first upload stops at 4 of 10 chunks. */
    @Test fun uploadingFrameAt412By892() {
        openThread()
        inputs.contextUsage.value = CONTEXT
        stageAttachments()
        thinking()
        checkNotNull(inputs.thread.value).sendMessage("My message")
        val uploading = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Uploading… 40%")
        rule.waitUntil(5_000) { rule.onAllNodes(uploading).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        design.capture(FOLDER, "uploading-attachments", "689:4475")
    }

    /** Run configuration with a four-model menu and Sonnet selected; no "Default" option in any spelling. */
    private fun openRunConfiguration() {
        fake().setModelMenu(CONVERSATION, ModelMenu(MODELS.map { (name, id) -> menuRow(name, id) }, 0))
        fake().setSessionSettingsReading(CONVERSATION, settings("sonnet", effort = "high", permission = "default"))
        rule.onNodeWithContentDescription("Expand status details").performClick()
        await("Sonnet")
        await("Manual approval")
        await("Auto approval")
        // The footer rows sit outside the message region, whose seeded reply says "default to the current cwd".
        val outsideMessages = !hasAnyAncestor(hasTestTag("thread-message-region"))
        rule.onAllNodes(hasText("Default", substring = true, ignoreCase = true) and outsideMessages).assertCountEquals(0)
    }

    private fun keyboard() {
        val composer = rule.onNode(hasSetTextAction())
        design.openKeyboard(composer)
        composer.performTextReplacement("My message")
        rule.waitForIdle()
    }

    /** No clickable workspace row; the seeded demo reply itself mentions a workspace picker in plain text. */
    private fun noWorkspaceAction() {
        rule.onAllNodes(hasText("workspace", substring = true, ignoreCase = true) and hasClickAction()).assertCountEquals(0)
    }

    /** The footer's Actions menu draws in the screen's own window, not a popup, so wait for its last row. */
    private fun openActions() {
        rule.onNodeWithText("Actions").performTouchInput { click() }
        await("Knowledge capture")
    }

    private fun openThread() {
        design.paired = true
        install()
        design.launch()
        rule.onNodeWithText("Pyrycode Mobile").performScrollTo().performClick()
        rule.waitUntil(5_000) { inputs.thread.value != null }
        // The draft store outlives one test in this process; every capture starts from the frames' text.
        checkNotNull(inputs.thread.value).onDraftChange("My message")
        rule.waitForIdle()
    }

    /** The frames' composer strip: image, image, PDF, image. Images go through MediaStore so thumbnails load. */
    private fun stageAttachments() {
        val picked =
            listOf(
                PickedAttachment(image("design-stone-1.png"), "stone-1.png", "image/png", 4_096),
                PickedAttachment(image("design-stone-2.png"), "stone-2.png", "image/png", 4_096),
                PickedAttachment("content://de.pyryco.design.files/brief.pdf", "brief.pdf", "application/pdf", 4_096),
                PickedAttachment(image("design-stone-3.png"), "stone-3.png", "image/png", 4_096),
            )
        checkNotNull(inputs.thread.value).addPickedAttachments(picked)
        rule.waitUntil(5_000) { checkNotNull(inputs.thread.value).pendingAttachments.value.size == picked.size }
        rule.waitForIdle()
    }

    private fun stageNone() {
        inputs.thread.value?.let { vm -> vm.pendingAttachments.value.forEach { vm.removeAttachment(it.key) } }
        rule.waitForIdle()
    }

    private fun image(name: String): String {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        images += uri
        checkNotNull(resolver.openOutputStream(uri)).use { stone(96, 96).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri.toString()
    }

    /** The kept file the override's [ConversationRepository.retrieveAttachment] serves for the image bubble. */
    private fun photoFile(): File {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "design-photo")
        file.outputStream().use { stone(320, 320).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun stone(
        width: Int,
        height: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(Color.rgb(226, 226, 222))
                val paint = Paint().apply { color = Color.rgb(140, 136, 120) }
                drawOval(width * 0.29f, height * 0.1f, width * 0.71f, height * 0.94f, paint)
            }
        }

    private fun thinking() {
        // The band reads the repository's held phase, so set it there as well as on the live event stream.
        turnPhase.value = LiveSessionEvent.TurnState.Phase.Thinking
        runBlocking { inputs.liveSessionEvents.emit(LiveSessionEvent.TurnState(CONVERSATION, LiveSessionEvent.TurnState.Phase.Thinking)) }
        await("Thinking", substring = true)
    }

    /** Waits for the oldest-end row to read [tail], scrolls the reverse list to it and waits for its [text]. */
    private fun awaitHistoryTail(
        tail: ThreadHistoryTail,
        text: String,
    ) {
        rule.waitUntil(5_000) { checkNotNull(inputs.thread.value).state.value.historyTail == tail }
        rule.onNode(hasScrollToKeyAction() and hasAnyAncestor(hasTestTag("thread-message-region"))).performScrollToKey("history-tail")
        await(text)
    }

    /** The bubble's photo is fetched by the view model, then decoded off the main clock, so wait for both. */
    private fun awaitImageLoaded() {
        rule.waitUntil(10_000) { checkNotNull(inputs.thread.value).attachmentStates.value["design-photo"] is AttachmentViewState.Ready }
        val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
        rule.waitUntil(10_000) { rule.onAllNodes(spinner).fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
    }

    /** Opens and closes through the header X, which stays when #1496 removes the panel's Close button. */
    private fun openPanel() {
        rule.waitForIdle()
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        rule.onNodeWithText("Background tasks").performTouchInput { click() }
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun closePanel() {
        rule.onNodeWithContentDescription("Close").performTouchInput { click() }
        rule.waitForIdle()
    }

    /** Fails the test unless [text] shows within five seconds. */
    private fun await(
        text: String,
        substring: Boolean = false,
    ) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** #1747: advance the Short timeout without adding a dismiss action to an inert error pill. */
    private fun expireErrorPill(text: String) {
        rule.mainClock.advanceTimeBy(4_100)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    /** #1747: reachable production reader failure, with real bars and unchanged body reservations. */
    @Test fun readerErrorFrameAt412By892() {
        openThread()
        checkNotNull(inputs.thread.value).onOpenMarkdownLink("docs/Builder Pipeline - Plan.md")
        await("Builder Pipeline Plan")
        val headingBefore = rule.onNodeWithText("Builder Pipeline Plan").getUnclippedBoundsInRoot()
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        // The demo's production reread is unsupported, so Refresh deterministically fails.
        rule.onNodeWithText("Refresh").performClick()
        await("Couldn't open file")
        val bar = rule.onNodeWithTag("markdown-reader-top-bar").getUnclippedBoundsInRoot()
        val pill = rule.onNodeWithTag("transient_error_notice").getUnclippedBoundsInRoot()
        assertEquals(24f, (pill.bottom - pill.top).value, 0.5f)
        assertEquals(28f, (pill.top - bar.bottom).value, 0.5f)
        assertEquals(20f, (bar.right - pill.right).value, 0.5f)
        assertEquals(headingBefore, rule.onNodeWithText("Builder Pipeline Plan").getUnclippedBoundsInRoot())
        design.capture(FOLDER, "reader-error", "696:5101")
        expireErrorPill("Couldn't open file")
        rule.onNodeWithText("Builder Pipeline Plan").assertIsDisplayed()
    }

    /**
     * #1664: dismisses the snackbar showing [text] through the dismiss action Material3 gives each snackbar, then
     * waits for it to leave. Waiting out its timer flaked: the 4 s delay runs on the rule's virtual clock, which
     * `waitUntil` advances one frame per poll, so on a busy emulator the ~255 polls outlast the budget.
     */
    private fun dismissSnackbar(text: String) {
        rule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyDescendant(hasText(text, substring = true)))
            .performSemanticsAction(SemanticsActions.Dismiss)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun fake() = GlobalContext.get().get<FakeConversationRepository>()

    /**
     * Redefines the thread view model over [DesignInputs]' flows, as `DesignInputs` does, with a repository
     * that also appends [extraItems] to the thread, serves [refusals], [turnPhase] and [usageLimit], and reads
     * one markdown note.
     */
    private fun install() {
        loadKoinModules(
            module {
                viewModel {
                    val fake = get<FakeConversationRepository>()
                    val repository =
                        object : ConversationRepository by fake {
                            override fun observeMessages(conversationId: String) =
                                combine(fake.observeMessages(conversationId), extraItems) { items, extra -> items + extra }

                            override fun observeLiveRefusalEvents(conversationId: String) = refusals

                            override fun observeTurnPhase(conversationId: String) = turnPhase

                            override fun observeUsageLimit(conversationId: String) = usageLimit

                            override fun observeAttachmentOffers(conversationId: String) = inputs.attachmentOffers

                            override fun observeSessionFacts(conversationId: String) = inputs.sessionFacts

                            override fun observeContextUsage(conversationId: String) = inputs.contextUsage

                            override suspend fun retrieveAttachment(
                                conversationId: String,
                                attachmentId: String,
                            ) = AttachmentRetrievalResult.Retrieved(photo ?: photoFile().also { photo = it }, "stone.png", "image/png")

                            override suspend fun requestHistory(
                                conversationId: String,
                                cursor: String,
                                limit: Int,
                            ) = historyGate?.await() ?: fake.requestHistory(conversationId, cursor, limit)

                            override suspend fun setSessionSettings(
                                sessionId: String,
                                model: String?,
                                effort: String?,
                                yolo: Boolean?,
                                permissionMode: String?,
                            ) = settingsGate?.await() ?: fake.setSessionSettings(sessionId, model, effort, yolo, permissionMode)

                            // No test archives except to show the failure notice.
                            override suspend fun archive(conversationId: String): Unit =
                                throw IllegalStateException("design: archive fails")

                            // No test uploads except to hold the strip at 40 %.
                            override suspend fun uploadAttachment(
                                conversationId: String,
                                bytes: ByteArray,
                                filename: String,
                                mimeType: String,
                                onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
                            ): AttachmentUploadResult {
                                onProgress(4, 10)
                                awaitCancellation()
                            }

                            override suspend fun readWorkspaceFile(
                                conversationId: String,
                                path: String,
                            ) = AttachmentFetchResult.Fetched(
                                AttachmentContent(listOf(readerNote.toByteArray())),
                                "Plan.md",
                                "text/markdown",
                            )
                        }
                    val connection =
                        object : ConnectionStateSource {
                            override fun observe() = inputs.connectionState

                            override suspend fun retry() = Unit
                        }
                    ThreadViewModel(
                        get<SavedStateHandle>(),
                        repository,
                        connection,
                        get(),
                        liveSessionEvents = inputs.liveSessionEvents,
                        hostModal = inputs.hostModal,
                        questionBatch = { inputs.questionBatch },
                        backgroundTasks = { inputs.backgroundTasks },
                        backgroundTaskCount = { inputs.backgroundTaskCount },
                        repositoryAvailable = hostAvailable,
                        pairingRejected = inputs.pairingRejected,
                        attachmentReader = get<AttachmentReader>(),
                    ).also {
                        inputs.thread.value = it
                        val handle = get<SavedStateHandle>()
                        it.addCloseable(
                            get<ConversationViewing>().view(
                                handle.get<String>("serverId").orEmpty(),
                                handle.get<String>("conversationId").orEmpty(),
                            ),
                        )
                    }
                }
            },
        )
    }

    private fun message(
        id: String,
        role: Role,
        content: String,
        second: Int,
        attachment: MessageAttachment? = null,
    ) = ThreadItem.MessageItem(
        Message(
            id = id,
            sessionId = "seed-session-pyrycode-mobile",
            role = role,
            content = content,
            timestamp = at(second),
            isStreaming = false,
            attachments = listOfNotNull(attachment),
        ),
    )

    /** The frame's user bubble with a photo, served by the override's retrieveAttachment. */
    private fun imageMessage() =
        message(
            "design-image",
            Role.User,
            "Morbi efficitur scelerisque augue, in pretium erat tempor in.",
            1,
            MessageAttachment("design-photo", "stone.png", "image/png"),
        )

    private fun pdfMessage() =
        message(
            "design-attachment",
            Role.Assistant,
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
            4,
            MessageAttachment("design-pdf", "Filename of the Best file attachment.pdf", "application/pdf"),
        )

    /** Session delimiter and overflow menu frames: a clear boundary, then an idle-evict boundary. */
    private fun delimiters() =
        listOf(
            ThreadItem.SessionBoundary("seed-session-pyrycode-mobile", "design-session-2", BoundaryReason.Clear, at(5)),
            message("design-d1", Role.User, "Lorem ipsum dolor sit amet, consectetur adipiscing elit.", 6),
            message("design-d2", Role.Assistant, "Mauris at quam euismod.", 7),
            ThreadItem.SessionBoundary("design-session-2", "design-session-3", BoundaryReason.IdleEvict, at(8)),
            message("design-d3", Role.User, "Morbi efficitur scelerisque augue, in pretium erat tempor in.", 9),
            message("design-d4", Role.Assistant, "Vivamus sagittis lacus vel augue.", 9),
        )

    /** `685:4112`: a collapsed assistant-block row, then a truncated whole-message row the test expands. */
    private fun unrecognized() =
        listOf(
            ThreadItem.UnrecognizedMessage(
                "design-u1",
                UnrecognizedSite.AssistantBlock,
                "thinking_delta",
                "{\"type\":\"thinking_delta\"}",
                false,
                at(10),
            ),
            ThreadItem.UnrecognizedMessage(
                "design-u2",
                UnrecognizedSite.LineType,
                "server_tool_use",
                "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_01\",\"name\":\"web_search\",\"input\":{\"query\":\"how",
                true,
                at(11),
            ),
        )

    private fun refusal() =
        ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "This request was declined on Opus.", false, at(3))

    private fun settings(
        model: String,
        effort: String = "",
        permission: String = "",
    ) = SessionSettings(
        sessionId = "seed-session-pyrycode-mobile",
        model = model,
        effort = effort,
        effectiveEffort = if (effort.isEmpty()) EffectiveEffort.NotReported else EffectiveEffort.Applied(effort),
        permissionMode = permission,
        yolo = false,
        usedTokens = 0,
        windowTokens = 0,
    )

    private fun menuRow(
        name: String,
        resolved: String,
    ) = ModelMenuRow(resolved, name.lowercase(), name, listOf("low", "medium", "high", "max"), true, null)

    private fun task(
        id: String,
        type: String,
        description: String,
        update: String? = null,
        finish: String? = null,
        status: String = "",
        truncated: List<String>? = null,
        updateCut: List<String>? = null,
        progress: BackgroundTaskProgress? = null,
    ) = BackgroundTask(
        taskId = id,
        toolCallId = null,
        taskType = type,
        description = description,
        truncatedFields = truncated,
        latestUpdate = update?.let { BackgroundTaskUpdate(it, "running", "", updateCut) },
        finish = finish?.let { BackgroundTaskUpdate("", status, it, null) },
        isFinished = finish != null,
        progress = progress,
    )

    private fun populated() =
        BackgroundTaskRoster(
            listOf(
                task(
                    "t1",
                    "local_bash",
                    "go test ./internal/relay/... -run TestReconnect -count=20 -race",
                    update = """{"output_tail":"--- PASS: TestReconnect/drop_mid_frame (0.84s)"}""",
                    progress = BackgroundTaskProgress("Running go test with the race detector", "", "Bash", 18_000, 4, 161_000, null),
                ),
                task(
                    "t2",
                    "local_agent",
                    "Review the relay reconnect diff for data races",
                    progress = BackgroundTaskProgress("Reading internal/relay/conn.go", "", "Read", 42_000, 7, 65_000, null),
                ),
                task("t3", "local_bash", "npm run build", finish = "Build finished in 38s with no warnings.", status = "completed"),
                task(
                    "t4",
                    "local_bash",
                    "docker compose up relay",
                    finish = "Exited with code 1: port 8443 is already in use.",
                    status = "failed",
                ),
            ),
            0,
        )

    private fun capped() =
        BackgroundTaskRoster(
            listOf(
                task(
                    "c1",
                    "local_bash",
                    "for f in \$(git ls-files \"internal/**/*.go\"); do go vet \"\$f\" && staticcheck -checks all \"\$f\" >> /tmp/lint.txt; done; sort -u /tmp/lint.txt | head -n 400 > /tmp/li",
                    truncated = listOf("description"),
                ),
                task(
                    "c2",
                    "local_bash",
                    "python3 scripts/replay_capture.py",
                    update = """{"output_tail":"replayed 214 frames, 3 roste""",
                    updateCut = listOf("patch"),
                ),
                task("c3", "local_agent", "Summarise the open tickets that mention the relay", update = ""),
            ) + (4..8).map { task("c$it", "local_bash", "sleep $it") },
            3,
        )

    private companion object {
        const val FOLDER = "thread"
        const val CONVERSATION = "seed-channel-pyrycode-mobile"
        val CONTEXT = ContextUsage(168_000, 200_000, 84, null)
        val MODELS =
            listOf(
                "Fable" to "claude-fable-5-1",
                "Opus" to "claude-opus-5-5",
                "Sonnet" to "claude-sonnet-5",
                "Haiku" to "claude-haiku-4-5",
            )

        /** `685:4232`'s four `/co` matches, plus two rows the prefix filters out. */
        val SLASH_COMMANDS =
            listOf(
                Triple("clear", "", "Clear conversation history and free up context"),
                Triple("compact", "[instructions]", "Clear conversation history but keep a summary in context"),
                Triple("config", "", "Open the config panel"),
                Triple("context", "", "Visualize current context usage as a colored grid"),
                Triple("cost", "", "Show the total cost and duration of the current session"),
                Triple("help", "", "Show help and available commands"),
            )
        val NOTE =
            """
            # Builder Pipeline Plan

            The main board runs four roles since 2026-09-01. Each ticket moves from [refiner](refiner.md) to builder, then through review and the merge gate.

            ## Next steps

            - Pin all five agents repos to one dispatcher version
            - Measure token spend per role
            - Retire the old single-role runner

            ```
            pyry release --both
            pyry status
            ```

            > Tokens first, running time second.
            """.trimIndent()

        fun at(second: Int) = Instant.parse("2026-05-10T09:00:%02dZ".format(second))
    }
}
