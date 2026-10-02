package de.pyryco.mobile.design

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
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
import de.pyryco.mobile.data.repository.AttachmentContent
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.LiveRefusalEvent
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.di.ConversationViewing
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.PickedAttachment
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Thread, composer and thread status states of the assembled app at the Figma frames' viewports
 * (design-1220/thread). Each state is reached through [DesignInputs] or, for thread rows the fake graph
 * cannot emit (banner, refusal, attachment message) and the linked-note reader, through this class's own
 * thread override. A state that does not appear within its wait is still captured, so the index records
 * what the app showed instead of the run stopping.
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
    private val images = mutableListOf<Uri>()

    @After fun clearStaged() {
        inputs.thread.value?.let { vm -> vm.pendingAttachments.value.forEach { vm.removeAttachment(it.key) } }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        images.forEach { resolver.delete(it, null, null) }
    }

    @Test fun threadStatusFramesAt412By892() {
        openThread()
        stageAttachments()
        inputs.contextUsage.value = ContextUsage(168_000, 200_000, 84, null)
        thinking()
        design.capture(FOLDER, "thread", "16:8")

        inputs.connectionState.value = ConnectionState.Connecting
        soft { rule.onNodeWithText("Connecting…").fetchSemanticsNode() }
        design.capture(FOLDER, "connecting", "627:1740")

        inputs.connectionState.value = ConnectionState.Reconnecting(12)
        soft { rule.onNodeWithText("Reconnecting in 12s").fetchSemanticsNode() }
        design.capture(FOLDER, "reconnecting", "627:4657")

        inputs.connectionState.value = ConnectionState.Offline
        soft { rule.onNodeWithText("Offline · Retry").fetchSemanticsNode() }
        design.capture(FOLDER, "offline", "627:4910")

        inputs.connectionState.value = ConnectionState.Connected
        inputs.pairingRejected.value = true
        usageLimit.value = UsageLimitReading("allowed_warning", "seven_day", 0, 0.94, null)
        inputs.backgroundTaskCount.value = 2
        thinking()
        soft { rule.onNodeWithText("2 tasks running").fetchSemanticsNode() }
        design.capture(FOLDER, "task-count-pill", "568:3139")
    }

    @Test fun threadNoticeFramesAt412By892() {
        openThread()
        inputs.contextUsage.value = ContextUsage(168_000, 200_000, 84, null)
        extraItems.value = listOf(attachmentMessage(), ThreadItem.Banner(BannerLevel.Warning, "A hook blocked this request.", false, at(2)))
        thinking()
        design.capture(FOLDER, "session-notice", "627:5466")

        val refusal = refusal(fallback = "claude-sonnet-5")
        extraItems.value = listOf(attachmentMessage(), refusal)
        thinking()
        design.capture(FOLDER, "notification-text", "620:1577")

        fake().setSessionSettingsReading(CONVERSATION, settings("claude-sonnet-5"))
        runBlocking { refusals.emit(LiveRefusalEvent.Refused(refusal, "session")) }
        soft { rule.onNodeWithText("Switch back to", substring = true).fetchSemanticsNode() }
        design.capture(FOLDER, "refusal-switch-back", "646:4707")
    }

    @Test fun backgroundTaskPanelAt412By892() {
        openThread()
        inputs.backgroundTasks.value = populated()
        inputs.backgroundTaskCount.value = 2
        openPanel()
        design.capture(FOLDER, "tasks-populated", "568:877")
        closePanel()

        inputs.backgroundTasks.value = capped()
        inputs.backgroundTaskCount.value = 11
        openPanel()
        design.capture(FOLDER, "tasks-capped", "568:932")
        closePanel()

        inputs.backgroundTasks.value = BackgroundTaskRoster(emptyList(), 0)
        inputs.backgroundTaskCount.value = 0
        openPanel()
        design.capture(FOLDER, "tasks-empty", "568:981")
        closePanel()

        inputs.backgroundTasks.value = null
        openPanel()
        design.capture(FOLDER, "tasks-never-reported", "568:997")
    }

    @Test fun runConfigurationAndReaderAt412By892() {
        openThread()
        fake().setModelMenu(CONVERSATION, ModelMenu(MODELS.map { (name, id) -> menuRow(name, id) }, 0))
        fake().setSessionSettingsReading(CONVERSATION, settings("sonnet", effort = "high"))
        rule.onNodeWithContentDescription("Expand status details").performClick()
        soft { rule.onNodeWithText("Sonnet").fetchSemanticsNode() }
        rule.onAllNodesWithText("Default").assertCountEquals(0)
        design.capture(FOLDER, "run-configuration", "600:1694")
        Espresso.pressBack()
        rule.waitForIdle()

        checkNotNull(inputs.thread.value).onOpenMarkdownLink("docs/Builder Pipeline - Plan.md")
        soft { rule.onNodeWithText("Builder Pipeline Plan").fetchSemanticsNode() }
        design.capture(FOLDER, "markdown-reader", "553:2574")
    }

    @Test fun menusAndKeyboardAt412By892() {
        openThread()
        menusAndKeyboard("")
    }

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun compactAt320By700() {
        openThread()
        inputs.contextUsage.value = ContextUsage(168_000, 200_000, 84, null)
        inputs.backgroundTaskCount.value = 2
        thinking()
        design.capture(FOLDER, "compact-thread", "16:8")
        inputs.connectionState.value = ConnectionState.Offline
        inputs.pairingRejected.value = true
        design.capture(FOLDER, "compact-offline-overlays", "627:4910")
        inputs.connectionState.value = ConnectionState.Connected
        menusAndKeyboard("compact-")
    }

    /** Overflow menu, Actions menu and the composer with the keyboard up; no workspace action in either menu. */
    private fun menusAndKeyboard(prefix: String) {
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        noWorkspaceAction()
        design.capture(FOLDER, "${prefix}overflow-menu", "16:8")
        Espresso.pressBack()
        rule.waitForIdle()

        openActions()
        noWorkspaceAction()
        design.capture(FOLDER, "${prefix}actions-menu", "16:8")
        Espresso.pressBack()
        rule.waitForIdle()

        val composer = rule.onNode(hasSetTextAction())
        design.openKeyboard(composer)
        composer.performTextReplacement("My message")
        design.capture(FOLDER, "${prefix}keyboard", "16:8")
        design.closeKeyboard()
    }

    /** No clickable workspace row; the seeded demo reply itself mentions a workspace picker in plain text. */
    private fun noWorkspaceAction() {
        rule.onAllNodes(hasText("workspace", substring = true, ignoreCase = true) and hasClickAction()).assertCountEquals(0)
    }

    /** The footer's Actions menu draws in the screen's own window, not a popup, so wait for its last row. */
    private fun openActions() {
        rule.onNodeWithText("Actions").performTouchInput { click() }
        // The row reads "Background tasks (N)" while tasks run.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Background tasks", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
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
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(226, 226, 222))
            drawOval(28f, 10f, 68f, 90f, Paint().apply { color = Color.rgb(140, 136, 120) })
        }
        checkNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri.toString()
    }

    private fun thinking() {
        // The band reads the repository's held phase, so set it there as well as on the live event stream.
        turnPhase.value = LiveSessionEvent.TurnState.Phase.Thinking
        runBlocking { inputs.liveSessionEvents.emit(LiveSessionEvent.TurnState(CONVERSATION, LiveSessionEvent.TurnState.Phase.Thinking)) }
        soft { rule.onNodeWithText("Thinking", substring = true).fetchSemanticsNode() }
    }

    private fun openPanel() {
        rule.waitForIdle()
        openActions()
        rule.onNodeWithText("Background tasks", substring = true).performTouchInput { click() }
        soft { rule.onNodeWithText("Close").fetchSemanticsNode() }
        rule.waitForIdle()
    }

    private fun closePanel() {
        rule.onNodeWithText("Close").performTouchInput { click() }
        rule.waitForIdle()
    }

    /** Waits up to five seconds for [check] to pass, and carries on either way. */
    private fun soft(check: () -> Unit) {
        runCatching { rule.waitUntil(5_000) { runCatching(check).isSuccess } }
        rule.waitForIdle()
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

                            override suspend fun readWorkspaceFile(
                                conversationId: String,
                                path: String,
                            ) = AttachmentFetchResult.Fetched(AttachmentContent(listOf(NOTE.toByteArray())), "Plan.md", "text/markdown")
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

    private fun attachmentMessage() =
        ThreadItem.MessageItem(
            Message(
                id = "design-attachment",
                sessionId = "seed-session-pyrycode-mobile",
                role = Role.Assistant,
                content = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
                timestamp = at(1),
                isStreaming = false,
                attachments = listOf(MessageAttachment("design-pdf", "Filename of the Best file attachment.pdf", "application/pdf")),
            ),
        )

    private fun refusal(fallback: String?) =
        ThreadItem.ModelRefusal("claude-opus-5-5", fallback, "This request was declined on Opus.", false, at(3))

    private fun settings(
        model: String,
        effort: String = "",
    ) = SessionSettings(
        sessionId = "seed-session-pyrycode-mobile",
        model = model,
        effort = effort,
        effectiveEffort = if (effort.isEmpty()) EffectiveEffort.NotReported else EffectiveEffort.Applied(effort),
        permissionMode = "",
        yolo = false,
        usedTokens = 0,
        windowTokens = 0,
    )

    private fun menuRow(
        name: String,
        resolved: String,
    ) = ModelMenuRow(resolved, name.lowercase(), name, listOf("low", "medium", "high", "max"), false, null)

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
        val MODELS =
            listOf(
                "Fable" to "claude-fable-5-1",
                "Opus" to "claude-opus-5-5",
                "Sonnet" to "claude-sonnet-5",
                "Haiku" to "claude-haiku-4-5",
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

        fun at(second: Int) = Instant.parse("2026-05-10T09:00:0${second}Z")
    }
}
