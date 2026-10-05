package de.pyryco.mobile.e2e

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ATTACHMENT_CHUNK_BYTES
import de.pyryco.mobile.data.network.AssistantDeltaPayloadDto
import de.pyryco.mobile.data.network.AttachmentOfferedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskProgressPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskUpdatedPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HistoryEntryDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModalShownPayloadDto
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ToolResultPayloadDto
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.DebugBundleStatus
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.RelayConnectionBundle
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.e2e.E2eTestApplication.Companion.ARG_RELAY_URL
import de.pyryco.mobile.e2e.E2eTestApplication.Companion.ARG_SERVER_ID
import de.pyryco.mobile.e2e.E2eTestApplication.Companion.ARG_SERVER_STATIC_PUBLIC_KEY
import de.pyryco.mobile.grantNotificationPermission
import de.pyryco.mobile.notifications.ATTENTION_CHANNEL_ID
import de.pyryco.mobile.push.PushTokenSource
import de.pyryco.mobile.ui.components.CHANNEL_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.CHANNEL_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.components.EDIT_HOST_NAME_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.CHANNEL_INFO_AGENT_VERSION_TAG
import de.pyryco.mobile.ui.conversations.components.CHANNEL_INFO_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.CHANNEL_INFO_SESSION_COST_TAG
import de.pyryco.mobile.ui.conversations.components.MESSAGE_ATTACHMENT_FILE_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.RUNNING_MODEL_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.SESSION_BOUNDARY_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.treeHostChannelAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostChatAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostEditTestTag
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHAT_ROW_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.ATTACHMENT_STRIP_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.CONTEXT_USAGE_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.ComposerAction
import de.pyryco.mobile.ui.conversations.thread.EFFORT_PLACEHOLDER_LABEL
import de.pyryco.mobile.ui.conversations.thread.PERMISSION_SETTLE_WINDOW_MS
import de.pyryco.mobile.ui.conversations.thread.PING_PROMPT
import de.pyryco.mobile.ui.conversations.thread.PermissionModeOption
import de.pyryco.mobile.ui.conversations.thread.STATUS_READING_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.UNAVAILABLE_MODEL_LABEL
import de.pyryco.mobile.ui.conversations.thread.awaitDisplayedPingReply
import de.pyryco.mobile.ui.conversations.thread.awaitDisplayedSessionBoundary
import de.pyryco.mobile.ui.conversations.thread.completeSlashCommand
import de.pyryco.mobile.ui.conversations.thread.dropdownLabel
import de.pyryco.mobile.ui.conversations.thread.inert
import de.pyryco.mobile.ui.conversations.thread.pingReplyMatcher
import de.pyryco.mobile.ui.conversations.thread.slashCommandOptions
import de.pyryco.mobile.ui.conversations.thread.slashCommandTypeAheadRows
import de.pyryco.mobile.ui.onboarding.ScannerEvent
import de.pyryco.mobile.ui.onboarding.ScannerUiState
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import androidx.compose.ui.semantics.Role as SemanticsRole

/**
 * Happy-path end-to-end test for the mobile interactive event stream (#642 rung 3, ADR 025): the
 * **real** app on a headless emulator connects to a host `pyry` + relay, sends a constrained prompt to
 * real claude, and asserts the streamed assistant reply renders in the thread.
 *
 * This test does **not** stand alone — it is driven by `scripts/e2e-emulator.sh`, which boots the
 * relay + daemon, mints a device token with `pyry pair`, and passes the relay URL / token / server id
 * / server static public key as instrumentation arguments. [E2eInstrumentationRunner] sees those args
 * and swaps in [E2eTestApplication] (paired + relay-backed). Run it via the managed-device task:
 *
 * ```
 * bash scripts/e2e-emulator.sh
 * ```
 *
 * Semi-deterministic by nature (real claude): assertions use generous timeouts and case-insensitive
 * text matching. Ping matches the exact constrained reply in the message list. The "reply with
 * exactly: ping" framing is what keeps real claude's output predictable enough to assert against while
 * still exercising the whole real path.
 *
 * Most Compose selectors are text / content-description based; if those UI strings change, update the
 * constants below. The exceptions are the handles the list screen authors for the device suites: the
 * arrival marker [awaitChannelList] keys on (#736) and the tier tags the promote scenario reads (#731).
 * Those survive chrome changes the strings do not — which is why arrival and creation are three shared
 * helpers here rather than repeated in every scenario.
 */
@RunWith(AndroidJUnit4::class)
class InteractiveStreamE2ETest {
    init {
        grantNotificationPermission()
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    /**
     * #586: fails **any** scenario in this class during which the daemon reported a claude message kind
     * its parser could not map. Declaring it is the entire per-class cost — a ninth scenario added
     * tomorrow inherits the guard with no line to remember. Red does not mean broken; it means the
     * daemon's measured ignore-list needs re-taking. See [UnrecognizedRowSentinel].
     *
     * The sentinel can only fire when the resolved interactive runner is stream-json, which is where the
     * `unrecognized_message` emitter lives. On `LIVE=1` that comes from the operator's real
     * `~/.pyry/config.json` (`INTERACTIVE_RUNNER` is refused in preflight), and #614's
     * `interactive runner: <runner> (<reason>)` line prints it before every daemon spawn — a known,
     * visible condition, deliberately not engineered around.
     */
    @get:Rule
    val unrecognizedRowSentinel = UnrecognizedRowSentinel()

    // The thinking spinner's content-description (production UI string, no test tags). Copied from
    // DeterministicInteractiveStreamE2ETest (the rung-4 twin). Keep in sync with res/values/strings.xml:
    //   cd_thread_thinking = "Agent is thinking".
    private val thinkingDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking)

    // #950: the status area's running-tool label, from resources. The no-reading form is matched exactly; the
    // elapsed form by its text around the reading, since claude's heartbeat decides the seconds.
    //   cd_thread_tool_running = "Claude is running %1$s",
    //   cd_thread_tool_running_elapsed = "Claude is running %1$s, %2$s elapsed".
    private val runningToolLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_tool_running, TOOL_NAME)
    private val runningToolElapsedLabel: Regex =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_tool_running_elapsed, TOOL_NAME, ELAPSED_SLOT)
            .split(ELAPSED_SLOT)
            .joinToString(".+") { Regex.escape(it) }
            .toRegex()

    // The queued row's state description and its drop control (#849), production strings from resources:
    //   thread_queued_state_desc = "Waiting to send", cd_thread_queued_drop = "Drop this queued message".
    private val queuedStateDescription: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_queued_state_desc)
    private val queuedDropDescription: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_queued_drop)

    // #891: the footer's Status-sheet opener and the running-model row's unavailable note, from resources.
    private val statusExpandDescription: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_status_expand)
    private val runningModelUnavailable: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.status_sheet_running_model_unavailable)

    // #545: the footer's model and effort controls, found by their click labels, and the state description a
    // control carries while its write is outstanding, all from resources.
    private val changeModelLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_footer_change_model)
    private val changeEffortLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_footer_change_effort)
    private val footerPending: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_footer_pending)

    // #687: the footer's permission control, found by its click label, and the permission prompt's Cancel.
    private val changePermissionLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_footer_change_permission)
    private val modalCancel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.modal_cancel)

    // #1016: the composer's attach action and the save notices, from resources.
    private val attachFilesLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_attach_files)
    private val savedNotice: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_attachment_saved)
    private val saveFailedNotice: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_attachment_save_failed)

    // #1017: a failed attachment row's status and its retry control, from resources.
    private val attachmentFailedText: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_attachment_failed)
    private val attachmentRetryLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_attachment_retry)

    /** #1674: finished assistant prose copies only the long-pressed word via Android's real menu. */
    @Test
    fun interactiveTurn_finishedReply_systemCopyCopiesSelectedWord() {
        awaitChannelList()
        awaitConnected()
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val before = hostConversationIds(serverId)
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        val conversationId = newHostConversationId(serverId, before)
        sendFromPhone(SELECTION_PROMPT)
        composeTestRule.assertFinishedReplySystemCopy(hostRepository(serverId), conversationId, REPLY_TIMEOUT_MS)
    }

    @Test
    fun interactiveTurn_pingPrompt_streamsPingReplyIntoThread() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation — createDiscussion
        //    round-trips to the daemon, so tapping before the session is Open would fail the send.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread. The send button (only on
        //    the thread) is the marker that we have arrived.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Type the constrained prompt into the only editable field, then send.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Match the displayed reply itself; queued prompt removal cannot offset this signal.
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
    }

    /**
     * #891: after one real turn, the Status sheet's running-model row shows what claude announced on its
     * `system/init` line (`model_announced`). Asserts only that the row carries a non-empty value and not
     * the unavailable note — the model name depends on the operator's claude and is never hard-coded.
     *
     * #1346: first, Channel info's Session section shows a reported version (not "Not reported") and a cost
     * row, which exists only once the turn's `turn_end` carried a positive `cost_usd_total`. Neither value
     * is hard-coded; the sheet is closed with its own Close button before the Status sheet opens.
     */
    @Test
    fun interactiveTurn_pingPrompt_statusSheetShowsRunningModel() {
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).onFirst().performClick()
        val versionShown = {
            composeTestRule
                .onAllNodes(hasTestTag(CHANNEL_INFO_AGENT_VERSION_TAG))
                .fetchSemanticsNodes()
                .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            versionShown().any { it.isNotBlank() && it != SESSION_VALUE_NOT_REPORTED } &&
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_INFO_SESSION_COST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        val cost =
            composeTestRule
                .onNode(hasTestTag(CHANNEL_INFO_SESSION_COST_TAG))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        assertTrue("cost row reads \"$cost\"", Regex("\\$\\d+\\.\\d{2} est\\.").matches(cost))
        composeTestRule.onNode(hasContentDescription(CD_CLOSE_SHEET) and hasClickAction()).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_INFO_AGENT_VERSION_TAG)).fetchSemanticsNodes().isEmpty()
        }

        composeTestRule.onNode(hasContentDescription(statusExpandDescription)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(RUNNING_MODEL_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }

        val shown =
            composeTestRule
                .onNode(hasTestTag(RUNNING_MODEL_TEST_TAG))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        assertTrue("running-model row is empty", shown.isNotBlank())
        assertNotEquals(runningModelUnavailable, shown)
    }

    /**
     * #1344: after one real turn (the daemon queries the conversation's live child), opening Channel info asks
     * for the MCP reading; ticking "Show built-in" lists the daemon's own `pyry_approve` server. Asserts only the
     * built-in name — the operator's other MCP servers vary and are never hard-coded.
     */
    @Test
    fun interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn() {
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).onFirst().performClick()

        // The Show built-in row appears only once a report has arrived for this conversation.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithContentDescription(MCP_SHOW_BUILT_IN).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription(MCP_SHOW_BUILT_IN).performScrollTo().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(MCP_BUILT_IN_APPROVE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(MCP_BUILT_IN_APPROVE).performScrollTo().assertIsDisplayed()
    }

    /**
     * #946: after one real turn, the composer footer's circle announces the computed percentage
     * (`context_usage`, published after every completed turn and answered on the screen's own ask). Asserts
     * an available accessible percentage (including warning and high readings) — the figure depends on the operator's claude and is never hard-coded.
     */
    @Test
    fun interactiveTurn_pingPrompt_footerShowsContextUsage() {
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        val reported = CONTEXT_REPORTED
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CONTEXT_USAGE_TEST_TAG)).fetchSemanticsNodes().any { node ->
                reported.matches(node.config[SemanticsProperties.ContentDescription].joinToString(""))
            }
        }
    }

    /**
     * #1410: opening an existing chat after a fresh connection shows its context reading before any new turn, from
     * the thread's own `request_context_usage`. The [SecondClientPeer] runs the chat's only turn while the phone's
     * link is cut, so the post-turn push never reaches the phone: the chat had no ring events before the cut, and a
     * reconnect replays only the conversation the phone's cursor names. Back on the list, the host's held reading for
     * the chat is still empty, which is what makes the footer's later percentage the answer to the open's ask. The
     * creating open also asks and is refused (no reading yet); the peer's whole turn separates that refusal from the
     * reopen, longer than the daemon's short per-conversation collapse window.
     *
     * **One real-claude turn** (the peer's ping) plus one on-demand reading.
     */
    @Test
    fun interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The phone creates and names a chat, then leaves it without sending anything.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val before = hostConversationIds(serverId)
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId = newHostConversationId(serverId, before)
            val chatName = CONTEXT_ASK_CHAT_NAME_PREFIX + System.currentTimeMillis()
            renameOpenThread(chatName)
            leaveThread()

            // 2. With the phone's link cut, the peer's turn runs to its end and the daemon publishes its reading.
            setHostLink(serverId, up = false)
            runBlocking {
                peer.sendMessage(conversationId, PING_PROMPT, THREAD_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "context_usage", THREAD_TIMEOUT_MS)
            }

            // 3. A fresh connection: the phone holds no reading for the chat, so only the open's ask can fill it.
            setHostLink(serverId, up = true)
            awaitChannelList()
            assertNull(
                "the reconnect alone delivered the chat's reading; the open's ask would prove nothing",
                runBlocking { hostRepository(serverId).observeContextUsage(conversationId).first() },
            )

            // 4. Open the chat and send nothing: the open's ask fills the reading. Waiting on the reading itself,
            // not only the footer, because the footer also renders a percentage from session_settings alone.
            openChatRow(chatName)
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    hostRepository(serverId).observeContextUsage(conversationId).filterNotNull().first()
                }
            }
            val reported = CONTEXT_REPORTED
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag(CONTEXT_USAGE_TEST_TAG)).fetchSemanticsNodes().any { node ->
                    reported.matches(node.config[SemanticsProperties.ContentDescription].joinToString(""))
                }
            }
        } finally {
            peer.close()
        }
    }

    /**
     * Negative control (manual). Un-ignore once to confirm the positive assertion is real: it sends the
     * same ping prompt but waits for a word claude is never asked to say. On a correct build this wait
     * **times out and the test FAILS** — proving the substring matcher is not matching everything and
     * the positive test is genuinely observing claude's output. Left `@Ignore` so it does not burn a
     * claude turn on every suite run.
     */
    @Ignore("manual negative control — un-ignore to confirm the positive assertion can fail")
    @Test
    fun negativeControl_wordClaudeNeverSays_isNeverDisplayed() {
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        // "pong" is in neither the prompt nor the expected reply, so this must time out (→ fail).
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("pong", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Tool-use twin of the ping happy path (#481, Layer 3): a constrained prompt makes **real claude run
     * a shell tool**, and we assert the tool step renders in the thread. The render path (`tool_use` →
     * running row, `tool_result` → done — #387 correlation / #388 tool-row status UI) is already shipped
     * and reviewed; this exercises it end to end against real claude.
     *
     * The load-bearing signal is the durable Done content description on the tool row. The described
     * Figma variant replaces the verbatim tool name in the header, so text matching that name would
     * miss a correctly rendered row. We do not race the transient running spinner.
     *
     * The new discussion has no resolved tool step before the prompt. After the turn, the Done glyph
     * is a stable signal of the rendered row regardless of which header variant claude supplies.
     */
    @Test
    fun interactiveTurn_toolPrompt_rendersToolStepInThread() {
        val doneDescription =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_tool_done)
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithContentDescription(doneDescription).assertCountEquals(0)

        // 4. Type the tool-forcing prompt into the only editable field, then send.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(TOOL_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Wait for the resolved tool-row status, then confirm it is visible.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithContentDescription(doneDescription).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithContentDescription(doneDescription)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * Manual sentinel for an unexpected Edit step in the read-only tool prompt. This text matcher is
     * separate from the positive test's resolved-status matcher because a described header omits its
     * tool name. On a correct build the wait times out. It remains ignored to avoid another live turn.
     */
    @Ignore("manual sentinel — un-ignore to check for an unexpected Edit step")
    @Test
    fun negativeControl_toolClaudeNeverUses_isNeverDisplayed() {
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction()).performTextInput(TOOL_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        // The prompt asks only for a read-only shell command, never a file edit, so "Edit" must never
        // render a tool row → this wait must time out (→ fail).
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(TOOL_NEVER_USED, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The status band keeps a reading for the whole running turn (#1311, rung 3). [TOOL_THEN_TEXT_PROMPT]
     * makes real claude run a read-only `echo` and then answer in text, so one turn walks thinking, a tool
     * call and the `responding` text that used to leave the band dark. From the tap on Send the band is
     * sampled inside `waitUntil` until the turn has been seen busy and then idle. "Busy" is the stop control,
     * which shows exactly while `isBusy` holds and the composer is empty. A reading is a label: the turn's own
     * (thinking, working, running tool, stalled) or any other arm that can pre-empt it mid-turn: compaction,
     * api-retry, Reset session, the connection arm during a reconnect, or waiting for answers. A sample counts
     * as dark only when busy holds both before and after its reading checks, so `turn_state{idle}` landing
     * between the reads at the turn's falling edge is not mistaken for an empty band. Any dark sample is
     * recorded, and the list must be empty. The status snowflake is not a reading: since #1312 the band draws
     * it in every state, idle included, so only a label proves the band is not dark.
     *
     * Always-on: it asserts an absence over the whole turn rather than catching a transient label, so no
     * timing decides the outcome. Non-vacuity: at least one busy sample must have been taken.
     *
     * **One real-claude turn.**
     */
    @Test
    fun interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stopControl = hasContentDescription(context.getString(R.string.cd_thread_interrupt))
        val compactingReading = context.getString(R.string.cd_thread_compacting)
        // Every api-retry description, counted or not, opens with this agent-named phrase.
        val retryPrefix = context.getString(R.string.cd_thread_api_retry_unknown).substringBefore(",")
        val exactReadings =
            setOf(
                compactingReading,
                context.getString(R.string.thread_resetting_wrapping_up),
                context.getString(R.string.thread_resetting_restarting_written),
                context.getString(R.string.thread_resetting_restarting_skipped),
                context.getString(R.string.thread_resetting_restarting),
                context.getString(R.string.thread_connection_connecting),
                context.getString(R.string.question_waiting_for_answers),
            )
        // Unformatted, so the prefix stops before the countdown's placeholder.
        val reconnectingPrefix = context.resources.getString(R.string.thread_connection_reconnecting).substringBefore("%")
        // The turn's own readings. Unformatted, so each prefix stops before its first placeholder; the token
        // reading opens with the plain thinking label.
        val turnReadings = setOf(context.getString(R.string.thread_working_label), context.getString(R.string.thread_stalled_label))
        val turnPrefixes =
            listOf(
                context.getString(R.string.thread_thinking_label),
                context.resources.getString(R.string.thread_tool_running_label).substringBefore("%"),
            )
        // Scoped to the band's reading box, so a streamed reply line that opens with "Running …" cannot pass
        // for a reading and hide a dark band.
        val turnReading =
            SemanticsMatcher("a thinking, working, running-tool or stall reading") { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
                    .any { text -> text in turnReadings || turnPrefixes.any { text.startsWith(it) } }
            } and hasAnyAncestor(hasTestTag(STATUS_READING_TEST_TAG))
        val otherReading =
            SemanticsMatcher("a compaction, api-retry, reset, connection or waiting reading") { node ->
                val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                val texts =
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                (descriptions + texts).any {
                    it in exactReadings || it.startsWith(retryPrefix) || it.startsWith(reconnectingPrefix)
                }
            }
        awaitChannelList()
        awaitConnected()
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput(TOOL_THEN_TEXT_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        var busySamples = 0
        var seenBusy = false
        val darkSamples = mutableListOf<Int>()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            val busy = composeTestRule.onAllNodes(stopControl).fetchSemanticsNodes().isNotEmpty()
            if (busy) {
                seenBusy = true
                busySamples++
                val turn = composeTestRule.onAllNodes(turnReading, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                val other = composeTestRule.onAllNodes(otherReading).fetchSemanticsNodes().isNotEmpty()
                val stillBusy = composeTestRule.onAllNodes(stopControl).fetchSemanticsNodes().isNotEmpty()
                if (!turn && !other && stillBusy) darkSamples += busySamples
            }
            seenBusy && !busy
        }

        assertTrue("the turn was never seen busy, so nothing was sampled", busySamples > 0)
        assertTrue("busy samples with no status reading: $darkSamples of $busySamples", darkSamples.isEmpty())
    }

    /**
     * Thinking-spinner twin of the ping happy path (#482, Layer 3): a **pure-reasoning** prompt makes
     * **real claude think for a beat**, and we assert the thinking spinner is displayed while the turn is
     * active. The render path (`turn_state(thinking)` →
     * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isThinking] → `ThinkingIndicator`, #406) is
     * already shipped and reviewed; this exercises it end to end against real claude — the only layer that
     * catches real claude changing the screen text the screen-sourced spinner is matched from. Real-claude
     * twin of #454 (rung 4 scripted) and #432 (Layer 1a component).
     *
     * **`@Ignore`d by default — a documented manual case (AC #3), not a flaky always-on test.** This is the
     * flakiest scenario on the ladder. Unlike #481's tool row — whose resolved status is a
     * **durable** terminal signal that survives turn-end — the spinner leaves **no durable artifact**: the
     * instant real claude emits its first token the daemon flips `turn_state` to `responding`, `isThinking`
     * goes false, and `ThinkingIndicator` early-returns, so the node disappears with no trace. Rung 3 has no
     * scripted backend to hold the turn open and cannot imperatively pause real claude (the levers #454's
     * two-drop fence and #432's `pushTurnState` give the twins), so the spinner's presence mid-turn cannot
     * be made deterministic here. The operator un-ignores to attempt the run; if a pure-reasoning prompt
     * yields a `turn_state(thinking)` window long enough to observe over the relay, the operator may
     * promote it to always-on — otherwise it stays a documented manual case. See
     * `docs/e2e-interactive-stream.md`.
     *
     * **Presence-only, mid-turn — no absence-after-end assertion.** Asserting the spinner *cleared* would
     * need the scripted two-drop fence to make the transition deterministic; at rung 3 a second real-claude
     * turn would itself re-enter `thinking` and re-show the spinner, so an absence check would race a 2nd
     * turn — exactly the "never on timing" failure the ladder forbids (AC #2). The single load-bearing
     * assertion catches the spinner content-description while the turn is in its thinking phase, tolerantly,
     * never on counts or timing.
     *
     * **No negative control (deliberate divergence from the ping / tool-use siblings).** Those assert on
     * claude *output* substrings and each ship an `@Ignore`d control to prove the matcher is selective. Here
     * the asserted token is the production content-description [thinkingDescription] (`cd_thread_thinking`),
     * which never appears in any user bubble, auto-derived title, or claude output — there is nothing for a
     * negative control to disprove. This `@Ignore`d positive test *is* the manual case.
     */
    @Ignore("manual — transient spinner; un-ignore to attempt promotion, see KDoc")
    @Test
    fun interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Type the pure-reasoning prompt into the only editable field, then send. The prompt forbids
        //    tool use (so the #428 permission modal never interposes) and asks claude to reason a beat
        //    before answering with only a short token, widening the transient thinking window.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(THINK_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Catch the thinking spinner mid-turn, then confirm it is on screen. Presence only — the spinner
        //    is transient and leaves no durable artifact once the turn moves on, so we never assert it
        //    cleared (that would race a 2nd turn). The matched content-description is emitted only by
        //    ThinkingIndicator, so a non-empty match can only be the live spinner.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodes(hasContentDescription(thinkingDescription))
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * The status area names the tool claude is running (#950, rung 3; #897's label). The rung-4 twins are
     * the `tool` and `tool-progress` scenarios. The call is held the #849 way: [HELD_TOOL_PROMPT]'s `python3`
     * command is never auto-allowed, claude's `tool_use` arrives, and the call waits on a permission prompt
     * that only the [SecondClientPeer] paired with `--allow-remote-permissions` can answer. Since #1483 the
     * band reads "Waiting for permission" in place of the label while the prompt is open, so the hold
     * asserts that. Once the peer allows the command, it sleeps 10 s, well short of claude's ~30 s first
     * heartbeat. While it runs, the label reads `Running Bash…` with no elapsed reading. After the turn
     * ends, the label is gone.
     *
     * A prior peer binds the shared token first, so this also guards #1698's identity custody when
     * selected alone, independently of the live suite's scenario order (#1683).
     *
     * **One real-claude turn**, of at least 10 s.
     */
    @Test
    fun interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool() {
        val peer = runningToolPeer()
        val waitingReading =
            hasText(string(R.string.thread_status_waiting_for_permission)) and hasAnyAncestor(hasTestTag(STATUS_READING_TEST_TAG))
        try {
            runningToolPeer().use { prior ->
                peerStep(prior, "open prior running-tool peer") { prior.open(CONNECT_TIMEOUT_MS) }
            }
            val (conversationId, modalId) = holdToolOnPermission(peer, HELD_TOOL_PROMPT)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(waitingReading).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(waitingReading).assertIsDisplayed()
            composeTestRule.onNode(hasContentDescription(runningToolLabel)).assertDoesNotExist()

            peerStep(peer, "allow held tool once and await permission dismissal") { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(runningToolLabel)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(hasContentDescription(runningToolLabel)).assertIsDisplayed()

            peerStep(peer, "await permission-held tool's turn_end") { peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS) }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(runningToolLabel)).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            peer.close()
        }
    }

    /**
     * The label adds claude's elapsed reading once a `tool_progress` heartbeat arrives (#950, rung 3). The
     * call is held on a permission prompt as in [interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool];
     * once the peer allows it, [ELAPSED_TOOL_PROMPT]'s command sleeps 45 s in the foreground, and the label
     * should read `Running Bash… 30s` from claude's first heartbeat until the call returns. The rung-4
     * `tool-progress` scenario proves the same label from a scripted heartbeat on every scripted run.
     *
     * **`@Ignore`d: it cannot be made durable here.** The first heartbeat is claude's, not ours: it arrived
     * at 30 s on the one committed capture (claude 2.1.259), and nothing in the harness can make it come
     * sooner or promise it comes at all. That leaves about 15 s to observe, and only if claude runs the
     * command in the foreground as asked rather than backgrounding it; a bare `sleep` of 25 s or more is
     * refused outright, which is why the command sleeps inside `python3`. A longer sleep would widen the
     * window but spend more of every run. The operator un-ignores it to check the label against the
     * current claude.
     *
     * **One real-claude turn**, of at least 45 s.
     */
    @Ignore("manual — claude's first tool_progress heartbeat comes at ~30 s and cannot be held; see KDoc")
    @Test
    fun interactiveTurn_longRunningTool_statusAreaShowsElapsed() {
        val peer = runningToolPeer()
        val elapsedLabel =
            SemanticsMatcher("running-tool label with an elapsed reading") { node ->
                node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .any { runningToolElapsedLabel.matches(it) }
            }
        try {
            val (conversationId, modalId) = holdToolOnPermission(peer, ELAPSED_TOOL_PROMPT)
            runBlocking { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(elapsedLabel).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(elapsedLabel).assertIsDisplayed()

            runBlocking { peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS) }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(elapsedLabel).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            peer.close()
        }
    }

    /**
     * Create-workspace-folder twin of the ping happy path (#566, Layer 3): drive the real
     * create-a-workspace-folder flow end to end against real claude, exercising the already-shipped
     * #564 create wire and #565 recents wire. Create a chat from its Chats section, open the thread's
     * folder picker, create a new folder and move the chat there. A constrained ping proves the folder is
     * usable as a live-session workspace; re-opening the thread picker checks it appears in Recent.
     *
     * The WorkspaceChip unmounts after messages arrive, so the second picker opens from thread overflow.
     * Sequencing that check after the ping means the folder has been used by an active session.
     *
     * Semi-deterministic by nature (real claude): every assertion is **tolerant** — generous timeouts,
     * substring / case-insensitive, never a delta-count or timing assertion. [folderName][FOLDER_NAME_PREFIX]
     * is a runtime-unique string that cannot pre-exist on screen, so a substring match on it (in the chip
     * and in the recents row) is a genuine presence check — the ping/tool tests' token discipline. The
     * unique name also keeps repeated LIVE gate runs green: `~/pyry-workspace` lives on the operator's
     * **real** `$HOME` (#527 isolates the pyry instance name, not `$HOME`) and the gate does not clean
     * between runs, so a fixed name would collide/accumulate.
     */
    @Test
    @Ignore("Workspace switching is no longer exposed in the mobile chat UI")
    fun interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        //    Wait for the relay connection to open before creating — the picker's create round-trips to
        //    the daemon, so acting before the session is Open would fail the request.
        awaitChannelList()
        awaitConnected()

        // 2. Create a chat in the daemon default, then open its thread folder picker.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        openThreadWorkspacePicker()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Open the create dialog, type a collision-resistant folder name, and confirm. The name is a
        //    clean single path element (the daemon rejects empty / absolute / separator-bearing / ".."),
        //    unique per run so a substring match on it is a genuine presence check.
        val folderName = FOLDER_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextInput(folderName)
        composeTestRule.onAllNodesWithText(CREATE_BUTTON).onFirst().performClick()

        // 4. AC-1: the picker moves the already-open discussion into the created folder.
        //    The send button marks the thread; the workspace chip reflects the folder's basename verbatim
        //    ("Workspace: <folderName> (change)"). The chip is still present here — no messages yet.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(folderName, substring = true)
            .onFirst()
            .assertIsDisplayed()

        // 5. AC-2: send the constrained ping in the new workspace and assert the streamed reply renders —
        //    proving the created folder is usable as a live session's workspace against a real claude turn.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        // 6. Re-open the thread picker through overflow after the chip unmounts and check Recent.
        openThreadWorkspacePicker()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RECENT_SECTION).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(folderName, substring = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /**
     * New-session twin of the ping happy path (#541, Layer 3): drive the real "Reset session" overflow flow
     * end to end against real claude, exercising the already-shipped #540 fire-and-forget wire. With a live,
     * exercised session, open the thread overflow menu → tap "Reset session" → the daemon wraps up and rotates →
     * broadcasts `session_transition` (`reason: "clear"`) → the thread folds a `ThreadItem.SessionBoundary`
     * (#336, canonical in `RemoteConversationRepository`) → `SessionBoundaryDelimiter` renders it. This proves
     * that path against real claude and the daemon's reset, not the boundary the Fake synthesizes.
     *
     * **Reachability.** The "Reset session" item is gated on `mutationsSupported` only (not promotion), which is
     * `true` in relay mode (PR #572), so the scenario is reachable on a plain **discussion** — the same real
     * overflow menu the operator uses.
     *
     * **Fire-and-forget — assert the durable delimiter, never an ack.** `new_session` is fire-and-forget
     * (pyrycode#831, #540 wire), so the only observable is the post-broadcast delimiter. The load-bearing
     * matcher is [SESSION_BOUNDARY_TEST_TAG], the test tag on
     * [de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter] (#1578), which can **only** come
     * from the rendered delimiter — it is reason-independent, so the match is robust even if the daemon's
     * `session_transition` reason differs from `clear`. [NEW_SESSION_ITEM] only selects the reset action;
     * the tagged delimiter proves the resulting session boundary, drawn without the retired "doesn't
     * remember" explanation. Its absence is asserted before
     * the reset tap, so its later appearance is attributable to the action — no extra
     * claude turn.
     *
     * **Always-on, not `@Ignore`d.** Unlike #482's transient thinking spinner — which leaves no trace once the
     * turn moves on — the delimiter is a **durable** artifact that survives the turn, so it belongs in the
     * always-on gate, matching #481's durable tool-name row.
     *
     * **The wrapping-up phase (#965).** Before the delimiter, the status area names the wrapping-up phase, and
     * it clears once the reset is over. The daemon holds that phase for its whole wrap-up turn, which it runs
     * for any live child whether or not handoff notes are stored. The restarting phase is not asserted: it
     * lasts only the respawn, with nothing holding it open, so `ScriptedResettingTest` alone proves it.
     *
     * Real-claude cost: the ping turn plus the daemon's reset wrap-up turn.
     */
    @Test
    fun interactiveTurn_newSession_rendersSessionBoundaryDelimiter() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating a conversation.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — the "Reset session" item is gated on mutationsSupported only, not promotion.
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val before = hostConversationIds(serverId)
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        val conversationId = newHostConversationId(serverId, before)
        val repository = hostRepository(serverId)

        // 4. Prove the session is live (AC-3): send the constrained ping and wait for the streamed reply, so the
        //    session is genuinely exercised and there is above-delimiter content once it clears.
        //    The daemon may run a separate wrap-up turn after the New-session tap.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(PING_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

        runBlocking {
            withTimeout(THREAD_TIMEOUT_MS) { repository.observeContextUsage(conversationId).filterNotNull().first() }
        }

        // 5. Absence guard (AC-2, deterministic — no extra turn): no delimiter may be on screen yet, so its
        //    later appearance is attributable to the New-session tap.
        composeTestRule
            .onAllNodesWithTag(SESSION_BOUNDARY_TEST_TAG)
            .assertCountEquals(0)

        runBlocking {
            // Observe before tapping: session_transition clears the old reading, and only a later
            // context_usage reply can fill it. Settings fallback cannot satisfy this wait (#1761).
            val freshReading =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(REPLY_TIMEOUT_MS) {
                        repository
                            .observeContextUsage(conversationId)
                            .dropWhile { it != null }
                            .filterNotNull()
                            .first()
                    }
                }

            // 6. Open the real overflow menu and tap Reset session. The durable assertion uses
            //    SESSION_BOUNDARY_TEST_TAG, independently of the action label.
            composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).onFirst().performClick()

            // 7. #965: the status area names the wrapping-up phase before any delimiter. The daemon lowers it only
            //    once its wrap-up turn — a real claude turn writing the handoff note — has ended, so the phase is
            //    held by that turn, not caught on timing. Restarting spans only the respawn and nothing holds it,
            //    so it is left to ScriptedResettingTest.
            val wrappingUp = string(R.string.thread_resetting_wrapping_up)
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(wrappingUp)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule
                .onAllNodesWithTag(SESSION_BOUNDARY_TEST_TAG)
                .assertCountEquals(0)

            // 8. The phase clears: no resetting label of either phase is left in the status area.
            val resettingLabels =
                listOf(
                    R.string.thread_resetting_wrapping_up,
                    R.string.thread_resetting_restarting,
                    R.string.thread_resetting_restarting_written,
                    R.string.thread_resetting_restarting_skipped,
                ).map(::string)
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                resettingLabels.all { label -> composeTestRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isEmpty() }
            }

            // 9. Reveal the newest row while waiting: the daemon's wrap-up reply can fill the viewport
            //    before session_transition appends the delimiter. The delimiter must still be displayed, with no
            //    explanation line under it (#1578).
            composeTestRule.awaitDisplayedSessionBoundary(REPLY_TIMEOUT_MS)

            val usage = freshReading.await()
            assertTrue("post-reset context reading must have a usable token window", usage.maxTokens > 0)
            val percent =
                kotlin.math
                    .floor(usage.totalTokens.toDouble() / usage.maxTokens * 100 + 0.5)
                    .toInt()
                    .coerceIn(0, 100)
            val descriptionResource =
                when {
                    percent >= 85 -> R.string.cd_context_usage_high
                    percent >= 70 -> R.string.cd_context_usage_warning
                    else -> R.string.cd_context_usage
                }
            val expected = InstrumentationRegistry.getInstrumentation().targetContext.getString(descriptionResource, percent)
            awaitContextSegment(THREAD_TIMEOUT_MS, "the fresh post-reset reading ($percent%)") { it == expected }
        }
    }

    /**
     * Delete-conversation twin of the ping happy path (#554, Layer 3): drive the real Delete flow end to
     * end against a real daemon, exercising the already-shipped #532 `delete` wire (pyrycode#822). Give a
     * scratch discussion a runtime-unique, list-visible identity via **Rename**, confirm it is **present**
     * on the channel list, then delete it from the thread — thread overflow → "Channel info" → the sheet's
     * "Delete" → the "Delete conversation?" dialog → confirm — and assert **both** durable post-conditions:
     * the unique name is **gone from the list** and the **thread has popped back**.
     *
     * **Reachability.** The Delete affordance lives in the `mutationsSupported`-gated Actions block of the
     * Channel Info sheet, reached from the **ungated** "Channel info" overflow item.
     * [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported] is `true` in relay
     * mode (PR #572), so the flow is reachable on a plain **discussion** — the same real overflow the
     * operator uses. Delete is conversation-scoped (keyed by `conversation_id`, replies
     * `conversation_deleted`), so it carries none of the session-scoped blockers that re-park the sibling
     * e2es; it is in the clean-buildable camp with #541 / #566.
     *
     * **Durable identity via Rename, not promote.** A scratch discussion is auto-named server-side, so its
     * name is not test-controlled and asserting one's absence is fragile. Renaming to
     * [CONVERSATION_NAME_PREFIX]` + System.currentTimeMillis()` gives a runtime-unique, list-visible token
     * that cannot pre-exist on screen nor collide with conversations accumulated by prior LIVE gate runs.
     * Rename (not "Save as channel") touches only the name — no dedicated-workspace folder that would
     * accumulate on the operator's real `~/pyry-workspace` across runs (the #566 accumulation problem). The
     * renamed discussion stays a discussion and is #1 in `observeConversations(Discussions)`
     * (`sortedByDescending { lastUsedAt }`, just created) → always inside the visible recents, so its row is
     * guaranteed present.
     *
     * **The absence is a genuine inversion.** [CONVERSATION_NAME_PREFIX]` + …` is unique, so its presence is
     * observed on the list (step 5 assert + step 6 re-enter tap) *before* the delete, and its
     * `assertCountEquals(0)` after (step 9) is a real present→absent flip on the same surface — never a
     * match-everything, never a delta count or timing (the #481 / #566 token discipline, applied to an
     * **absence** assertion).
     *
     * **The "Delete" collision (the one gotcha).** The sheet's Delete `ActionCell` and the confirm dialog's
     * button are **both** the literal `"Delete"`, and `ThreadEvent.Delete` leaves the sheet composed behind
     * the dialog (it sets `pendingDeleteConfirm` without clearing `pendingChannelInfo`), so both "Delete"
     * nodes are on screen at confirm time. The confirm tap is disambiguated by a compound matcher only the
     * dialog's button satisfies — its sibling is [DELETE_DIALOG_CANCEL], which the sheet (whose dismiss is a
     * Close *icon*) has no equivalent of. Never [onFirst] across the two identical "Delete" nodes (z-order
     * is not guaranteed).
     *
     * **Always-on, not `@Ignore`d.** The post-conditions are **durable** structural facts (a conversation is
     * in the list or not; the thread popped or not) — no transient like #482's spinner — so the scenario
     * belongs in the always-on gate, matching #481's tool-name row and #541's delimiter.
     *
     * **Zero real-claude turns (deliberate divergence from #541 / #566).** Create-discussion, rename, and
     * delete are daemon round-trips, not claude turns, and the durable identity is the typed name (no live
     * session content needed to identify it), so this scenario sends **no** ping and spends **no** claude
     * turn. It still rides the real rung-3 stack (real relay + daemon) and belongs in the LIVE gate: it
     * catches a broken `delete` / `rename` wire against the production relay. The LIVE gate is a **quartet**
     * (4 methods) at **still 3 turns** (delete adds a method, not a turn).
     */
    @Test
    fun interactiveTurn_deleteConversation_removesFromListAndClosesThread() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — rename/delete round-trip to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" (mutationsSupported) and "Channel info" (ungated) both reach it.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Rename the discussion to a runtime-unique, list-visible name. Open the overflow, tap "Rename".
        //    The RenameDialog opens OVER the thread, whose composer is also an editable field, so
        //    hasSetTextAction() alone is ambiguous — target the dialog's field by its focus (RenameDialog
        //    auto-focuses on open; the composer never requested focus), waiting for focus to land. REPLACE
        //    the pre-filled+selected auto-name (performTextReplacement, not performTextInput) so the field
        //    holds exactly the unique name, then Save.
        val uniqueName = CONVERSATION_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithText(RENAME_SAVE).performClick()

        // 5. Presence check (AC-3): back to the list, wait for it, then confirm the unique name is displayed on
        //    a recents row. The rename reply (conversation_updated) upserts → observeConversations re-emits with
        //    the new name; the waitUntil covers that round-trip. This is the genuine presence observation on the
        //    same surface where absence is later asserted (step 9).
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 6. Re-enter the thread by tapping the recents row (a 2nd presence observation — it can only succeed if
        //    the name is on the list). The merged DiscussionPreviewRow carries the name as text and is clickable.
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 7. Open Channel info → tap the sheet's Delete. "Channel info" is ungated; the sheet's Delete
        //    ActionCell is unique while only the sheet is open. Tapping it opens the confirm dialog OVER the
        //    still-composed sheet (ThreadEvent.Delete leaves pendingChannelInfo true) → two "Delete" nodes.
        //    The Session (#1346), System prompt and MCP servers sections push Actions below the fold, so scroll
        //    to Delete before tapping; an off-screen tap lands outside the sheet and opens nothing (#1344).
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(DELETE_ACTION).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(DELETE_ACTION).performScrollTo().performClick()

        // 8. Confirm the delete. Wait for the dialog's unique title, then tap the CONFIRM "Delete" — the sheet's
        //    "Delete" is also on screen, so disambiguate by the dialog's sibling "Cancel" button (the sheet has
        //    none). If the button-row tree differs on first run, pick another unambiguous anchor rooted at the
        //    dialog title (rung 3 permits selector tuning) — never onFirst() across the two identical "Delete".
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(DELETE_DIALOG_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onNode(hasText(DELETE_ACTION) and hasAnySibling(hasText(DELETE_DIALOG_CANCEL)))
            .performClick()

        // 9. Both durable post-conditions (AC-2). After DeleteConfirm → repository.delete → PopBack: wait for the
        //    list marker (the thread has popped back), then assert the unique name is gone from the list. delete
        //    completes (conversation_deleted → removeConversation clears all projections) BEFORE PopBack fires
        //    (sequential in the same coroutine), so the re-projection has landed by the time the list renders →
        //    a direct assertCountEquals(0). Tolerant: presence/absence, generous timeout — never a delta count.
        awaitChannelList()
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)
    }

    /**
     * Archive/restore round-trip twin of the delete scenario (#551, Layer 3): drive the real Archive and
     * Restore flows end to end against a real daemon, exercising the already-shipped #549 archive/unarchive
     * wire, the #556 archive-from-thread surfacing, and the #557 restore-from-Archive-screen surfacing. Give
     * a scratch discussion a runtime-unique, list-visible identity via **Rename**, confirm it is **present**
     * on the channel list, archive it from the thread (overflow → "Archive", **immediate — no confirm**) and
     * assert it is **gone** from the list, then restore it (list header menu → Archive → Archived
     * screen's restore affordance) and assert it is **back** in the list. The same unique token flips **out
     * of** and then **back into** the same surface, so each list assertion is a genuine inversion of the other.
     *
     * **Reachability.** The "Archive" overflow item lives in the `mutationsSupported`-gated block
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu]);
     * [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported] is `true` in relay
     * mode (PR #572), so it is reachable on a plain **discussion** — the same real overflow the operator uses.
     * Archive/unarchive are conversation-scoped (keyed by `conversation_id`, reply reuses
     * `conversation_updated`), so they carry none of the session-scoped blockers that re-park the sibling
     * e2es; clean-buildable with #541 / #554 / #566.
     *
     * **Durable identity via Rename** (unchanged from #554). The Archived screen renders **only the display
     * name** ([de.pyryco.mobile.ui.conversations.components.ArchiveRow]), and a scratch discussion is
     * auto-named server-side → it renders as the non-unique fallback "Untitled discussion", un-findable on the
     * Archived screen. Renaming to [ARCHIVE_NAME_PREFIX]` + System.currentTimeMillis()` gives a runtime-unique,
     * list-visible token that survives archive → restore, cannot pre-exist on screen nor collide with prior
     * LIVE-gate leftovers, and makes both the archive **absence** and the restore **presence** assertions
     * genuine inversions.
     *
     * **Round-trip, not one-shot (the divergence from #554).** #554 asserts one direction (delete → absent).
     * This asserts the list flip in **both** directions. Two structural differences from the delete twin:
     * (1) **Archive is immediate — no confirm dialog, no sheet.** The "Archive" item sits directly in the
     * thread overflow and fires `ThreadEvent.Archive → sendArchive → repository.archive → success-only
     * PopBack`; there is **none** of #554's "Delete"-collision / sheet-behind-dialog disambiguation — the
     * archive tap is a single [onNodeWithText] in the open overflow. (2) **Restore needs a second screen:**
     * channel list → Archived screen, a tap on its **Discussions** tab (Archive opens on Channels since #1487),
     * restore, then Back to confirm re-appearance.
     *
     * **The one gotcha — the restore-coroutine cancellation race.** `RestoreRequested` handling is
     * `viewModelScope.launch { repository.unarchive(id); … }` scoped to the **Archived screen's**
     * `ArchivedDiscussionsViewModel`. Tapping restore then immediately navigating Back would `popBackStack`
     * that ViewModel and cancel a launched-but-unstarted `unarchive` before it ever sent the request → the
     * conversation would never restore and the closing presence check would flake to a timeout. Step 11's wait
     * for the **"Restored" success snackbar** closes this: the snackbar renders only after `unarchive`
     * returned and `RestoreSucceeded` was sent, so once it is observed the round-trip has fully completed and
     * navigating away is safe. [RESTORED_SNACKBAR] (`"Restored"`) appears in no other on-screen string (the row
     * subtitle is "Archived <time>"; the restore button's content-description is "Restore …", not "Restored"),
     * so a non-empty text match can only be the success snackbar.
     *
     * Both post-conditions are durable structural facts, so this scenario belongs in the live gate.
     *
     * **Zero real-claude turns (same as #554).** Create-discussion, rename, archive, and restore are daemon
     * round-trips, not claude turns, and the durable identity is the typed name, so this scenario sends **no**
     * ping and spends **no** claude turn. It still rides the real rung-3 stack (real relay + daemon) and
     * catches a broken `archive` / `unarchive` / `rename` wire against the production relay.
     */
    @Test
    fun interactiveTurn_archiveRestore_roundTripsListMembership() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — rename/archive/restore round-trip to the daemon.
        awaitConnected()

        val serverId = twoHostArg(ARG_SERVER_ID)
        val before = hostConversationIds(serverId)

        var createdId: String? = null
        try {
            // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
            //    plain discussion suffices — "Rename" and "Archive" are both gated on mutationsSupported, reachable on it.
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            createdId = newHostConversationId(serverId, before)

            // 4. Rename the discussion to a runtime-unique, list-visible name (identical to #554 step 4). The
            //    RenameDialog opens OVER the thread, whose composer is also an editable field, so hasSetTextAction()
            //    alone is ambiguous — target the dialog's field by its focus (RenameDialog auto-focuses on open),
            //    REPLACE the pre-filled+selected auto-name (performTextReplacement, not performTextInput), then Save.
            val uniqueName = ARCHIVE_NAME_PREFIX + System.currentTimeMillis()
            composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
            composeTestRule.onNodeWithText(RENAME_SAVE).performClick()

            // 5. Presence check #1 (AC-1): back to the list, wait for it, then confirm the unique name is displayed on
            //    its chat row — the genuine presence observation on the surface where absence is later asserted (step 8).
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

            // 6. Re-enter the thread by tapping the chat row (a 2nd presence observation — it can only succeed if
            //    the name is on the list). Archive is driven "from the thread".
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }

            // 7. Archive (immediate — no confirm dialog). Open the overflow, wait for the "Archive" item, tap it:
            //    sendArchive → repository.archive → success-only PopBack. "Archive" is unique in the open overflow.
            composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(ARCHIVE_ITEM).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(ARCHIVE_ITEM).performClick()

            // 8. Absence check (AC-1). After PopBack: wait for the list marker (the thread has popped back), then
            //    assert the unique name is gone from the active list — a genuine inversion of step 5. archive folds
            //    the conversation out of the active projection on the repo's demux loop before PopBack renders the
            //    list, so a direct assertCountEquals(0). Tolerant: presence/absence, generous timeout — never a delta count.
            awaitChannelList()
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)

            // 9. The list header menu opens Archive for the selected host. Its Discussions tab shows the chat.
            openListMenuEntry(R.string.thread_overflow_archive)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            openArchiveTab(R.string.archived_tab_discussions)

            // 10. Restore. Wait for the restore affordance keyed on the unique name — the "Restore <uniqueName>"
            //     IconButton (the row name is a Text node, so only the restore button matches a content-description
            //     search) — a presence observation on the Archived screen, then tap it: RestoreRequested →
            //     repository.unarchive.
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(uniqueName, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodes(hasContentDescription(uniqueName, substring = true)).onFirst().performClick()

            // 11. Restore-completed guard (the one gotcha — see KDoc). Wait for the "Restored <name>" success
            //     snackbar BEFORE navigating back: it renders only after repository.unarchive returned and
            //     RestoreSucceeded was sent, so the ArchivedDiscussionsViewModel-scoped restore coroutine is not
            //     cancelled mid-flight by the return-nav's popBackStack.
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(RESTORED_SNACKBAR, substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 12. Return directly from Archive to the channel list after the restore has completed.
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()

            // 13. Presence check #2 (AC-2 — round-trip closes). Wait for the unique name on the active list, then
            //     confirm it is displayed. The re-appearance is attributable to the restore (asserted absent in
            //     step 8), on the same surface, same unique token.
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
        } finally {
            cleanupCreatedConversation(serverId, before, createdId, "archive discussion cleanup failed")
        }
    }

    /**
     * The list header's menu → Archive reaches the Archived screen (#740, #1665).
     * [interactiveTurn_archiveRestore_roundTripsListMembership] uses the same Archive entry
     * for a complete round trip, while this method checks the entry without creating a conversation.
     *
     * The bar is drawn on every state of the list, so the scenario needs no connection wait, no seeded
     * conversation, no prompt and no claude turn. The list draws no "Archived" text, so [ARCHIVED_TITLE] is
     * asserted absent before the tap and the arrival after it is a genuine inversion.
     */
    @Test
    fun interactiveTurn_listArchiveEntry_opensArchived() {
        awaitChannelList()
        composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).assertCountEquals(0)

        openListMenuEntry(R.string.thread_overflow_archive)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).onFirst().assertIsDisplayed()
    }

    /**
     * The Archive lists the most recently archived chat first (#1332). Chat A is created before chat B, so B
     * has the newer `last_used_at`; B is archived first and A second. Rename and archive do not bump
     * `last_used_at` on the daemon, so the old last-use order would put B on top and only the daemon's
     * `archived_at` puts A there — the scenario fails on the pre-#1332 order.
     *
     * When the screen opens, B already carries its stamp from the `list_conversations` reply that confirmed its
     * archive, while A may still hold none: that confirming read can match on the held list, where A was folded
     * in from `conversation_updated`, before its own reply lands. A can therefore first draw below B and move
     * in front of it when the screen's own list reply arrives; the screen keeps an at-top list on its new first
     * row, so the order is waited for rather than read once. Zero real-claude turns; both chats are deleted
     * afterwards.
     */
    @Test
    fun interactiveTurn_archiveTwoChats_listsSecondArchivedFirst() {
        awaitChannelList()
        awaitConnected()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val stamp = System.currentTimeMillis()
        val nameA = "${ARCHIVE_ORDER_PREFIX}a-$stamp"
        val nameB = "${ARCHIVE_ORDER_PREFIX}b-$stamp"
        var beforeA: Set<String>? = null
        var beforeB: Set<String>? = null
        var idA: String? = null
        var idB: String? = null
        try {
            // 1. A, then B: B is the newer by last use.
            beforeA = hostConversationIds(serverId)
            idA = createChatOn(serverId)
            renameOpenThread(nameA)
            leaveThread()
            beforeB = hostConversationIds(serverId)
            idB = createChatOn(serverId)
            renameOpenThread(nameB)

            // 2. Archive B from its open thread, then A from its reopened thread.
            archiveOpenThread()
            archivedIds(serverId) { idB in it }
            openChatRow(nameA)
            archiveOpenThread()
            archivedIds(serverId) { idA in it }

            // 3. Open Archive on its Discussions tab: A, archived last, is the first row.
            openListMenuEntry(R.string.thread_overflow_archive)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            openArchiveTab(R.string.archived_tab_discussions)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val restoreA = hasContentDescription(context.getString(R.string.cd_restore_archive, nameA))
            val restoreB = hasContentDescription(context.getString(R.string.cd_restore_archive, nameB))
            val anyRestore = hasContentDescription(context.getString(R.string.cd_restore_archive, ""), substring = true)

            fun topOf(matcher: SemanticsMatcher): Float? =
                composeTestRule
                    .onAllNodes(matcher)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.boundsInRoot
                    ?.top

            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                val a = topOf(restoreA)
                val b = topOf(restoreB)
                a != null && b != null && a < b
            }
            val topmost = composeTestRule.onAllNodes(anyRestore).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
            assertEquals("the second-archived chat is not the Archive's first row", topmost, topOf(restoreA))
        } finally {
            beforeA?.let { cleanupCreatedConversation(serverId, it, idA, "archive order cleanup failed") }
            beforeB?.let { cleanupCreatedConversation(serverId, it, idB, "archive order cleanup failed") }
        }
    }

    /**
     * Change-workspace twin of the create-workspace-folder scenario (#562, Layer 3): drive the real
     * "Change workspace…" overflow flow end to end against a real daemon, exercising the already-shipped
     * #560 `change_workspace` wire and #561 surfacing. Create a plain discussion, then via the **real**
     * thread overflow "Change workspace…" → Workspace Picker → "Create new folder…" → a runtime-unique
     * folder name, complete a `change_workspace` round-trip to that new target path, and assert the
     * conversation's recorded workspace durably flips to it — read off the [WorkspaceChip] (`"Workspace:
     * <newWorkspace> (change)"`, the recorded `cwd` basename).
     *
     * **Reachability.** The "Change workspace…" item lives in the `mutationsSupported`-gated block of the
     * overflow ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu]) and is **not**
     * promotion-gated; [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported]
     * is `true` in relay mode (PR #572), so it is reachable on a plain **discussion** — the same real
     * overflow the operator uses. `change_workspace` is **conversation-scoped** (keyed by `conversation_id`,
     * a line-for-line mirror of `rename`, **no** session transition), so it carries none of the
     * session-scoped `currentSessionId == ""` blocker that re-parks the settings e2e (#545); clean-buildable
     * with #541 / #554 / #551 / #566.
     *
     * **Assert the recorded cwd, not a session id.** Because `change_workspace` performs no session
     * transition, the durable post-condition is the recorded workspace, so the assertion targets the
     * `WorkspaceChip` (the `cwd` basename) — it deliberately does **not** reintroduce the session-scoped
     * dependency that parks #545. The chip is the assertion surface **only because no message is sent:** it
     * is gated `!isPromoted && !hasMessages` ([de.pyryco.mobile.ui.conversations.thread.ThreadScreen]), and
     * this scenario spends no claude turn (no ping), so `hasMessages` stays false and the chip stays mounted
     * throughout.
     *
     * **The one field-disambiguation gotcha (unlike #566).** #566 opens the picker over the **channel
     * list** (no editable field), so `onNode(hasSetTextAction())` is unambiguous there. Here the picker
     * opens over the **thread**, whose composer ([de.pyryco.mobile.ui.conversations.thread.ThreadInputBar])
     * is also an editable field, so once the [CreateFolderDialog] opens **two** `hasSetTextAction()` nodes
     * are on screen. The dialog auto-focuses its field on open (`focusRequester.requestFocus()`) and the
     * composer never requests focus, so `hasSetTextAction() and isFocused()` selects the dialog's field —
     * the same disambiguation #554 uses for RenameDialog-over-thread. (Empty field → `performTextInput`,
     * not `performTextReplacement`.)
     *
     * **Two sequential daemon round-trips, one wait (the correctness note).** The Create tap chains
     * `createWorkspaceFolder` (returns the canonical path) → `onWorkspacePicked` → `sendChangeWorkspace` →
     * `changeWorkspace` → `conversation_updated` → the projection re-emits with the new `cwd` → the chip
     * re-labels. Step 6's single `waitUntil` spans **both** round-trips ([THREAD_TIMEOUT_MS] comfortably
     * covers them over `wss://`; bump only if the live relay proves slow on first operator run — rung 3
     * permits timeout tuning).
     *
     * **The unique name's only post-Create on-screen home is the chip — no transient false match.** [onCreate]
     * ([de.pyryco.mobile.ui.conversations.components.WorkspacePicker]) sets `showCreateDialog = false`
     * **synchronously before** the suspend, so the dialog's text field (which held [newWorkspace][WORKSPACE_FOLDER_PREFIX])
     * is gone the instant Create is tapped; the picker sheet then closes on `onPicked`; and the just-created
     * folder is not yet in the picker's "Recent" (a folder becomes recent only once used). So `onFirst()`
     * unambiguously lands on the chip.
     *
     * **The before → after inversion.** [newWorkspace][WORKSPACE_FOLDER_PREFIX] is `"e2e562-" +
     * System.currentTimeMillis()` — runtime-unique, so its **absence is asserted before** the change (step 3,
     * a deterministic guard, no claude turn) and its appearance in the chip after (step 6) is attributable to
     * the change. The unique suffix also keeps repeated LIVE gate runs green: each run creates one folder
     * under the operator's real `~/pyry-workspace` (the #566 accumulation pattern), and a fixed name would
     * collide with folders left by prior runs.
     *
     * **Always-on, not `@Ignore`d.** The recorded cwd is a **durable** fact (the chip re-label survives the
     * turn) — no transient like #482's spinner — so the scenario belongs in the always-on gate, matching
     * #481's tool-name row, #541's delimiter, and #554's / #551's list inversions. **Zero real-claude turns**
     * (like #554 / #551): create-folder and change-workspace are daemon round-trips, not claude turns. The
     * LIVE gate goes from a quintet (5 methods) to a **sextet** (6 methods) at **still 3 turns**.
     */
    @Test
    @Ignore("Workspace switching is no longer exposed in the mobile chat UI")
    fun interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736). Wait
        //    for the relay connection to open before creating — the picker's create + change round-trip to
        //    the daemon, so acting before the session is Open would fail the request.
        awaitChannelList()
        awaitConnected()

        // 2. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Change workspace…" is mutationsSupported-gated only, reachable on it.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Absence guard (the before-state, deterministic — no claude turn): the runtime-unique target
        //    name is not on screen yet (the chip shows the discussion's scratch workspace), so its later
        //    appearance in the chip is attributable to the change_workspace round-trip.
        val newWorkspace = WORKSPACE_FOLDER_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(newWorkspace, substring = true).assertCountEquals(0)

        // 4. Drive the REAL overflow: open "More actions", wait for the "Change workspace…" item (matched as
        //    a substring — the production string ends in a real U+2026 ellipsis), then tap it. The picker
        //    sheet opens (pendingWorkspacePicker = true), the same one the WorkspaceChip opens.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANGE_WORKSPACE_ITEM, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANGE_WORKSPACE_ITEM, substring = true).onFirst().performClick()

        // 5. Open the create dialog, type the collision-resistant folder name, and confirm. The dialog opens
        //    OVER the thread, whose composer is also an editable field, so hasSetTextAction() alone is
        //    ambiguous — target the dialog's field by its focus (CreateFolderDialog auto-focuses on open; the
        //    composer never requested focus), waiting for focus to land. The empty field → performTextInput.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CREATE_FOLDER_ROW, substring = true).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextInput(newWorkspace)
        composeTestRule.onAllNodesWithText(CREATE_BUTTON).onFirst().performClick()

        // 6. After-state (the durable post-condition). The single wait spans BOTH sequential daemon
        //    round-trips (create_workspace_folder → change_workspace); on success the chip re-labels to
        //    "Workspace: <newWorkspace> (change)" (the recorded cwd basename), so a non-empty match on the
        //    unique name can only be the chip. Tolerant: substring, generous timeout, presence — a genuine
        //    inversion of step 3's absence on the same surface.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(newWorkspace, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(newWorkspace, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * Rename-conversation twin of the delete / change-workspace scenarios (#537, Layer 3): drive the real
     * Rename flow end to end against a real daemon, exercising the already-shipped #530 `rename` wire
     * (pyrycode#820). The four shipped siblings (#541 / #551 / #554 / #562) already drive the same
     * `RenameDialog` as a **seeding** step; this promotes rename from a seed to the **subject** of its own
     * scenario — only the assertion target changes. Create a scratch discussion, rename it to a
     * runtime-unique title, submit, and assert the new title appears **durably** on **two** surfaces after the
     * round-trip: the **thread top bar** (in-thread, immediately after submit) and the **conversation list**
     * (after popping back).
     *
     * **Reachability.** The "Rename" overflow item lives in the `mutationsSupported`-gated block
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu]) and is **not** promotion-gated;
     * [de.pyryco.mobile.data.repository.RemoteConversationRepository.mutationsSupported] is `true` in relay
     * mode (PR #572), so it is reachable on a plain **discussion** — the same real overflow the operator uses.
     * `rename` is **conversation-scoped** (keyed by `conversation_id`, a line-for-line mirror of
     * `change_workspace` and `delete`, **no** session transition), so it carries none of the session-scoped
     * `currentSessionId == ""` blocker that re-parks the settings e2e (#545); clean-buildable with
     * #541 / #554 / #551 / #562.
     *
     * **Assert the recorded name, not a session id.** Because `rename` performs no session transition, the
     * durable post-condition is the recorded conversation **name**. Surface #1 is the thread top bar
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadScreen] `title = state.displayName`), reached
     * **immediately after submit** — unlike delete/archive there is **no PopBack** (`RenameSubmit` dismisses
     * the dialog and the thread stays open, [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel]), so
     * the top bar re-labels in place after `conversation_updated` folds into thread state. Surface #2 is the
     * conversation-list recents row, reached after tapping Back — the same fold upserts the list projection.
     * Neither surface is a session id (the #545 lesson).
     *
     * **The RenameDialog-over-thread field disambiguation (the one gotcha).** `RenameDialog` opens **over**
     * the thread, whose composer is also an editable field, so `hasSetTextAction()` alone is ambiguous — two
     * nodes. The dialog auto-focuses its field (`focusRequester.requestFocus()`) and the composer never
     * requests focus, so `hasSetTextAction() and isFocused()` selects the dialog's field (`and` is a
     * `SemanticsMatcher` member — no import). The field is **pre-filled with the server auto-name and fully
     * selected** → `performTextReplacement` (not `performTextInput`, which could leave the auto-name
     * concatenated). This is verbatim the selector the four siblings use for their rename seed.
     *
     * **No top-bar false match.** After Save, `RenameSubmit` flips `showRenameDialog` false synchronously, so
     * the dialog (whose field held [uniqueName][RENAME_NAME_PREFIX]) leaves composition before the round-trip
     * lands; the top bar still shows the old auto-name until then. The two never hold the unique name
     * simultaneously, so step 6's first match is the top bar.
     *
     * **Always-on, not `@Ignore`d.** Both post-conditions are **durable** structural facts (the recorded name
     * on two surfaces) — no transient like #482's spinner — so the scenario belongs in the always-on gate,
     * matching #481's tool-name row, #541's delimiter, and #554's / #562's inversions. **Zero real-claude
     * turns** (like #554 / #562): create-discussion and rename are daemon round-trips, not claude turns, and
     * the durable identity is the typed name, so this scenario sends **no** ping. The LIVE gate goes from a
     * sextet (6 methods) to a **septet** (7 methods) at **still 3 turns** — rename adds a method, not a turn.
     */
    @Test
    fun interactiveTurn_renameConversation_relabelsTopBarAndListRow() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — rename round-trips to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion suffices — "Rename" is mutationsSupported-gated only, reachable on it.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Absence guard (the before-state, deterministic — no claude turn): the runtime-unique target title
        //    is not on screen yet (the top bar shows the server auto-name), so its later appearance is
        //    attributable to the rename round-trip. Same guard as #562 step 3 / #554 step 9's inversion.
        val uniqueName = RENAME_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)

        // 5. Rename to the unique title (identical drive to #554 step 4). Open the overflow, tap "Rename". The
        //    RenameDialog opens OVER the thread, whose composer is also an editable field, so hasSetTextAction()
        //    alone is ambiguous — target the dialog's field by its focus (RenameDialog auto-focuses on open; the
        //    composer never requested focus), waiting for focus to land. REPLACE the pre-filled+selected
        //    auto-name (performTextReplacement, not performTextInput) so the field holds exactly the unique
        //    name, then Save.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithText(RENAME_SAVE).performClick()

        // 6. Top-bar assertion (AC-2, surface #1 — in-thread). This wait spans the rename round-trip
        //    (conversation_updated → state.displayName); there is NO PopBack, so the thread stays open and the
        //    top bar re-labels in place. The dialog has already left composition (Save flips showRenameDialog
        //    false synchronously), so the match is the top bar Text, not the dismissing field. Tolerant:
        //    substring, generous timeout, presence.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 7. List assertion (AC-2, surface #2 — after popping back). Tap Back, wait for the list marker (the
        //    thread has popped back), then wait for the unique name on a recents row and confirm it is
        //    displayed. The same conversation_updated fold upserts the list projection → observeConversations
        //    re-emits with the new name; the waitUntil covers that round-trip. A genuine inversion of step 4's
        //    absence, on the list surface, same unique token.
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * Save-as-channel (promote) twin of the rename scenario (#581, Layer 3): drive the real "Save as channel…"
     * overflow flow end to end against a real daemon, exercising the already-shipped #348 `promote` wire. This
     * is the **backfill** member of the family — save-as-channel shipped *before* the real-stack
     * definition-of-done rule (pyrycode-mobile-agents#9), and the gap was not theoretical: the daemon never
     * registered the `promote_conversation` handler, so the verb answered `unsupported` over the real wire and
     * **the promote never happened**, while the mobile suite stayed green against a fake daemon that answers
     * anything. pyrycode/pyrycode#949 landed the handler; this is the remaining mobile client half (the desktop
     * parallel is pyrycode-desktop#430). **Expected RED on any daemon older than #949 — that is the regression
     * it exists to catch.**
     *
     * **Reachability.** The "Save as channel…" item is gated on the conversation being **unpromoted**
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadOverflowMenu] `if (!isPromoted)`) and is **not**
     * `mutationsSupported`-gated (unlike #537's "Rename"), so a freshly created discussion reaches it
     * regardless of the capability flag. `promote` is **conversation-scoped** (**no** session transition), so it
     * carries none of the session-scoped `currentSessionId == ""` blocker that re-parks the settings e2e
     * (#545); clean-buildable with #541 / #551 / #554 / #562 / #566 / #537.
     *
     * **Wire shape.** `promote` is a **request/reply** verb whose reply is the **bare conversation object**,
     * folded by a confirmed upsert — it is **not** a `conversation_updated` broadcast (that is *rename's*
     * shape, from #530). So this asserts on **rendered UI**, never on a named wire message.
     *
     * **Three durable surfaces, one projection.** [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel]
     * derives thread state from `observeConversations(ConversationFilter.All)`, so a single confirmed upsert of
     * the promote reply drives all three assertions: (1) the **thread top bar**
     * ([de.pyryco.mobile.ui.conversations.thread.ThreadScreen] `title = state.displayName`), reached
     * **as soon as the modal closes** — unlike delete/archive there is **no PopBack** (the modal closes once its
     * writes are confirmed and the thread stays open), so the top bar re-labels in place; (2) the
     * **[de.pyryco.mobile.ui.conversations.components.WorkspaceChip] unmount** — the `isPromoted` tier flip,
     * in-thread and free, because the chip is gated `!isPromoted && !hasMessages` and this scenario **sends no
     * message**, so `hasMessages` stays false and the chip's disappearance is attributable **solely** to the
     * promote (the #562 gating fact, read in the opposite direction); and (3) after Back, **presence on the
     * main list** ∧ **absence from the Discussions drilldown**. None of the three is a session id (the #545
     * lesson).
     *
     * **Why the tier read is a tagged main-list matcher (#731).** The drilldown this scenario used to take is
     * gone with the recent-discussions section: the assembled list shows both tiers at once, so there is no
     * `See all discussions (N)` row left to tap. The tiers are still separable by no production string — both
     * sections instance the **same** [de.pyryco.mobile.ui.conversations.components.TreeConversationRow], with
     * the same glyph and type scale and no tier word anywhere on it — and a section's header and its rows are
     * **siblings** inside one `LazyColumn`, so there is no ancestor scoping to bet on either. The assembling
     * screen therefore tags each conversation row with the tier it drew it in ([TREE_CHANNEL_ROW_TEST_TAG] /
     * [TREE_CHAT_ROW_TEST_TAG]). Present on a channel-tagged row ∧ absent from every chat-tagged row **is**
     * "presented in the promoted (channel) tier rather than among the chats", expressed entirely in
     * presence/absence matchers — and its positive half is stronger than the old absence-in-a-drilldown,
     * because it names the tier the row is actually in. This is the #551 tier-membership idiom, reused.
     *
     * **The modal-over-thread field disambiguation (#537's idiom, reused).**
     * [de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog] (#957, the `MobileModal` shell) opens
     * **over** the thread, whose composer is also an editable field, and the modal holds a second one (the system
     * prompt), so `hasSetTextAction()` alone is ambiguous. The form focuses its name field on open and nothing
     * else requests focus, so `hasSetTextAction() and isFocused()` selects the name field. It is **pre-filled
     * and fully selected** with the chat's own name → `performTextReplacement`. The prompt field is reached by
     * its [CHANNEL_PROMPT_FIELD_TAG].
     *
     * **In place, with a prompt (#957).** There is no location choice any more: the chat is promoted in its own
     * `cwd` (`promote(..., workspace = null)`), so the scenario creates no folder on the operator's machine. It
     * types a short system prompt, so the modal's second write — `set_system_prompt`, sent only after the
     * promote is confirmed — rides the same run. The prompt applies at the next session start and this scenario
     * starts none, so it costs no claude turn.
     *
     * **One literal collision matched exactly, not by substring.** `save_as_channel_action` is
     * `"Save as channel…"` (U+2026) while `save_as_channel_dialog_title` is `"Save as channel"` (no ellipsis),
     * so a substring search conflates them — [SAVE_AS_CHANNEL_ITEM] and [SAVE_AS_CHANNEL_TITLE] are both matched
     * **exactly**. The second collision this scenario used to dodge went with the drilldown: step 8 no longer
     * navigates, so it needs no arrival marker.
     *
     * **No top-bar false match.** The modal now stays open, holding the unique name in its field, until both
     * writes are confirmed — a failure would keep it open with an error. So step 6 first waits for the modal's
     * exact title to leave, which is the proof both writes landed, and only then reads the unique name, which
     * can by then be only the top bar's.
     *
     * **Always-on, not `@Ignore`d.** All three post-conditions are **durable** structural facts (the recorded
     * name and the recorded `isPromoted` flag) — no transient like #482's spinner — so the scenario belongs in
     * the always-on gate, matching #481's tool-name row, #541's delimiter, and #554's / #551's / #562's /
     * #537's inversions. **Zero real-claude turns**: create-discussion and promote are daemon round-trips
     * (promote is a pure registry op daemon-side), not claude turns, and the durable identity is the typed
     * name, so this scenario sends **no** ping. The LIVE gate goes from a septet (7 methods) to an **octet**
     * (8 methods) at **still 3 turns** — promote adds a method, not a turn.
     */
    @Test
    fun interactiveTurn_saveAsChannel_promotesToChannelTier() {
        // 1. A paired launch lands on the channel list, read off the list's own arrival marker (#736).
        awaitChannelList()

        // 2. Wait for the relay connection to open before creating — promote round-trips to the daemon.
        awaitConnected()

        // 3. Create a fresh discussion → the app navigates into its thread; the send button marks arrival. A
        //    plain discussion is exactly what is needed — "Save as channel…" is !isPromoted-gated (NOT
        //    mutationsSupported-gated), and a fresh discussion is unpromoted by construction.
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Absence guard + tier before-state (both deterministic — no claude turn). The runtime-unique
        //    channel name is not on screen yet (the top bar shows the server auto-name, which the modal
        //    pre-fills), so its later appearance is attributable to the promote
        //    round-trip. And the WorkspaceChip IS mounted — the discussion tier — the before-state of step 6's
        //    tier-flip inversion (it stays mounted because no message is sent: !isPromoted && !hasMessages).
        val uniqueName = PROMOTE_NAME_PREFIX + System.currentTimeMillis()
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(WORKSPACE_CHIP_PREFIX, substring = true).assertCountEquals(0)

        // 5. Promote to the unique name, with a system prompt. Open the overflow, tap "Save as channel…"
        //    (matched EXACTLY — the modal title is the same literal minus the U+2026 ellipsis). The modal opens
        //    OVER the thread, whose composer is also an editable field, and holds a second field of its own, so
        //    target the name field by its focus (the form focuses it on open), waiting for focus to land, and
        //    REPLACE the pre-filled+selected chat name. Type the prompt into its tagged field, then OK. There is
        //    no location choice: the chat is promoted in its own cwd, so no folder is created.
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(SAVE_AS_CHANNEL_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(SAVE_AS_CHANNEL_ITEM).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(uniqueName)
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextInput(SAVE_AS_CHANNEL_PROMPT)
        composeTestRule.onNodeWithText(SAVE_AS_CHANNEL_OK).performClick()

        // 6. In-thread assertions (surfaces #1 + #2). The first wait spans both writes: the modal closes only
        //    once the promote reply and then the set_system_prompt reply are confirmed (a failure keeps it open
        //    with an error, so this wait is the proof both landed). Only then is the unique name read — the
        //    modal's field held it until now, so after the close the match is the top bar, re-labelled in place
        //    (there is NO PopBack). The next wait is the tier flip — the WorkspaceChip unmounts once
        //    state.isPromoted is true. Independent waits keep a failure attributable.
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(SAVE_AS_CHANNEL_TITLE).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(WORKSPACE_CHIP_PREFIX, substring = true).fetchSemanticsNodes().isEmpty()
        }

        // 7. Main-list presence (surface #3a). Tap Back, wait for the list marker (the thread has popped back),
        //    then wait for the unique name on the list and confirm it is displayed — a genuine inversion of
        //    step 4's absence, same unique token. The same upsert feeds both list projections.
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(uniqueName, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(uniqueName, substring = true).onFirst().assertIsDisplayed()

        // 8. The tier read (surface #3b), now on the assembled list itself — no navigation, so no arrival
        //    marker to get wrong. Wait for the promoted conversation on a CHANNEL-tagged row, then assert it is
        //    on no CHAT-tagged row. Both tiers are drawn on this one screen, so present-as-a-channel ∧
        //    absent-among-the-chats ⇒ presented in the promoted tier. Tolerant: presence/absence with a
        //    generous timeout — never a delta count, a row ordering or a geometric read.
        val channelTierRow = hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText(uniqueName, substring = true)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(channelTierRow).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(channelTierRow).onFirst().assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(uniqueName, substring = true))
            .assertCountEquals(0)
    }

    /**
     * Mute notifications in Edit channel round-trips through the host (#1021, rung 3). The checkbox writes
     * `set_conversation_muted` and nothing is patched locally, so the only way the modal can reopen checked is
     * the daemon storing the flag and echoing it back in `conversation_updated`. The unit and screen tests
     * prove the write against a fake that patches its own rows; this proves the real daemon keeps it.
     *
     * The channel is set up on the host directly (a discussion promoted in its own cwd, so no folder is
     * created) and deleted in `finally`, which also leaves no muted channel behind. The drive is the operator's:
     * the thread menu's Edit opens Edit channel, OK closes it only once every write is confirmed, and the reopened
     * modal reads the flag from the host's row. It is checked on the host's own record too, and unchecking
     * proves the clear goes the same way.
     *
     * **Zero real-claude turns**: create, promote, mute and delete are daemon round-trips.
     */
    @Test
    fun interactiveTurn_muteChannel_roundTripsThroughTheHost() {
        awaitChannelList()
        awaitConnected()
        val repository = hostRepository()
        val name = MUTE_NAME_PREFIX + System.currentTimeMillis()
        val channel =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) { repository.promote(repository.createDiscussion().id, name) }
            }
        try {
            // Opens unchecked: a new channel is not muted. Check it and save.
            setMuteInEditChannel(name, from = false, to = true)
            assertHostMuted(repository, channel.id, true)

            // Reopens checked, read from the host's echoed row. Uncheck it and save: the clear round-trips too.
            setMuteInEditChannel(name, from = true, to = false)
            assertHostMuted(repository, channel.id, false)
        } finally {
            runCatching { runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repository.delete(channel.id) } } }
                .onFailure { Log.w("E2E", "mute channel cleanup failed: ${it::class.simpleName}") }
        }
    }

    /**
     * Open Edit channel from [name]'s thread menu, check the Mute notifications row opens at [from], set it to
     * [to] and press OK, then wait for the modal to close — it closes only once the host confirmed the write —
     * and return to the list.
     */
    private fun setMuteInEditChannel(
        name: String,
        from: Boolean,
        to: Boolean,
    ) {
        val title = string(R.string.edit_channel_title)
        val mute = hasText(string(R.string.edit_channel_mute)) and isToggleable()
        openChannelEditor(name)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(mute and if (from) isOn() else isOff()).fetchSemanticsNodes().isNotEmpty()
        }
        if (from != to) composeTestRule.onNode(mute).performScrollTo().performClick()
        composeTestRule.onNode(mute and if (to) isOn() else isOff()).assertExists()
        composeTestRule.onNodeWithText(EDIT_CHANNEL_OK).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        leaveThread()
    }

    /** [conversationId]'s own row on the host reports [muted]. */
    private fun assertHostMuted(
        repository: ConversationRepository,
        conversationId: String,
        muted: Boolean,
    ) {
        runBlocking {
            withTimeout(LIST_TIMEOUT_MS) {
                repository
                    .observeConversations(ConversationFilter.All)
                    .first { rows -> rows.any { it.id == conversationId && it.muted == muted } }
            }
        }
    }

    /**
     * Two paired hosts whose conversations share one id stay separate (#847, rung 3). Daemon-minted ids
     * never collide by chance, so `scripts/e2e-emulator.sh` seeds the collision: before either daemon
     * starts it writes ONE promoted conversation under the same run-unique id into host A's instance and
     * a second test daemon's instance, named [collisionNameA][ARG_COLLISION_NAME_A] and
     * [collisionNameB][ARG_COLLISION_NAME_B]. Host A is pre-paired by [E2eTestApplication]; host B is
     * paired here through the app's own paste-a-code flow, with the code the harness minted and
     * re-pointed at the relay the phone dials.
     *
     * **What "separate" is read from.** Row, thread and cache are all keyed by `(serverId,
     * conversationId)` (#731, #795–#798). A key that dropped the host would show one name twice, open
     * one host's conversation from the other's row, or file a rename under both. Each check below keys
     * on the two exact, run-unique names, so the two reads distinguish the hosts with no new test tag:
     *  * **under its own host** — folding a host's Channels row hides its own conversation and leaves
     *    the other host's ([assertEachUnderOwnHost]);
     *  * **opens its own conversation** — a row's thread shows that row's name and never the other's
     *    ([assertRowOpensOwnThread]).
     * Both are re-read after a rename of host A's conversation, after each host's link is cut and
     * restored, and after the object graph is rebuilt over the same on-device state — the restart an
     * instrumented test can perform ([E2eTestApplication.rebuildGraph]; it cannot kill its own process).
     *
     * **Shared app state.** Every live method shares one Application and one Koin graph, and a newly
     * saved host becomes the registry's selection, which the other scenarios' connection waits follow.
     * Host B is therefore removed in `finally`, on whichever graph is current then.
     *
     * **Zero real-claude turns**: pairing, navigation, rename and link cycling are daemon round-trips.
     */
    @Test
    fun interactiveTurn_twoHostsCollidingConversationId_stayPerHost() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val collisionId = twoHostArg(ARG_COLLISION_CONVERSATION_ID)
        val nameA = twoHostArg(ARG_COLLISION_NAME_A)
        val nameB = twoHostArg(ARG_COLLISION_NAME_B)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var relaunched: ActivityScenario<MainActivity>? = null
        try {
            // 1. Host A's seeded conversation is on the list, and host A really holds it under the seeded id.
            awaitChannelList()
            awaitConnected()
            awaitChannelRow(nameA)
            assertHostHoldsConversation(serverIdA, collisionId, nameA)

            // 2. Pair host B through the section header's add control → scanner → paste link → PairCodeScreen.
            //    The scanner asks for CAMERA at runtime; granting it first keeps the system dialog off screen.
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
            pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))
            awaitChannelRow(nameB)
            assertHostHoldsConversation(serverIdB, collisionId, nameB)

            // 3. AC-1 + AC-2: each conversation sits under its own host and each row opens its own thread.
            val labelA = hostLabel(serverIdA)
            val labelB = hostLabel(serverIdB)
            assertHostsStaySeparate(labelA to nameA, labelB to nameB)

            // 4. AC-2: rename host A's conversation from its thread (#537's drive). The new name shows on
            //    A's thread and A's row only; B's row and thread keep B's name.
            val renamedA = RENAMED_NAME_PREFIX + System.currentTimeMillis()
            openRow(nameA)
            renameOpenThread(renamedA)
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            awaitChannelRow(renamedA)
            composeTestRule.onAllNodes(channelRow(nameA)).assertCountEquals(0)
            assertHostsStaySeparate(labelA to renamedA, labelB to nameB)

            // 5. AC-3: cut and restore each host's link in turn, then re-read both.
            cycleHostLink(serverIdA)
            cycleHostLink(serverIdB)
            awaitChannelRow(renamedA)
            awaitChannelRow(nameB)
            assertHostsStaySeparate(labelA to renamedA, labelB to nameB)

            // 6. AC-3: restart. No activity may outlive the graph it resolved, so the rule's activity is
            //    destroyed first (recreate() would retain its view models, which hold the old graph).
            composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph()
            }
            relaunched = ActivityScenario.launch(MainActivity::class.java)
            awaitChannelList()
            awaitConnected()
            awaitChannelRow(renamedA)
            awaitChannelRow(nameB)
            assertHostsStaySeparate(labelA to renamedA, labelB to nameB)
        } finally {
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB) }
            relaunched?.close()
        }
    }

    /**
     * Managing a second host leaves the first alone (#1085, rung 3). Host B is paired by code as in #847,
     * renamed from its host row's Edit host modal (#744), then unpaired from the same modal (#745): once
     * declined, once confirmed. After each step host A keeps its name, its conversations and its very
     * connection bundle ([assertFirstHostUntouched]).
     *
     * **Which of A's conversations.** The seeded collision conversation, by its id, under whatever name A
     * holds for it now: #847 renames it, and JUnit orders methods by name hash, so the seeded name may
     * already be gone. B's seeded conversation is never renamed, so [ARG_COLLISION_NAME_B] marks B's section.
     *
     * **Unpair is phone-local.** It removes the pairing and the registry closes B's bundle; the device is not
     * revoked on the daemon, so #847 can pair the same code again in the same run. B is still removed in
     * `finally`, so a red run cannot leave it paired or selected for a later scenario.
     *
     * **Zero real-claude turns**: pairing, rename and unpair are daemon round-trips or phone-local.
     */
    @Test
    fun interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val nameB = twoHostArg(ARG_COLLISION_NAME_B)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val registry = GlobalContext.get().get<RelayConnectionRegistry>()
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        try {
            // 1. Host A's baseline: its label, its bundle, its conversations and one conversation's name.
            awaitChannelList()
            awaitConnected()
            val labelA = hostLabel(serverIdA)
            val bundleA = checkNotNull(registry.connectionFor(serverIdA)) { "host A not registered" }
            val idsA = hostConversationIds(serverIdA)
            val nameA =
                checkNotNull(heldConversationName(serverIdA, twoHostArg(ARG_COLLISION_CONVERSATION_ID))) {
                    "host A's seeded conversation has no name"
                }
            val firstHost = FirstHost(serverIdA, labelA, bundleA, idsA, nameA)

            // 2. Pair host B by code, as #847 does.
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))
            awaitChannelRow(nameB)
            assertEquals(HOST_B_NAME, hostLabel(serverIdB))
            assertFirstHostUntouched(firstHost)

            // 3. AC-1: rename B from its host row's Edit host modal.
            val renamedB = HOST_RENAME_PREFIX + System.currentTimeMillis()
            openHostEditor(serverIdB)
            composeTestRule.onNode(hasTestTag(EDIT_HOST_NAME_FIELD_TAG)).performTextReplacement(renamedB)
            composeTestRule.onNode(hasText(EDIT_HOST_OK) and hasClickAction()).performClick()
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                editorClosed(context) &&
                    composeTestRule.onAllNodes(hasContentDescription(hostEditDescription(renamedB))).fetchSemanticsNodes().isNotEmpty() &&
                    composeTestRule.onAllNodes(hasContentDescription(hostEditDescription(HOST_B_NAME))).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(renamedB, hostLabel(serverIdB))
            awaitChannelRow(nameB)
            assertFirstHostUntouched(firstHost)

            // 4. AC-2: declining the confirmation returns to the editor and removes nothing.
            openHostEditor(serverIdB)
            requestUnpair(context)
            composeTestRule.onNode(hasText(modalCancel) and hasClickAction()).performClick()
            awaitEditorTitle(context)
            composeTestRule.onNode(hasText(modalCancel) and hasClickAction()).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { editorClosed(context) }
            assertNotNull("declining the unpair removed host B's pairing", runBlocking { store.loadById(serverIdB) })
            assertNotNull("declining the unpair closed host B's connection", registry.connectionFor(serverIdB))
            scrollListTo(hasContentDescription(hostEditDescription(renamedB)))
            awaitChannelRow(nameB)
            assertFirstHostUntouched(firstHost)

            // 5. AC-2: confirming removes B's section, its pairing and its connection; A still opens.
            openHostEditor(serverIdB)
            requestUnpair(context)
            composeTestRule.onNode(hasText(EDIT_HOST_OK) and hasClickAction()).performClick()
            // Absent from the whole tree, not merely off screen: scrolling to either must find nothing. The
            // modal goes first, so its own scrollable cannot answer the scroll.
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                editorClosed(context) &&
                    composeTestRule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() &&
                    runCatching { scrollListTo(hasContentDescription(hostEditDescription(renamedB))) }.isFailure &&
                    runCatching { scrollListTo(channelRow(nameB)) }.isFailure
            }
            assertNull("host B is still in the paired-server store", runBlocking { store.loadById(serverIdB) })
            assertNull("host B still has a connection", registry.connectionFor(serverIdB))
            awaitConnected()
            assertFirstHostUntouched(firstHost)
            openRow(nameA)
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
        } finally {
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB) }
        }
    }

    /** #1775: host-wide prompt storage through the real Edit host controls; zero Claude turns. */
    @Test
    fun interactiveTurn_hostSystemPrompt_editsResetsAndCancels() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        awaitChannelList()
        awaitConnected()

        fun fresh() = runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).requestHostSystemPrompt().getOrThrow() } }
        val original = fresh().systemPrompt
        val custom = "Host prompt e2e1775 " + System.currentTimeMillis() + "\nPreserve this second line."

        fun openPrompt() {
            composeTestRule.onNode(hasText(context.getString(R.string.host_prompt_title)) and hasClickAction()).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag(de.pyryco.mobile.ui.host.HOST_PROMPT_FIELD_TAG)).fetchSemanticsNodes().size == 1
            }
        }

        fun ok() = composeTestRule.onNode(hasText(EDIT_HOST_OK) and hasClickAction()).performClick()

        fun cancel() = composeTestRule.onNode(hasText(modalCancel) and hasClickAction()).performClick()
        try {
            openHostEditor(serverId)
            openPrompt()
            composeTestRule.onNodeWithTag(de.pyryco.mobile.ui.host.HOST_PROMPT_FIELD_TAG).performTextReplacement(custom)
            ok()
            awaitEditorTitle(context)
            assertTrue("custom host prompt was not durably stored", fresh().systemPrompt == custom)
            cancel()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { editorClosed(context) }
            openHostEditor(serverId)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule
                    .onAllNodes(
                        hasTestTag(de.pyryco.mobile.ui.host.HOST_PROMPT_PREVIEW_TAG) and hasText(custom),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .size ==
                    1
            }
            openPrompt()
            composeTestRule.onNodeWithText(context.getString(R.string.host_prompt_reset)).performClick()
            cancel()
            awaitEditorTitle(context)
            assertTrue("reset then Cancel changed host storage", fresh().systemPrompt == custom)
            openPrompt()
            composeTestRule.onNodeWithText(context.getString(R.string.host_prompt_reset)).performClick()
            ok()
            awaitEditorTitle(context)
            val reset = fresh()
            assertTrue("reset then OK did not store the daemon default", reset.systemPrompt == reset.defaultSystemPrompt)
            cancel()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { editorClosed(context) }
        } finally {
            // Restore the isolated harness host even after an assertion fails. Never print its text.
            runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).setHostSystemPrompt(original).getOrThrow() } }
        }
    }

    /**
     * The live registry returns each paired host's own complete diagnostic archive (#1252, rung 3). A's
     * daemon alone logs a newly muted discussion id; B's archive must not contain it even while B is selected.
     * Archive content stays in memory and never enters assertion messages or logs. Pairing, the marker and
     * both transfers spend zero real-claude turns.
     */
    @Test
    fun interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val registry = GlobalContext.get().get<RelayConnectionRegistry>()
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        var marker: String? = null
        try {
            // Pair host B by code, then keep B selected while requesting A's archive by exact host id.
            awaitChannelList()
            awaitConnected()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
            pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))
            selectHost(registry, store, serverIdB)

            // Mint A's marker immediately before both requests so it remains in A's bounded log ring.
            val repositoryA = hostRepository(serverIdA)
            val minted = runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repositoryA.createDiscussion().id } }
            marker = minted
            runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repositoryA.setMuted(minted, true) } }
            assertTrue(
                "A's archive lacks the marker only A's daemon logged",
                completeBundleLogs(requestArchive(registry, serverIdA)).contains(minted),
            )
            assertFalse(
                "host B's archive holds the marker only A's daemon logged",
                completeBundleLogs(requestArchive(registry, serverIdB)).contains(minted),
            )
        } finally {
            marker?.let { id ->
                runCatching { runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverIdA).delete(id) } } }
                    .onFailure { Log.w("E2E", "diagnostic marker cleanup failed: ${it::class.simpleName}") }
            }
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB) }
        }
    }

    /**
     * The scanner reports a pairing done only once the host answered (#1386), over the real relay (#1394,
     * rung 3). No camera reads a code: once the scanner is ready, host B's pair code goes in as a decoded
     * QR, which the scanner parses as the code path does. Confirm, then the view model's connection wait,
     * then the list ([pairHostByScanner]).
     *
     * **Which label.** A scanner pairing saves no name, so both hosts would read "Unnamed host". B is named
     * with the store call the Edit host modal makes, then each seeded conversation is folded away under its
     * own host's label. A's is read by id, since #847 renames it.
     *
     * **Zero real-claude turns**: pairing and the list are daemon round-trips. B is removed in `finally`.
     */
    @Test
    fun interactiveTurn_scannerConfirm_waitsForHostThenOpensList() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val nameB = twoHostArg(ARG_COLLISION_NAME_B)
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        try {
            awaitChannelList()
            awaitConnected()
            val nameA =
                checkNotNull(heldConversationName(serverIdA, twoHostArg(ARG_COLLISION_CONVERSATION_ID))) {
                    "host A's seeded conversation has no name"
                }

            pairHostByScanner(twoHostArg(ARG_PAIR_CODE_B))
            assertNotNull("the scanner did not save host B", runBlocking { store.loadById(serverIdB) })
            awaitChannelRow(nameB)

            runBlocking { store.setDisplayName(serverIdB, HOST_B_NAME) }
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                runCatching { scrollListTo(hasContentDescription(hostEditDescription(HOST_B_NAME))) }.isSuccess
            }
            val labelA = hostLabel(serverIdA)
            val labelB = hostLabel(serverIdB)
            assertEquals(HOST_B_NAME, labelB)
            assertEachUnderOwnHost(labelB to nameB, labelA to nameA)
            assertEachUnderOwnHost(labelA to nameA, labelB to nameB)
        } finally {
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB) }
        }
    }

    /**
     * Make [serverId] the selected host the way the registry defines it: re-save its unchanged record, which
     * makes it the latest saved. The record is equal, so its connection bundle must survive the move.
     */
    private fun selectHost(
        registry: RelayConnectionRegistry,
        store: PairedServerCollectionStore,
        serverId: String,
    ) {
        val bundle = checkNotNull(registry.connectionFor(serverId)) { "host not registered" }
        val entry = checkNotNull(runBlocking { store.loadById(serverId) }) { "host not paired" }
        runBlocking {
            withTimeout(LIST_TIMEOUT_MS) {
                store.save(entry.record)
                registry.selected.first { it === bundle }
            }
        }
        assertSame("re-saving an unchanged record replaced the host's connection", bundle, registry.connectionFor(serverId))
    }

    /** Consume a completed transfer once, with no archive bytes or daemon text in failure output. */
    private fun requestArchive(
        registry: RelayConnectionRegistry,
        serverId: String,
    ): ByteArray {
        val transfer = registry.requestDebugBundle(serverId)
        val status =
            runBlocking {
                withTimeout(
                    DEBUG_BUNDLE_TIMEOUT_MS,
                ) { transfer.state.first { it.status != DebugBundleStatus.RECEIVING }.status }
            }
        assertEquals("diagnostic archive transfer status", DebugBundleStatus.COMPLETE, status)
        val archive = checkNotNull(transfer.takeArchive()) { "completed diagnostic archive was not held" }
        return ByteArrayOutputStream().also { archive.writeTo(it) }.toByteArray()
    }

    /**
     * Check [archive] is a complete daemon diagnostic archive and return its `logs.txt` as text: the gzip
     * stream reads to its end, the tar holds `manifest.json` and `logs.txt`, holds `recording.cast` exactly
     * when the manifest says one is present, and each member's size is the manifest's. Every message here
     * states a fact or a size, never archive content: the recording can be the operator's real session.
     */
    private fun completeBundleLogs(archive: ByteArray): String {
        val members = bundleMembers(archive)
        assertTrue("the archive has a member it should not", members.keys.all { it in BUNDLE_MEMBERS })
        val manifestBytes = checkNotNull(members[BUNDLE_MANIFEST]) { "the archive has no manifest.json" }
        val logs = checkNotNull(members[BUNDLE_LOGS]) { "the archive has no logs.txt" }
        val manifest = MobileJson.parseToJsonElement(String(manifestBytes, Charsets.UTF_8)) as JsonObject

        fun number(key: String): Long = checkNotNull(manifest[key]?.jsonPrimitive?.longOrNull) { "the manifest has no $key" }
        val recordingPresent =
            checkNotNull(manifest[MANIFEST_RECORDING_PRESENT]?.jsonPrimitive?.booleanOrNull) { "the manifest has no recording_present" }
        assertEquals("recording.cast present versus the manifest", recordingPresent, BUNDLE_RECORDING in members)
        assertEquals("logs.txt size versus the manifest", number(MANIFEST_LOG_BYTES), logs.size.toLong())
        assertEquals(
            "recording.cast size versus the manifest",
            number(MANIFEST_RECORDING_BYTES),
            members[BUNDLE_RECORDING]?.size?.toLong() ?: 0L,
        )
        return String(logs, Charsets.UTF_8)
    }

    /**
     * The regular-file members of a gzip-wrapped ustar archive, by name, read in memory. Reading the gzip to its
     * end checks its trailer, so a truncated or corrupt stream throws; the tar walk then requires every body to
     * fit and the zero end-of-archive block to follow the last member. Go's writer emits plain ustar headers
     * with octal sizes for everything the daemon packs.
     */
    private fun bundleMembers(archive: ByteArray): Map<String, ByteArray> {
        val tar = GZIPInputStream(archive.inputStream()).use { it.readBytes() }
        val members = mutableMapOf<String, ByteArray>()
        var at = 0
        while (true) {
            check(at + TAR_BLOCK <= tar.size) { "the archive ends before its end-of-archive block" }
            if ((at until at + TAR_BLOCK).all { tar[it] == 0.toByte() }) return members
            val name = String(tar, at, TAR_NAME_BYTES, Charsets.US_ASCII).substringBefore('\u0000')
            val size = String(tar, at + TAR_SIZE_OFFSET, TAR_SIZE_BYTES, Charsets.US_ASCII).trim(' ', '\u0000').toLong(8)
            check(tar[at + TAR_TYPE_OFFSET] == '0'.code.toByte()) { "an archive member is not a regular file" }
            val body = at + TAR_BLOCK
            check(size <= tar.size - body) { "an archive member runs past the end of the archive" }
            check(members.put(name, tar.copyOfRange(body, body + size.toInt())) == null) { "an archive member name repeats" }
            at = body + ((size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK).toInt()
        }
    }

    /**
     * Each host's Archive stays its own with two live hosts paired (#1086, rung 3).
     *
     * **Archive.** One of host A's chats is archived from its thread and restored from A's Archive, reached
     * through A's list header menu. B's full list and B's archived list are read before and compared after each step.
     *
     * **Cleanup.** Both created chats are deleted and B's pairing is removed in `finally`.
     *
     * **Zero real-claude turns**: pairing, chat creation, rename, archive and restore are daemon round-trips.
     */
    @Test
    fun interactiveTurn_twoHostsArchive_staysPerHost() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val beforeByHost = mutableMapOf<String, Set<String>>()
        val createdByHost = mutableMapOf<String, String>()
        try {
            // 1. Pair host B by code, as #847 does.
            awaitChannelList()
            awaitConnected()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
            pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))

            // 2. Create a uniquely named chat on A and a second chat on B.
            val chatName = TWO_HOST_ARCHIVE_CHAT_PREFIX + System.currentTimeMillis()
            beforeByHost[serverIdA] = hostConversationIds(serverIdA)
            val chatA = createChatOn(serverIdA)
            createdByHost[serverIdA] = chatA
            renameOpenThread(chatName)
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            awaitListText(chatName)

            beforeByHost[serverIdB] = hostConversationIds(serverIdB)
            val chatB = createChatOn(serverIdB)
            createdByHost[serverIdB] = chatB
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()

            // 3. B's active and archived baselines stay independent of A's archive changes.
            val archivedB = archivedIds(serverIdB) { true }
            val activeB = hostConversationIds(serverIdB) - archivedB
            assertTrue("B's new chat is missing from its active set", chatB in activeB)

            // 4. Archive A's chat from its thread. It leaves A's active list and joins A's archive.
            awaitListText(chatName)
            composeTestRule.onAllNodesWithText(chatName, substring = true).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(ARCHIVE_ITEM).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(ARCHIVE_ITEM).performClick()
            awaitChannelList()
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                runCatching { scrollListTo(hasText(chatName, substring = true)) }.isFailure
            }
            archivedIds(serverIdA) { chatA in it }
            val archivedBAfterArchive = archivedIds(serverIdB) { true }
            assertEquals("archiving A's chat changed B's archive", archivedB, archivedBAfterArchive)
            assertEquals("archiving A's chat changed B's active list", activeB, hostConversationIds(serverIdB) - archivedBAfterArchive)

            // 5. Select A for the list header menu's host-scoped Archive, then restore after observing the row.
            val koin = GlobalContext.get()
            selectHost(koin.get(), koin.get(), serverIdA)
            openListMenuEntry(R.string.thread_overflow_archive)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            openArchiveTab(R.string.archived_tab_discussions)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(chatName, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodes(hasContentDescription(chatName, substring = true)).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(RESTORED_SNACKBAR, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()

            // 6. A's chat is back on the active list and out of the archive; B is still unchanged.
            awaitListText(chatName)
            hostConversationIds(serverIdA, "the restored chat", ConversationFilter.Discussions) { chatA in it }
            archivedIds(serverIdA) { chatA !in it }
            val archivedBAfterRestore = archivedIds(serverIdB) { true }
            assertEquals("restoring A's chat changed B's archive", archivedB, archivedBAfterRestore)
            assertEquals("restoring A's chat changed B's active list", activeB, hostConversationIds(serverIdB) - archivedBAfterRestore)
        } finally {
            beforeByHost.forEach { (serverId, before) ->
                cleanupCreatedConversation(serverId, before, createdByHost[serverId], "two-host archive chat cleanup failed")
            }
            runCatching {
                runBlocking {
                    withTimeout(THREAD_TIMEOUT_MS) {
                        GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB)
                    }
                }
            }.onFailure { Log.w("E2E", "two-host archive pairing cleanup failed: ${it::class.simpleName}") }
        }
    }

    /** Wait until the open modal's OK is enabled, then tap it. */
    private fun clickEnabledOk() {
        val ok = hasText(OK_BUTTON) and isEnabled() and hasClickAction()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(ok).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(ok).onFirst().performClick()
    }

    /**
     * A channel created from the list, edited and archived from its thread, with its prompt read back (#1088,
     * rung 3). A host's initially empty Channels-section plus opens Create channel, whose OK creates the channel
     * with a name and a prompt and opens it. One ping starts its session with that prompt. The thread menu's Edit
     * (#1561, #1563) opens #667's Edit channel, which renames it and changes the prompt; reopened, it reads both back from the host and
     * says the prompt applies from the next session, since the running one was spawned with the old prompt.
     * After Reset session and a distinct second reply, a session spawned after the edit runs, and the reopened modal
     * drops that line. Emptying the box there clears the stored prompt (#1342), which Channel info's System prompt
     * section then reads back as absent. Archive channel moves it to host A's Archive, and restoring it returns it to Channels
     * under its new name.
     *
     * A throwaway unpromoted chat created with omitted `cwd` supplies the daemon's actual default folder for
     * comparison. The live harness seeds a promoted collision row, so this test temporarily archives the
     * host's existing channels to exercise an empty section. **Shared host state.** Those channels are restored,
     * both new conversations are deleted in `finally`.
     *
     * **Two real-claude turns**: the initial ping and the post-reset pong. Reset session also runs the daemon's wrap-up turn.
     */
    @Test
    fun interactiveTurn_createEditArchiveChannel_readsPromptBack() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val stamp = System.currentTimeMillis()
        val firstName = "${CHANNEL_E2E_PREFIX}$stamp-a"
        val newName = "${CHANNEL_E2E_PREFIX}$stamp-b"
        val nextSessionLine = string(R.string.edit_channel_prompt_next_session)
        var channelId: String? = null
        var defaultProbeId: String? = null
        val archivedFixtureIds = mutableListOf<String>()
        try {
            awaitChannelList()
            awaitConnected()
            val originalChannelIds =
                hostConversationIds(serverId, "the harness's seeded channel", ConversationFilter.Channels) {
                    twoHostArg(ARG_COLLISION_CONVERSATION_ID) in it
                }
            originalChannelIds.forEach { id ->
                runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).archive(id) } }
                archivedFixtureIds.add(id)
            }
            hostConversationIds(serverId, "an empty Channels section", ConversationFilter.Channels) { it.isEmpty() }
            val daemonDefault = runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).createDiscussion(null) } }
            defaultProbeId = daemonDefault.id
            val before = hostConversationIds(serverId, "the default-folder probe") { daemonDefault.id in it }

            // 1. The empty Channels section creates in the host's default working folder.
            val plus = hasTestTag(treeHostChannelAddTestTag(serverId))
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                runCatching { scrollListTo(plus) }.isSuccess
            }
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG)).fetchSemanticsNodes().isEmpty()
            }
            composeTestRule.onAllNodes(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG)).assertCountEquals(0)
            composeTestRule.onNode(plus).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(string(R.string.create_channel_title)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextInput(firstName)
            composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextInput(CHANNEL_PROMPT_FIRST)
            clickEnabledOk()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val id = newHostConversationId(serverId, before).also { channelId = it }
            val createdCwd = heldConversation(serverId, id).cwd
            assertEquals("the channel did not use the daemon default", daemonDefault.cwd, createdCwd)

            // 2. AC-1: one ping starts the channel's session with the first prompt.
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            leaveThread()

            // 3. AC-1: rename it and change its prompt. The field first reads back the prompt the create wrote.
            openChannelEditor(firstName)
            awaitPromptField(CHANNEL_PROMPT_FIRST)
            composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextReplacement(newName)
            composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextReplacement(CHANNEL_PROMPT_SECOND)
            composeTestRule.onNodeWithText(EDIT_CHANNEL_OK).performClick()
            awaitChannelEditorClosed()
            leaveThread()

            // 4. AC-1: reopened, it reads back the new name and prompt, and the running session still has the old one.
            awaitChannelRow(newName)
            composeTestRule.onAllNodes(channelRow(firstName)).assertCountEquals(0)
            openChannelEditor(newName)
            awaitPromptField(CHANNEL_PROMPT_SECOND)
            composeTestRule.onNode(hasTestTag(CHANNEL_NAME_FIELD_TAG) and hasText(newName)).assertExists()
            composeTestRule.onAllNodesWithText(nextSessionLine).onFirst().assertIsDisplayed()
            composeTestRule.onNodeWithText(modalCancel).performClick()
            awaitChannelEditorClosed()
            leaveThread()

            // 5. AC-2: Reset session, then a distinct reply. Whether the respawn was eager or the send spawned the
            //    session, the host reports it runs with the stored prompt once one started after the edit.
            openRow(newName)
            composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(NEW_SESSION_ITEM).onFirst().performClick()
            composeTestRule.awaitDisplayedSessionBoundary(REPLY_TIMEOUT_MS)
            sendFromPhone("Reply with exactly the word: pong (nothing else).")
            val secondReply =
                composeTestRule.onNode(
                    hasText("pong", ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG)),
                    useUnmergedTree = true,
                )
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { secondReply.isDisplayed() }
            secondReply.assertIsDisplayed()
            val live = hostRepository(serverId)
            runBlocking {
                withTimeout(REPLY_TIMEOUT_MS) {
                    while (live.requestSystemPrompt(id).sessionPromptStatus != SessionPromptStatus.Matches) {
                        delay(PROMPT_STATUS_POLL_MS)
                    }
                }
            }
            leaveThread()

            // 6. AC-2: reopened, the same prompt without the next-session line. The filled field is the reading.
            openChannelEditor(newName)
            awaitPromptField(CHANNEL_PROMPT_SECOND)
            composeTestRule.onAllNodesWithText(nextSessionLine).assertCountEquals(0)

            // 6b. #1342: emptying the box clears the stored prompt (null, not ""), as desktop's promptWriteFor does.
            composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextReplacement("")
            composeTestRule.onNodeWithText(EDIT_CHANNEL_OK).performClick()
            awaitChannelEditorClosed()
            leaveThread()
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    while (hostRepository(serverId).requestSystemPrompt(id).systemPrompt != null) delay(PROMPT_STATUS_POLL_MS)
                }
            }

            // 6c. #1342: Channel info reads the prompt on open and shows it absent: an empty box at zero bytes.
            openRow(newName)
            composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodesWithText(CHANNEL_INFO_ITEM).onFirst().performClick()
            val emptyBox = hasTestTag(CHANNEL_INFO_PROMPT_FIELD_TAG) and hasText("")
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(emptyBox).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(emptyBox).performScrollTo()
            composeTestRule.onNode(hasText("0 / 8192 bytes") and hasAnyAncestor(isDialog())).performScrollTo()
            composeTestRule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_INFO_PROMPT_FIELD_TAG)).fetchSemanticsNodes().isEmpty()
            }
            leaveThread()

            // The archive below starts from Edit channel again, which now reads an empty box.
            openChannelEditor(newName)
            awaitPromptField("")

            // 7. AC-3: archive it from the same modal. The thread returns to the list (#1561), and the channel
            //    leaves Channels for host A's Archive.
            composeTestRule.onNode(hasText(string(R.string.edit_channel_archive)) and hasClickAction()).performClick()
            awaitChannelEditorClosed()
            awaitChannelList()
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                runCatching { scrollListTo(channelRow(newName)) }.isFailure
            }
            archivedIds(serverId) { id in it }

            // 8. Restore it from the selected host's Archive through the list header menu. The snackbar wait
            //    keeps the restore coroutine from being cancelled by the Back that follows (#551).
            val koin = GlobalContext.get()
            selectHost(koin.get(), koin.get(), serverId)
            openListMenuEntry(R.string.thread_overflow_archive)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(ARCHIVED_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            openArchiveTab(R.string.archived_tab_channels)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(newName, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodes(hasContentDescription(newName, substring = true)).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(RESTORED_SNACKBAR, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            awaitChannelRow(newName)
            composeTestRule.onAllNodes(channelRow(firstName)).assertCountEquals(0)
        } finally {
            listOfNotNull(channelId, defaultProbeId).forEach { id ->
                runCatching { runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).delete(id) } } }
                    .onFailure { Log.w("E2E", "channel cleanup failed: ${it::class.simpleName}") }
            }
            archivedFixtureIds.forEach { id ->
                runCatching { runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).unarchive(id) } } }
                    .onFailure { Log.w("E2E", "channel fixture restore failed: ${it::class.simpleName}") }
            }
        }
    }

    /**
     * Open the Channels row named [name] and choose Edit from its thread's menu (#1561), the only way into Edit
     * channel once the list's rows draw no pen (#1563), then wait for Edit channel's title. The thread stays open
     * behind the modal.
     */
    private fun openChannelEditor(name: String) {
        val edit = string(R.string.thread_overflow_edit)
        openRow(name)
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(edit).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(edit).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(string(R.string.edit_channel_title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Wait until Edit channel closes, which it does only once the host confirmed every write. */
    private fun awaitChannelEditorClosed() {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(string(R.string.edit_channel_title)).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Wait until Edit channel's prompt field is enabled and holds [prompt]: the host's reading has arrived. */
    private fun awaitPromptField(prompt: String) {
        val field = hasTestTag(CHANNEL_PROMPT_FIELD_TAG) and hasText(prompt) and isEnabled()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A turn started from another client continues on the phone (#848, rung 3). A [SecondClientPeer] —
     * a second paired device on host A with its own token ([ARG_PEER_TOKEN]) and key, standing in for the
     * desktop — sends the constrained ping prompt into a chat the phone has open.
     *
     * The daemon carries no live frame with another device's message text: the turn's stream frames fan
     * out to every interactive connection, and the message text reaches the phone through history. So the
     * checks are what the operator sees, however delivered:
     *  * **while open** — claude's reply renders in the thread exactly once;
     *  * **after leaving and reopening** — the peer's message and the reply each render exactly once.
     *
     * The chat is renamed to a run-unique name before any message is sent, so the daemon's first-message
     * auto-naming never fires (a name set by rename is never overwritten) and the row can be found again.
     * The prompt count is read inside the thread's scrollable list, where a delivered bubble and an inline
     * queued row both live, so a message drawn once as each would count twice; the top bar is outside it.
     *
     * **One real-claude turn**: the peer's ping.
     */
    @Test
    fun interactiveTurn_peerStartedTurn_continuesOnPhone() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The phone creates a chat and is in its thread; the new id is the one it did not hold before.
            awaitChannelList()
            awaitConnected()
            val before = hostConversationIds(serverId)
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId = newHostConversationId(serverId, before)
            val chatName = PEER_CHAT_NAME_PREFIX + System.currentTimeMillis()
            renameOpenThread(chatName)

            // 2. AC-1: the peer, as its own device, sends into that conversation and observes its frames.
            peerStep(peer, "open") { peer.open(CONNECT_TIMEOUT_MS) }
            peerStep(peer, "send the ping and await its ack") { peer.sendMessage(conversationId, PING_PROMPT, THREAD_TIMEOUT_MS) }
            peerStep(peer, "await the ping turn's turn_end") { peer.awaitFrame(conversationId, "turn_end", REPLY_TIMEOUT_MS) }

            // 3. AC-2: with the thread open, claude's reply renders there once.
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)

            // 4. AC-3: leave, reopen, and read the message and the reply once each.
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            val chatRow = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(chatName, substring = true)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) { runCatching { scrollListTo(chatRow) }.isSuccess }
            composeTestRule.onAllNodes(chatRow).onFirst().performClick()
            val peerMessage = hasText(PING_PROMPT) and hasAnyAncestor(hasScrollToNodeAction())
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(peerMessage, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(peerMessage, useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)
        } finally {
            peer.close()
        }
    }

    /** #1642: a harness release-file holds Bash until Send now really reaches the daemon. */
    @Test
    fun interactiveTurn_sendQueuedNow_reachesRunningTurn() {
        val args = InstrumentationRegistry.getArguments()
        val release = twoHostArg("sendNowReleasePath")
        require(release.matches(Regex("/[A-Za-z0-9_./-]+"))) { "invalid harness release path" }
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            awaitChannelList()
            awaitConnected()
            val before = hostConversationIds(serverId)
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId = newHostConversationId(serverId, before)
            // Warm the session and its explicit capability reading before starting the held turn.
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            assertTrue("session must report mid-turn support", freshSettings(conversationId).capabilities?.midTurnInput == true)
            val marker = "sendnow1642_" + System.currentTimeMillis()
            val queuedPrompt = "In your final reply to this running turn include exactly this marker: $marker"
            val command = "while [ ! -e '$release' ]; do sleep 0.1; done; printf hold_done"
            val prompt =
                "Run exactly this Bash command with timeout 120000, in the foreground, never background it: $command. " +
                    "Wait for its result. Then reply with any marker supplied while the command was running."
            runBlocking {
                peer.open(CONNECT_TIMEOUT_MS)
                peer.sendMessage(conversationId, prompt, THREAD_TIMEOUT_MS)
            }
            val allowed = mutableSetOf<String>()
            val running =
                allowPromptsUntil(
                    peer,
                    conversationId,
                    REPLY_TIMEOUT_MS,
                    "Bash did not remain running",
                    allowed,
                    frame = "tool_progress",
                ) { it.type == "tool_progress" }
            val turnId = requireNotNull(peer.field(running, "turn_id"))
            assertTrue("held turn ended before queueing", peer.recorded(conversationId).none { it.type == "turn_end" })
            sendFromPhone(queuedPrompt)
            awaitQueuedRow(queuedPrompt)
            val queued =
                runBlocking {
                    peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { q -> q.any { it.text == queuedPrompt } }
                }.single { it.text == queuedPrompt }
            val send = hasContentDescription("Send now") and hasAnyAncestor(queuedRow(queuedPrompt))
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(send).fetchSemanticsNodes().isNotEmpty() }
            composeTestRule.onNode(send).performClick()
            runBlocking { peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { q -> q.none { it.queuedMsgId == queued.queuedMsgId } } }
            val ended =
                allowPromptsUntil(
                    peer,
                    conversationId,
                    REPLY_TIMEOUT_MS,
                    "held turn did not finish with the marker",
                    allowed,
                ) { it.type == "turn_end" }
            assertEquals(turnId, peer.field(ended, "turn_id"))
            val frames = peer.recorded(conversationId)
            assertEquals("Send now must stay in the running turn", 1, frames.count { it.type == "turn_end" })
            val resultIndex = frames.indexOfLast { it.type == "tool_result" }
            val messageIndex = frames.indexOfFirst { it.type == "message" && peer.field(it, "message_id") == queued.messageId }
            assertTrue("delivered user push must follow Bash result", resultIndex >= 0 && messageIndex > resultIndex)
            val finalText =
                frames
                    .filter { it.type == "assistant_delta" && peer.field(it, "turn_id") == turnId }
                    .joinToString("") { peer.field(it, "text").orEmpty() }
            assertTrue("the same turn's reply omitted the marker", marker in finalText)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(inThreadList(queuedPrompt), useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                    composeTestRule.onAllNodes(queuedRow(queuedPrompt)).fetchSemanticsNodes().isEmpty()
            }
            assertDrawnOnce(inThreadList(queuedPrompt))
            val rows =
                runBlocking { hostRepository().observeMessages(conversationId).first() }
                    .filterIsInstance<ThreadItem.MessageItem>()
            val toolIndex = rows.indexOfLast { it.message.role == Role.Tool }
            val userIndex = rows.indexOfFirst { it.message.id == queued.messageId }
            assertEquals(1, rows.count { it.message.id == queued.messageId })
            assertTrue("phone placed the user row before Bash result", toolIndex >= 0 && userIndex > toolIndex)
        } finally {
            peer.close()
        }
    }

    /**
     * Phone replies, queued sends and drops stay consistent with another client (#849, rung 3). In a
     * conversation the [SecondClientPeer] starts, the peer's opening turn has claude run a shell command
     * ([WAIT_PROMPT]) that needs permission. No device answers until the queue steps are done, so the
     * pending prompt holds the turn open for them, with no timing involved:
     *  * the phone's [PING_PROMPT] queues — a queued row on the phone and an item in the peer's snapshot;
     *  * the phone queues and drops [DROP_PROMPT] — gone from both backlogs and from the phone's thread;
     *  * the peer queues and drops [PEER_QUEUED_PROMPT] — a plain queued row on the phone until then.
     * The peer, paired with `--allow-remote-permissions`, then allows the command once. The turn ends and
     * the ping drains: claude's reply renders once on the phone, the prompt draws once,
     * the peer sees that turn's `turn_end`, and both backlogs are empty — so neither dropped message can
     * still reach claude, and neither one's reply token is ever drawn.
     *
     * Every backlog check on the peer reads the latest `queue_state`, which fans out to every interactive
     * connection. The prompt counts use #848's list matcher, so a message drawn once as a bubble and once
     * as a queued row counts twice.
     *
     * **Two real-claude turns**: the peer's wait turn and the drained ping.
     */
    @Test
    fun interactiveTurn_peerQueue_staysConsistentAcrossClients() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The phone creates and renames a chat, as #848 does; the peer joins as its own device.
            awaitChannelList()
            awaitConnected()
            val before = hostConversationIds(serverId)
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId = newHostConversationId(serverId, before)
            renameOpenThread(QUEUE_CHAT_NAME_PREFIX + System.currentTimeMillis())

            // 2. The peer starts the conversation with a turn that stops on a permission prompt. The prompt
            //    stays outstanding until step 6, so every queue step runs while that turn is still open.
            val permissionModalId =
                runBlocking {
                    peer.open(CONNECT_TIMEOUT_MS)
                    peer.sendMessage(conversationId, WAIT_PROMPT, THREAD_TIMEOUT_MS)
                    peer.awaitPermissionModal(conversationId, REPLY_TIMEOUT_MS)
                }

            // 3. AC-2: the phone's message queues behind it, on the phone and in the peer's snapshot.
            sendFromPhone(PING_PROMPT)
            awaitQueuedRow(PING_PROMPT)
            runBlocking { peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.any { it.text == PING_PROMPT } } }

            // 4. AC-3: a message the phone queues and drops leaves both backlogs and the phone's thread; the
            //    ping stays queued.
            sendFromPhone(DROP_PROMPT)
            awaitQueuedRow(DROP_PROMPT)
            runBlocking { peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.any { it.text == DROP_PROMPT } } }
            val dropControl = hasContentDescription(queuedDropDescription) and hasAnyAncestor(queuedRow(DROP_PROMPT))
            scrollListTo(dropControl)
            composeTestRule.onNode(dropControl).performClick()
            awaitGoneFromThread(DROP_PROMPT)
            runBlocking {
                peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue ->
                    queue.none { it.text == DROP_PROMPT } && queue.any { it.text == PING_PROMPT }
                }
            }

            // 5. AC-3: a message the peer queues is a plain queued row on the phone until the peer drops it.
            val peerItem =
                runBlocking {
                    peer.sendMessage(conversationId, PEER_QUEUED_PROMPT, THREAD_TIMEOUT_MS)
                    peer
                        .awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.any { it.text == PEER_QUEUED_PROMPT } }
                        .first { it.text == PEER_QUEUED_PROMPT }
                }
            awaitQueuedRow(PEER_QUEUED_PROMPT)
            peer.dequeueMessage(conversationId, peerItem.queuedMsgId)
            awaitGoneFromThread(PEER_QUEUED_PROMPT)
            runBlocking {
                peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.none { it.text == PEER_QUEUED_PROMPT } }
            }

            // 6. AC-1 / AC-2: the peer allows the command, the turn ends, and the ping drains and runs; the
            //    peer sees that turn end and an empty backlog, and the phone draws the reply and the prompt
            //    once each, no longer queued.
            runBlocking {
                peer.allowOnce(permissionModalId, THREAD_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS, occurrence = 2)
                peer.awaitQueue(conversationId, THREAD_TIMEOUT_MS) { queue -> queue.isEmpty() }
            }
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodes(inThreadList(PING_PROMPT), useUnmergedTree = true).assertCountEquals(1)
            composeTestRule.onAllNodes(queuedRow(PING_PROMPT)).assertCountEquals(0)

            // 6b. #1558: the drained ping sits where claude received it — below the wait turn's last row, its
            //     reply, and above the ping's own reply — not at the slot it was typed into mid-turn.
            val waitReply = hasText(WAIT_REPLY, ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(waitReply, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val tops =
                listOf(waitReply, inThreadList(PING_PROMPT), pingReplyMatcher()).map {
                    composeTestRule
                        .onNode(it, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
            assertTrue("expected wait reply, ping prompt, ping reply top to bottom; tops $tops", tops == tops.sorted())
            assertTrue("two of the messages share a row; tops $tops", tops.distinct().size == tops.size)

            // 7. AC-3: with the backlog empty nothing dropped can run, and no dropped reply was ever drawn.
            composeTestRule.onAllNodes(inThreadList(DROP_PROMPT), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(inThreadList(PEER_QUEUED_PROMPT), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText(DROP_REPLY), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText(PEER_QUEUED_REPLY), useUnmergedTree = true).assertCountEquals(0)
        } finally {
            peer.close()
        }
    }

    /**
     * The composer's Stop control stops a real running turn, and the conversation carries on (#965, rung 3).
     * The phone's own turn runs [STOP_HOLD_PROMPT]: a command that waits on an event nothing sets, so it
     * never returns on its own and the turn stays open until it is stopped, with no timing involved. The
     * command first raises a permission prompt, which the phone draws as a dialog over the composer; the
     * [SecondClientPeer], paired with `--allow-remote-permissions`, allows it once so the dialog is gone and
     * the Stop control is reachable the way an operator would reach it.
     *
     * After the tap the turn ends as cancelled (the peer's `turn_end`), the Stop control goes, and a ping sent
     * in the same thread gets claude's real reply. The held turn's reply token is never drawn, because the
     * stopped turn never finished. Since #1357 a cancelled turn puts no notice in the status area, so the
     * method's name outlives the Interrupted label it once asserted.
     *
     * **Two real-claude turns**: the stopped turn and the ping.
     */
    @Test
    fun interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val pairing =
            PairedServer(
                serverId = serverId,
                token = twoHostArg(ARG_PEER_TOKEN),
                relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
            )
        val peer = SecondClientPeer(pairing)
        val stopControl = hasContentDescription(string(R.string.cd_thread_interrupt))
        try {
            // 1. A fresh chat on the selected host, its id read off the host's repository as #849 does.
            awaitChannelList()
            awaitConnected()
            val before = hostConversationIds(serverId)
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId = newHostConversationId(serverId, before)

            // 2. The phone starts the held turn; the peer allows its command once, so the command runs and
            //    the permission dialog leaves the composer.
            // Bind the token through a prior peer, as earlier full-suite scenarios do (#1696).
            // A new static key on the observing peer must fail even when this method runs alone.
            SecondClientPeer(pairing).use { prior ->
                peerStep(prior, "open prior peer") { prior.open(CONNECT_TIMEOUT_MS) }
            }
            peerStep(peer, "open") { peer.open(CONNECT_TIMEOUT_MS) }
            sendFromPhone(STOP_HOLD_PROMPT)
            val modalId =
                peerStep(peer, "await the held command's permission prompt") { peer.awaitPermissionModal(conversationId, REPLY_TIMEOUT_MS) }
            peerStep(peer, "allow the prompt once and await its dismissal") { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }

            // 3. Tap the composer's Stop control once no dialog covers it.
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText(modalCancel)).fetchSemanticsNodes().isEmpty() &&
                    composeTestRule.onAllNodes(stopControl).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(stopControl).performClick()

            // 4. AC-1: the turn ends as cancelled and the Stop control goes.
            val turnEnd =
                peerStep(peer, "await the stopped turn's turn_end") { peer.awaitFrame(conversationId, "turn_end", THREAD_TIMEOUT_MS) }
            assertEquals(
                "stopped turn's stop_reason",
                "cancelled",
                (turnEnd.payload as? JsonObject)?.get("stop_reason")?.jsonPrimitive?.contentOrNull,
            )
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(stopControl).fetchSemanticsNodes().isEmpty()
            }

            // 5. AC-1: a following message in the same thread gets a real reply, and that turn is the
            //    conversation's second; the stopped turn's own reply was never drawn.
            sendFromPhone(PING_PROMPT)
            awaitPingReplyNamingLayer(peer, serverId, conversationId, priorTurnEnds = 1)
            peerStep(
                peer,
                "await the ping turn's turn_end",
            ) { peer.awaitFrame(conversationId, "turn_end", THREAD_TIMEOUT_MS, occurrence = 2) }
            composeTestRule.onAllNodes(hasText(STOP_HOLD_REPLY, ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)
        } finally {
            peer.close()
        }
    }

    /**
     * A loaded conversation stays readable offline and catches up on reconnect (#850, rung 3; the live
     * proof #795–#798 deferred). The phone loads a chat's history with one ping turn, then cuts its own
     * link to the host as [setHostLink] does. With the connection-scoped repository gone, readability can
     * only come from retained content:
     *  * **offline** — the open thread still draws the ping and its reply, the chat's row is still in the
     *    list, and reopening the row draws both again (the on-disk thread restore, not the in-memory rows);
     *  * **meanwhile** — the [SecondClientPeer] sends [OFFLINE_PROMPT] and its turn ends, and the phone
     *    draws none of it, which is what shows it really was offline;
     *  * **reconnected** — the still-open thread draws the peer's prompt and reply from the reconnect's ring
     *    replay with no pull, that turn after the ping, and each of the four messages once. The daemon pushes
     *    each delivered user message live and into the replay ring (pyrycode#2699), and the phone draws it
     *    (#1351); a reconnect no longer asks for history (#1352).
     *
     * The cut waits until the phone itself has settled the ping reply — its thread cache holds it, which
     * the open thread's collector writes only after drawing the settled row. A disconnect keeps only settled
     * rows, and the peer's copy of `turn_end` can arrive before the phone's, so waiting on the peer alone
     * could cut while the phone's reply still streamed and drop it by design.
     *
     * **Two real-claude turns**: the phone's ping and the peer's offline turn.
     */
    @Test
    fun interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect() {
        val args = InstrumentationRegistry.getArguments()
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = twoHostArg(ARG_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
                ),
            )
        try {
            // 1. The peer records frames from here on; the phone creates and renames a chat, as #848 does.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val before = hostConversationIds(serverId)
            createChat()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
            }
            val conversationId = newHostConversationId(serverId, before)
            val chatName = OFFLINE_CHAT_NAME_PREFIX + System.currentTimeMillis()
            renameOpenThread(chatName)

            // 2. Load history: the phone's ping turn renders and ends. The peer's turn_end keeps the later
            //    occurrence count right; the phone's own cache is what shows its rows settled before the cut.
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            runBlocking { peer.awaitFrame(conversationId, "turn_end", REPLY_TIMEOUT_MS) }
            awaitCachedAssistantReply(serverId, conversationId)

            // 3. AC-1: cut the phone's link. The open thread keeps what it drew.
            setHostLink(serverId, up = false)
            composeTestRule.waitForIdle()
            assertDrawnOnce(inThreadList(PING_PROMPT), pingReplyMatcher())

            // 4. AC-1: the chat's row is still listed, and reopening it offline draws the history again.
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitChannelList()
            val chatRow = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(chatName, substring = true)
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) { runCatching { scrollListTo(chatRow) }.isSuccess }
            composeTestRule.onAllNodes(chatRow).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(inThreadList(PING_PROMPT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.awaitDisplayedPingReply(THREAD_TIMEOUT_MS)
            assertDrawnOnce(inThreadList(PING_PROMPT), pingReplyMatcher())

            // 5. AC-2: while the phone is offline the peer's turn runs to its end; the phone draws none of it.
            runBlocking {
                peer.sendMessage(conversationId, OFFLINE_PROMPT, THREAD_TIMEOUT_MS)
                peer.awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS, occurrence = 2)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onAllNodes(inThreadList(OFFLINE_PROMPT), useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(offlineReplyMatcher(), useUnmergedTree = true).assertCountEquals(0)

            // 6. AC-2: reconnect with the thread open; the ring replay brings the peer's prompt and reply into it,
            //    with no pull. The reconnect itself asks for no history (#1352).
            setHostLink(serverId, up = true)
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onNode(offlineReplyMatcher(), useUnmergedTree = true).isDisplayed() &&
                    composeTestRule.onAllNodes(inThreadList(OFFLINE_PROMPT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitForIdle()
            assertDrawnOnce(inThreadList(PING_PROMPT), pingReplyMatcher(), inThreadList(OFFLINE_PROMPT), offlineReplyMatcher())
            val tops =
                listOf(pingReplyMatcher(), inThreadList(OFFLINE_PROMPT), offlineReplyMatcher()).map {
                    composeTestRule
                        .onNode(it, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
            assertTrue("expected ping reply, offline prompt, offline reply top to bottom; tops $tops", tops == tops.sorted())
            assertTrue("two of the messages share a row; tops $tops", tops.distinct().size == tops.size)
        } finally {
            peer.close()
        }
    }

    /**
     * A reply that ends while its chat is off screen survives a reconnect (#1581, rung 3; #1572's live proof). Chat
     * A has a settled ping turn the phone drew and cached. A's second turn, [WAIT_PROMPT], is held on the #849
     * permission lever while the phone leaves A for B, so A never draws its reply however fast claude is. The peer
     * allows it and the turn ends with B on screen. Rows that reach a closed thread live only in the connection's
     * projection, never the thread cache, and a reconnect discards them with the replay cursor already past them.
     *  * **Off screen** — the phone itself folded A's `turn_end` while A was not viewed: its stored read position
     *    names that turn as completed and unread. Waiting on the phone, not the peer's copy, keeps the cut after the
     *    phone had the turn, or the reconnect's ring replay would deliver it and the test would prove nothing.
     *  * **Not cached** — after the cut and restore, A's thread cache holds the ping's reply and not [WAIT_REPLY].
     *  * **Recovered** — opening A, with no other gesture, draws the reply from the open's newest-page ask (#1572),
     *    and the ping, its reply, [WAIT_PROMPT] and [WAIT_REPLY] each once, top to bottom.
     *
     * **Two real-claude turns**: A's ping and A's held command.
     */
    @Test
    fun interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val waitReply = hasText(WAIT_REPLY, ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
        try {
            // 1. Two chats, neither messaged; the peer records frames from here on.
            awaitChannelList()
            awaitConnected()
            val (chatA, nameA) = answerChat(serverId, OFFSCREEN_CHAT_NAME_PREFIX + "a-")
            val (_, nameB) = answerChat(serverId, OFFSCREEN_CHAT_NAME_PREFIX + "b-")
            // Bind the token first, so identity reuse is checked even when selected alone (#1692).
            runningToolPeer().use { prior ->
                peerStep(prior, "open prior offscreen peer") { prior.open(CONNECT_TIMEOUT_MS) }
            }
            peerStep(peer, "open offscreen peer") { peer.open(CONNECT_TIMEOUT_MS) }

            // 2. A's first turn renders, ends and is cached by the open thread.
            openChatRow(nameA)
            sendFromPhone(PING_PROMPT)
            awaitPingReplyNamingLayer(peer, serverId, chatA, priorTurnEnds = 0)
            awaitTurnEnd(peer, chatA, 1, "A's ping")
            awaitCachedAssistantReply(serverId, chatA)

            // 3. AC-2: A's second turn waits on its permission prompt; A leaves before any of its reply exists.
            sendFromPhone(WAIT_PROMPT)
            val modalId = peerStep(peer, "await A's permission prompt") { peer.awaitPermissionModal(chatA, REPLY_TIMEOUT_MS) }
            composeTestRule.onAllNodes(waitReply, useUnmergedTree = true).assertCountEquals(0)
            leaveThread()
            openChatRow(nameB)
            assertShowingThread(nameB, nameA)

            // 4. AC-2: released with B open, A's turn ends; the phone folds that turn_end while B is still shown.
            peerStep(peer, "allow A's prompt") { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            val turnEnd =
                peerStep(peer, "await A's held turn_end") { peer.awaitFrame(chatA, "turn_end", WAIT_TURN_TIMEOUT_MS, occurrence = 2) }
            awaitUnreadCompletion(serverId, chatA, checkNotNull(peer.field(turnEnd, "turn_id")) { "A's turn_end has no turn_id" })
            assertShowingThread(nameB, nameA)
            composeTestRule.onAllNodes(waitReply, useUnmergedTree = true).assertCountEquals(0)

            // 5. AC-2: still in B, cut and restore the link; A's cache never received the reply.
            setHostLink(serverId, up = false)
            setHostLink(serverId, up = true)
            assertNoCachedReply(serverId, chatA, WAIT_REPLY)

            // 6. AC-1: opening A is the only gesture; its newest-page ask brings the reply, every row once and in order.
            leaveThread()
            openChatRow(nameA)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(waitReply, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitForIdle()
            val rows = listOf(inThreadList(PING_PROMPT), pingReplyMatcher(), inThreadList(WAIT_PROMPT), waitReply)
            assertDrawnOnce(*rows.toTypedArray())
            val tops =
                rows.map {
                    composeTestRule
                        .onNode(it, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
            assertTrue("expected ping, its reply, the held prompt and its reply top to bottom; tops $tops", tops == tops.sorted())
            assertTrue("two of the messages share a row; tops $tops", tops.distinct().size == tops.size)
        } finally {
            peer.close()
        }
    }

    /** The foreground thread's real Offline pill retries its owning daemon after six failed dials (#1286). */
    @Test
    fun interactiveTurn_offlineRetry_reconnectsSameHostAndReplies() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId))
        val fault = DaemonFaultControl()
        awaitChannelList()
        awaitConnected()
        val before = hostConversationIds(serverId)
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        val conversationId = newHostConversationId(serverId, before)
        try {
            val retryDeadline =
                fault.stopUntilRetryWindow(bundle.supervisor) {
                    composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                        composeTestRule.onAllNodes(hasTestTag("offline_retry_target")).fetchSemanticsNodes().isNotEmpty()
                    }
                    composeTestRule.onNodeWithTag("offline_retry_target").assertIsDisplayed()
                }
            assertNull("the old host repository survived the daemon failure", bundle.coordinator.currentRepository.value)

            // The deadline began at the sixth failed dial, before daemon startup and the tap.
            // Recovery must beat the earliest passive dial, even if startup consumes most of the wait.
            fault.start()
            composeTestRule.onNodeWithTag("offline_retry_target").assertIsDisplayed()
            assertNull(bundle.coordinator.currentRepository.value)
            composeTestRule.onNodeWithTag("offline_retry_target").performClick()
            runBlocking {
                withTimeout(fault.recoveryTimeRemaining(retryDeadline)) {
                    bundle.coordinator.currentRepository.first { it != null }
                }
            }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag("offline_retry_target")).fetchSemanticsNodes().isEmpty()
            }
            assertTrue("the thread's conversation moved off its original host", conversationId in hostConversationIds(serverId))
            composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).assertIsDisplayed()
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
        } finally {
            runCatching { fault.start() }
        }
    }

    /**
     * A model change made on the phone reaches only its own conversation and survives a reopen (#545), and
     * an inherited chat marks the model claude announced rather than the `default` row's resolution
     * (#1308). Two chats are prepared through the host's own repository: `create_conversation` binds a
     * session, so a chat nobody has messaged can take a write. X stays inherited and runs one real turn;
     * the row its announcement maps to is computed here from the fresh published menu, by value, then
     * `resolved_model`, then family, and no model radio may read "Default". A picked row then stays
     * marked after leaving and reopening. Y gets its own model and effort, which X's pick must not touch.
     * Every model comes from the menu the host publishes at run time, and none is named here.
     *
     * The "fresh reply" is a new `request_session_settings`, sent by [freshSettings] on every call.
     *
     * **One real-claude turn.**
     */
    @Test
    fun interactiveTurn_modelChange_roundTripsAndStaysPerConversation() {
        val originals = mutableMapOf<String, SessionSettings>()
        try {
            awaitChannelList()
            awaitConnected()
            clearRememberedEffort()
            val stamp = System.currentTimeMillis()
            val nameX = MODEL_X_NAME_PREFIX + stamp
            val chatX = prepareChat(nameX, originals)
            val chatY = prepareChat(MODEL_Y_NAME_PREFIX + stamp, originals)
            assertTrue("X starts with a saved model", freshSettings(chatX.id).model in setOf("", INHERITED_MODEL_VALUE))
            val rows = usableRows(publishedMenu(chatX.id)).filter { it.value != INHERITED_MODEL_VALUE }
            val rowB = checkNotNull(rows.firstOrNull()) { "the menu publishes no usable model" }
            val effortY = rowB.effortLevels.firstOrNull().orEmpty()
            writeSettings(chatY.id, model = rowB.value, effort = effortY)
            assertSaved(chatY.id, rowB.value, effortY)

            // X inherits: after a real turn the mark follows what claude announced, not the default row.
            openChatRow(nameX)
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            val announced = announcedModel(chatX.id)
            check(announced.isNotEmpty()) { "claude's announced model was cut" }
            val freshMenu = publishedMenu(chatX.id)
            val marked = announcedRow(freshMenu, announced)
            awaitAnnouncedMark(freshMenu, marked, claudeFamily(announced).ifEmpty { UNAVAILABLE_MODEL_LABEL })
            if (marked != null) awaitFooter(changeModelLabel, marked.dropdownLabel(ConversationAgent.Claude))
            assertNoDefaultModelRadio()

            // A pick is exact-value and is never moved by the announcement.
            val target =
                checkNotNull(
                    rows.firstOrNull { it.value != marked?.value && it.value != rowB.value }
                        ?: rows.firstOrNull { it.value != marked?.value },
                ) {
                    "the menu publishes no usable model other than the announced one"
                }
            pickFooterOption(changeModelLabel, target.dropdownLabel(ConversationAgent.Claude))
            awaitFooter(changeModelLabel, target.dropdownLabel(ConversationAgent.Claude))
            assertEquals("X's saved model after the change", target.value, freshSettings(chatX.id).model)
            assertSaved(chatY.id, rowB.value, effortY)

            leaveThread()
            openChatRow(nameX)
            assertEquals("X's model on a fresh settings reply", target.value, freshSettings(chatX.id).model)
            awaitFooter(changeModelLabel, target.dropdownLabel(ConversationAgent.Claude))
            leaveThread()
            openChatRow(MODEL_Y_NAME_PREFIX + stamp)
            assertEquals("Y's model on a fresh settings reply", rowB.value, freshSettings(chatY.id).model)
            awaitFooter(changeModelLabel, rowB.dropdownLabel(ConversationAgent.Claude))
        } finally {
            restoreSettings(originals)
        }
    }

    /** A phone-selected model follows only a newly created chat into its first real Claude turn (#1223). */
    @Test
    fun interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage() {
        val originals = mutableMapOf<String, SessionSettings>()
        val preferences = GlobalContext.get().get<AppPreferences>()
        val previousChoice = runBlocking { preferences.rememberedModel.first() }
        try {
            awaitChannelList()
            awaitConnected()
            val originalName = "e2e1223-source-${System.currentTimeMillis()}"
            val original = prepareChat(originalName, originals)
            val initial = originals.getValue(original.id).model
            val target =
                checkNotNull(
                    usableRows(publishedMenu(original.id)).firstOrNull {
                        it.agent == original.agent && it.value != INHERITED_MODEL_VALUE && it.value != initial
                    },
                ) { "the host publishes no different nondefault model for the conversation agent" }

            openChatRow(originalName)
            openRunConfiguration()
            val family =
                target.value
                    .removePrefix("claude-")
                    .takeWhile { it.isLetter() }
                    .replaceFirstChar { it.uppercaseChar() }
                    .inert()
            val label =
                listOf(target.displayName.inert(), family).firstOrNull { candidate ->
                    composeTestRule.onAllNodes(hasText(candidate) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                } ?: error("the published model has no selectable row")
            composeTestRule
                .onAllNodes(hasText(label) and hasClickAction() and isEnabled())
                .onFirst()
                .performScrollTo()
                .performClick()
            awaitFooter(changeModelLabel, label)
            assertEquals("the source choice was acknowledged", target.value, freshSettings(original.id).model)
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    preferences.rememberedModel.first { it == target.value }
                }
            }
            leaveThread()

            val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
            val newId = createChatOn(serverId)
            assertEquals("new chat model before its first send", target.value, freshSettings(newId).model)
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            assertEquals("new chat model after a real turn", target.value, freshSettings(newId).model)
            assertEquals("original conversation retained its choice", target.value, freshSettings(original.id).model)
        } finally {
            restoreSettings(originals)
            runCatching {
                runBlocking { preferences.setRememberedModel(previousChoice.orEmpty()).getOrThrow() }
            }.onFailure { Log.w("E2E", "remembered model restore failed: ${it::class.simpleName}") }
        }
    }

    /**
     * Claude's applied effort reaches the footer after a real turn, starting from nothing saved and nothing
     * remembered (#545, #889). The reply to the first fresh read after the turn must carry
     * `effective_effort`, and an omitted key fails. The expectation is built from that reply, so no default
     * level is assumed.
     *
     * The thread stays open throughout (#1309): it re-reads its settings when the turn ends and when Run
     * configuration opens, so the applied effort and claude's reported permission mode both appear without
     * leaving and reopening it.
     *
     * **One real-claude turn.**
     */
    @Test
    fun interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn() {
        val originals = mutableMapOf<String, SessionSettings>()
        try {
            awaitChannelList()
            awaitConnected()
            clearRememberedEffort()
            assertNull("a remembered level survived the clear", rememberedEffort())
            val name = INHERITED_EFFORT_NAME_PREFIX + System.currentTimeMillis()
            val chat = prepareChat(name, originals)
            assertEquals("the fresh chat already has a saved effort", "", freshSettings(chat.id).effort)

            openChatRow(name)
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            val fresh = freshSettings(chat.id)
            val (label, note) = appliedEffortFooter(fresh.effectiveEffort)
            val mode = fresh.permissionMode
            assertTrue("the fresh reading after a real turn confirms no permission mode", mode.isNotEmpty())

            awaitFooter(changeEffortLabel, label) { it == note }
            awaitFooter(changePermissionLabel, PermissionModeOption.fromWire(mode)?.label ?: mode.inert())
        } finally {
            restoreSettings(originals)
        }
    }

    /**
     * An effort chosen before the first message is the one claude runs with (#545, #889). The phone picks,
     * from the footer, a published model that offers effort levels and then one of those levels. It waits
     * for the write to settle. Until a turn runs, the footer shows the choice and the reply reports no
     * applied string. After the first turn, the applied value is exactly the choice, and the reopened
     * footer shows it with no note. The note appears only when the saved value stands in for the applied one.
     *
     * **One real-claude turn.**
     */
    @Test
    fun interactiveTurn_chosenEffort_appliesFromTheFirstTurn() {
        val originals = mutableMapOf<String, SessionSettings>()
        try {
            awaitChannelList()
            awaitConnected()
            clearRememberedEffort()
            val name = CHOSEN_EFFORT_NAME_PREFIX + System.currentTimeMillis()
            val chat = prepareChat(name, originals)
            val current = originals.getValue(chat.id).model
            val row =
                checkNotNull(
                    usableRows(publishedMenu(chat.id)).firstOrNull {
                        it.effortLevels.isNotEmpty() && it.value != current && it.value != INHERITED_MODEL_VALUE
                    },
                ) {
                    "no other published model offers effort levels"
                }
            val level = row.effortLevels.first()

            openChatRow(name)
            pickFooterOption(changeModelLabel, row.dropdownLabel(ConversationAgent.Claude))
            awaitFooter(changeModelLabel, row.dropdownLabel(ConversationAgent.Claude))
            pickFooterOption(changeEffortLabel, effortLabel(level))
            awaitFooter(changeEffortLabel, effortLabel(level))

            val chosen = freshSettings(chat.id)
            assertEquals("saved effort after the tap", level, chosen.effort)
            assertTrue("claude reports an applied effort before any turn", chosen.effectiveEffort !is EffectiveEffort.Applied)
            awaitFooter(changeEffortLabel, effortLabel(level))

            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            assertEquals(EffectiveEffort.Applied(level), freshSettings(chat.id).effectiveEffort)

            leaveThread()
            openChatRow(name)
            awaitFooter(changeEffortLabel, effortLabel(level)) { it == null }
        } finally {
            restoreSettings(originals)
        }
    }

    /**
     * The remembered effort level survives an app restart and is applied to a fresh chat and a fresh
     * channel (#545, #686):
     *  * A tap on a priming chat's effort control is acknowledged, and its level becomes the remembered one.
     *  * The app restarts as it does in [interactiveTurn_twoHostsCollidingConversationId_stayPerHost]: the
     *    object graph is rebuilt over the same on-device state.
     *  * A fresh chat and a fresh channel, never messaged and with no saved effort, then get the
     *    remembered level through the recall before their first message. The reply confirms it, and the
     *    footer shows it. After one real turn in each, claude applies exactly that level.
     *  * A conversation with its own saved effort keeps it.
     *
     * No fixture has a model override: every saved model stays `""`, so the levels come from the published
     * `default` row (#972).
     *
     * **Two real-claude turns**: one in the fresh chat and one in the fresh channel.
     */
    @Test
    fun interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originals = mutableMapOf<String, SessionSettings>()
        var relaunched: ActivityScenario<MainActivity>? = null
        try {
            awaitChannelList()
            awaitConnected()
            clearRememberedEffort()
            val stamp = System.currentTimeMillis()

            // 1. A tapped level on the priming chat becomes the remembered level once the daemon acks it.
            val primingName = RECALL_PRIMING_NAME_PREFIX + stamp
            val priming = prepareChat(primingName, originals)
            val row =
                checkNotNull(
                    usableRows(publishedMenu(priming.id)).firstOrNull {
                        it.value == "default" &&
                            it.effortLevels.distinct().size >= 2
                    },
                ) {
                    "the published default row does not offer two effort levels"
                }
            val remembered = row.effortLevels.first()
            val explicit = row.effortLevels.last { it != remembered }
            assertSaved(priming.id, "", "")
            openChatRow(primingName)
            awaitFooter(changeModelLabel, inheritedModelLabel(publishedMenu(priming.id)))
            pickFooterOption(changeEffortLabel, effortLabel(remembered))
            awaitFooter(changeEffortLabel, effortLabel(remembered))
            assertEquals("the acknowledged tap was not remembered", remembered, rememberedEffort())
            leaveThread()

            // 2. The fixtures: a fresh chat and a fresh channel with no saved effort, and a chat with its own.
            val chatName = RECALL_CHAT_NAME_PREFIX + stamp
            val chat = prepareChat(chatName, originals)
            val channelName = RECALL_CHANNEL_NAME_PREFIX + stamp
            val channel = prepareChannel(channelName, chat.cwd, originals)
            val explicitName = RECALL_EXPLICIT_NAME_PREFIX + stamp
            val explicitChat = prepareChat(explicitName, originals)
            writeSettings(explicitChat.id, effort = explicit)
            assertSaved(chat.id, "", "")
            assertSaved(channel.id, "", "")
            assertSaved(explicitChat.id, "", explicit)

            // 3. Restart. No activity may outlive the graph it resolved, so the rule's activity goes first.
            composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            instrumentation.runOnMainSync {
                (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph()
            }
            relaunched = ActivityScenario.launch(MainActivity::class.java)
            awaitChannelList()
            awaitConnected()
            assertEquals("the remembered level after the restart", remembered, rememberedEffort())

            // 4. The fresh chat, then the fresh channel: recalled before the first message, applied after it.
            openChatRow(chatName)
            assertRecalledThenApplied(chat.id, remembered)
            leaveThread()
            openRow(channelName)
            assertRecalledThenApplied(channel.id, remembered)
            leaveThread()

            // 5. A saved effort of its own is kept: settled on it, confirmed by the reply, and still settled.
            openChatRow(explicitName)
            awaitFooter(changeEffortLabel, effortLabel(explicit))
            assertEquals("the explicit saved effort was overwritten", explicit, freshSettings(explicitChat.id).effort)
            composeTestRule.waitForIdle()
            awaitFooter(changeEffortLabel, effortLabel(explicit))
        } finally {
            restoreSettings(originals)
            relaunched?.close()
        }
    }

    /**
     * The phone never describes an operator-bypass child as enforcing approvals (#687, pyrycode#2510). The
     * scenario runs on a dedicated daemon that `scripts/e2e-emulator.sh` starts under its own HOME, with the
     * stdio prompt surface on and its children launched in operator bypass (stored mode `default`). The phone
     * pairs with it by code and stays unprivileged. A peer paired `--allow-remote-permissions` to the same
     * daemon answers the one prompt. The chat is made with `createDiscussion`: the daemon builds a minted
     * session from the bootstrap's template, so its child launches in bypass too.
     *  * **Bypass reads as bypass.** After a tool-free turn, a fresh reading reports `bypassPermissions`,
     *    and the reopened footer reads Bypass approvals, never Manual approval.
     *  * **An acknowledgement is not a confirmation.** After Manual approval, a structured write outcome
     *    distinguishes refusal from acknowledgement. Once the control settles, a fresh reply says whether
     *    the child confirmed `default` early or remained in `bypassPermissions` after an acknowledged no-op.
     *    The selected row must match that reply. Plan, Bypass approvals and Manual approval then each settle
     *    on their own label once a fresh reading reports them, on the same session with no turn in between.
     *  * **Manual approval enforces.** A Read of a file outside the workspace raises a prompt on the phone
     *    that names the Read. The peer allows it, and the reply carries the file's token, which the prompt
     *    never contains.
     *
     * An unmet prerequisite of the dedicated daemon fails here with its name, never a skip.
     *
     * **Two real-claude turns**: the tool-free ping and the Read.
     */
    @Test
    fun interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        args.getString(ARG_BYPASS_UNMET)?.let { throw AssertionError(bypassUnmetMessage(it)) }
        awaitChannelList()
        awaitConnected()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        val fixture = BypassPairingFixture.request()
        val serverId = fixture.serverId
        val tokenFile = bypassArg(ARG_BYPASS_TOKEN_FILE)
        val token = bypassArg(ARG_BYPASS_TOKEN)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = fixture.peerToken,
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = fixture.serverStaticPublicKey,
                ),
            )
        val bypass = PermissionModeOption.Bypass
        val manual = PermissionModeOption.Default
        try {
            // 1. Pair the freshly minted dedicated host by code, create a chat, and run one tool-free turn.
            pairHostByCode(fixture.pairCode, BYPASS_HOST_NAME)
            val name = BYPASS_CHAT_NAME_PREFIX + System.currentTimeMillis()
            val repository = hostRepository(serverId)
            val chat = runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repository.rename(repository.createDiscussion().id, name) } }
            openChatRow(name)
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

            // 2. AC-1: the thread re-reads its settings on subscription, not at turn end, so reopen it first.
            leaveThread()
            openChatRow(name)
            val sessionId = awaitPermissionReading(serverId, chat.id, bypass.wire).sessionId
            awaitFooter(changePermissionLabel, bypass.label)

            // 3. AC-1: distinguish the write result from the child's confirmed mode. A fast settlement
            //    is valid when the child already confirmed Manual approval; pending duration proves neither.
            val priorSink = RelayLog.sink
            val writeOutcome = AtomicReference<String?>(null)
            RelayLog.sink = { priority, tag, message ->
                priorSink(priority, tag, message)
                if (message.startsWith("event=permission_write outcome=")) {
                    writeOutcome.set(message.substringAfter("outcome="))
                }
            }
            try {
                pickFooterOption(changePermissionLabel, manual.label)
                try {
                    composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { writeOutcome.get() != null }
                } catch (e: ComposeTimeoutException) {
                    throw AssertionError("the Manual approval write had no classified outcome within $THREAD_TIMEOUT_MS ms", e)
                }
            } finally {
                RelayLog.sink = priorSink
            }
            when (writeOutcome.get()) {
                "acked" -> Unit
                "refused" -> throw AssertionError("the Manual approval write was refused")
                "failed" -> throw AssertionError("the Manual approval write failed before acknowledgement")
                else -> throw AssertionError("the Manual approval write had no classified outcome")
            }
            openRunConfiguration()
            try {
                composeTestRule.waitUntil(PERMISSION_SETTLE_WINDOW_MS + THREAD_TIMEOUT_MS) {
                    composeTestRule.onAllNodesWithText("Permission · applying…").fetchSemanticsNodes().isEmpty()
                }
            } finally {
                composeTestRule.onNodeWithContentDescription("Close").performClick()
            }
            val afterManual = freshSettings(chat.id, serverId)
            assertEquals("the Manual approval write moved to another session", sessionId, afterManual.sessionId)
            when (afterManual.permissionMode) {
                manual.wire -> awaitFooter(changePermissionLabel, manual.label)
                bypass.wire -> awaitFooter(changePermissionLabel, bypass.label)
                else -> throw AssertionError("the acknowledged Manual approval write reported an unexpected permission mode")
            }

            // 4. AC-2: real changes on the same child, each confirmed by a fresh reading and then the footer.
            listOf(PermissionModeOption.Plan, bypass, manual).forEach { mode ->
                pickFooterOption(changePermissionLabel, mode.label)
                val reading = awaitPermissionReading(serverId, chat.id, mode.wire)
                assertEquals("the conversation moved to another session", sessionId, reading.sessionId)
                awaitFooter(changePermissionLabel, mode.label)
            }

            // 5. AC-3: a Read outside the workspace raises a prompt that names it; the peer allows it once.
            val prompt = READ_PROMPT_TEMPLATE.format(tokenFile)
            assertTrue("the Read prompt contains the witness token", token !in prompt)
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            sendFromPhone(prompt)
            // #1480: Claude's think time is the host's to wait out, so the phone is judged only once the host asked.
            val modalId =
                try {
                    runBlocking { peer.awaitPermissionModal(chat.id, UPSTREAM_PERMISSION_TIMEOUT_MS) }
                } catch (e: TimeoutCancellationException) {
                    throw AssertionError(
                        "Claude raised no permission request within $UPSTREAM_PERMISSION_TIMEOUT_MS ms of the send",
                        e,
                    )
                }
            try {
                awaitReadPrompt(tokenFile.substringAfterLast('/'))
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the host raised the permission modal and the phone did not render it within $REPLY_TIMEOUT_MS ms", e)
            } catch (e: AssertionError) {
                throw AssertionError("the host raised the permission modal and the phone did not render it within $REPLY_TIMEOUT_MS ms", e)
            }
            // #977: the turn must end before the phone is judged, and a missing token says why.
            val mark = peer.recorded(chat.id).size
            runBlocking { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            val allowedAt = SystemClock.elapsedRealtime()
            val turnEnd =
                try {
                    runBlocking { peer.awaitFrame(chat.id, "turn_end", REPLY_TIMEOUT_MS) }
                } catch (e: TimeoutCancellationException) {
                    throw AssertionError(
                        "the allowed Read's turn never ended: no turn_end for the chat within $REPLY_TIMEOUT_MS ms of the allow",
                        e,
                    )
                }
            val endedAfterMs = SystemClock.elapsedRealtime() - allowedAt
            val reply = hasText(token, substring = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
            // #1449: the reply is judged where the follow rule leaves it, at the newest end, with no scroll.
            try {
                composeTestRule.waitUntil(PHONE_TRAIL_MS) {
                    composeTestRule.onAllNodes(reply, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError(
                    "the allowed Read's reply never carried the file's token: " +
                        readReplyDiagnosis(peer.recorded(chat.id), mark, token, turnEnd, endedAfterMs) +
                        " threadHeldToken=${threadHoldsReply(repository, chat.id, token)}",
                    e,
                )
            }
        } finally {
            peer.close()
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverId) }
        }
    }

    /**
     * A permission answer from the phone reaches the conversation that asked, and only that one (#966, rung 3).
     * The scenario runs on the dedicated answer daemon `scripts/e2e-emulator.sh` starts under its own HOME with
     * the stdio prompt surface on, the one path that offers don't-ask-again. The phone pairs with it by code
     * `--allow-remote-permissions`; it is the only host where the phone may answer. A peer paired the same way
     * records every frame and allows the last prompt.
     *  * **Only the asking thread shows it.** Chat A's command raises a prompt the phone draws inline in A with
     *    its decision context, and not in chat B.
     *  * **Leaving keeps the grant, not the arm (#1306).** Ticking don't-ask-again and arming Allow in A, then
     *    leaving for B and back, restores the tick and clears the arm, so allowing takes two new taps.
     *  * **The phone's allow reaches A's claude.** Ticking don't-ask-again and allowing on the phone ends A's
     *    turn, and claude's reply carries the command's output, which no prompt contains.
     *  * **Don't-ask-again holds.** The same command in A runs again with no second prompt.
     *  * **Resolved elsewhere closes it.** The same command in B prompts there, since B's session holds no
     *    grant; the peer allows it and the phone's dialog closes with no tap.
     *
     * Claude's reply is read from the `assistant_delta` frames the peer records for the chat, not from a phone
     * bubble: #981 tracks a reply that is not composed after an allowed prompt. An unmet prerequisite of the
     * dedicated daemon fails here with its name, never a skip.
     *
     * **Three real-claude turns**: A's allowed command, its repeat, and B's command.
     */
    @Test
    fun interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation() {
        val (serverId, peer) = answerHostPeer()
        try {
            pairAnswerHost()
            val (chatA, nameA) = answerChat(serverId, ANSWER_CHAT_NAME_PREFIX + "a-")
            val (chatB, nameB) = answerChat(serverId, ANSWER_CHAT_NAME_PREFIX + "b-")
            peerStep(peer, "open answer peer") { peer.open(CONNECT_TIMEOUT_MS) }

            // 1. AC-1: A's command raises a prompt in A that carries claude's context and a don't-ask-again offer.
            openChatRow(nameA)
            sendFromPhone(ANSWER_PERMISSION_PROMPT)
            val shown =
                runBlocking {
                    MobileJson.decodeFromJsonElement(
                        ModalShownPayloadDto.serializer(),
                        peer.awaitFrame(chatA, "modal_shown", REPLY_TIMEOUT_MS).payload,
                    )
                }
            assertEquals("the prompt's class", PERMISSION_CLASS, shown.modalClass)
            awaitPromptDialog()
            assertContextDrawn(shown)
            assertEquals(
                "the prompt's don't-ask-again offer",
                "true",
                (shown.alwaysAllow as? JsonObject)?.get("offered")?.jsonPrimitive?.contentOrNull,
            )

            // 2. #1306 AC-2: in A, tick don't-ask-again and arm Allow (a non-default option arms first), inline.
            val offer = hasText(string(R.string.modal_always_allow_label)) and hasClickAction() and inPromptDialog()
            tapInPrompt(offer)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(offer and isOn()).fetchSemanticsNodes().isNotEmpty()
            }
            val allow = hasText(shown.options.first { it.id == ALLOW_ONCE }.label) and hasClickAction() and inPromptDialog()
            val armed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.modal_armed_option_desc))
            tapInPrompt(allow)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(allow and armed).fetchSemanticsNodes().isNotEmpty()
            }

            // 3. AC-1: the prompt belongs to A. Leaving A for B shows no A prompt there; returning to A restores
            //    the ticked grant but not the arm, so allowing takes two new taps.
            leaveThread()
            openChatRow(nameB)
            composeTestRule.waitForIdle()
            SystemClock.sleep(SCOPE_SETTLE_MS)
            composeTestRule.onAllNodes(promptDialog()).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText(shown.prompt, substring = true)).assertCountEquals(0)
            leaveThread()
            openChatRow(nameA)
            awaitPromptDialog()
            composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(offer)
            composeTestRule.onNode(offer).assertIsOn()
            composeTestRule.onAllNodes(armed).assertCountEquals(0)
            tapInPrompt(allow)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(allow and armed).fetchSemanticsNodes().isNotEmpty()
            }
            tapInPrompt(allow)
            // #1340: the card closes on the tap itself, not on the daemon's reply.
            awaitNoPromptDialog("A's card stayed after the allow tap")

            // 4. AC-1: the daemon took the phone's answer for this prompt, A's turn ends, and claude's reply
            //    carries the command's output. The phone's own answer is not announced as resolved elsewhere.
            val dismissed = runBlocking { peer.awaitModalDismissed(shown.modalId, THREAD_TIMEOUT_MS) }
            assertEquals("who resolved A's prompt", REMOTE_SOURCE, peer.field(dismissed, "source"))
            assertEquals("A's prompt outcome", ALLOW_ONCE, peer.field(dismissed, "outcome"))
            awaitTurnEnd(peer, chatA, 1, "A's allowed turn")
            assertBashRan(peer, chatA, 0, "A's allowed turn")
            assertTrue("A's reply does not carry the command's output", ANSWER_PERMISSION_TOKEN in assistantText(peer, chatA))
            composeTestRule.onAllNodes(hasText(string(R.string.modal_dismissed_remote))).assertCountEquals(0)

            // 5. AC-2: the same command in A runs again with no second prompt. Claude could repeat the number
            //    from context, so the proof is a successful Bash call in this turn, not the reply alone.
            val mark = peer.recorded(chatA).size
            sendFromPhone(ANSWER_PERMISSION_PROMPT)
            awaitTurnEnd(peer, chatA, 2, "A's repeat")
            assertBashRan(peer, chatA, mark, "A's repeat")
            assertEquals("prompts raised in A", 1, peer.recorded(chatA).count { it.type == "modal_shown" })
            assertTrue("A's repeat reply does not carry the command's output", ANSWER_PERMISSION_TOKEN in assistantText(peer, chatA, mark))

            // 6. AC-4: the same command prompts in B; the peer allows it and the phone's dialog closes untouched.
            leaveThread()
            openChatRow(nameB)
            sendFromPhone(ANSWER_PERMISSION_PROMPT)
            awaitPromptDialog()
            runBlocking {
                val modalId = peer.awaitPermissionModal(chatB, REPLY_TIMEOUT_MS)
                peer.allowOnce(modalId, THREAD_TIMEOUT_MS)
            }
            awaitNoPromptDialog("B's dialog stayed after the peer allowed it")
            awaitTurnEnd(peer, chatB, 1, "B's peer-allowed turn")
        } finally {
            peer.close()
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverId) }
        }
    }

    /**
     * Two chats on one host each hold a real permission prompt at once, and each shows its own (#1337, rung 3),
     * on the dedicated answer daemon of [interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation].
     *  * **Both held.** A raises a prompt, then B raises one while A's is still outstanding. B shows its own, and
     *    returning to A still shows A's: B's prompt no longer replaces it.
     *  * **Answering A leaves B.** The phone allows A's prompt; the daemon dismisses A's id from the phone, never
     *    B's, and B's thread still shows its prompt. The peer then allows B's and B's dialog closes.
     *
     * Both prompts carry the same command, so which prompt the phone answered is proven by the peer's frames:
     * the phone's one answer, sent from A, resolves A's `modal_id` with source `remote`, and B's thread still
     * shows a prompt afterwards, which the phone drops once the daemon dismisses B's id.
     *
     * A's turn end is not awaited. The daemon streams turn frames only for the conversation a message was last
     * routed to (its follow-active cursor), which is B from step 2 on, so A's frames after the allow never reach
     * any client. That A's allowed turn ends is proven by the sibling method, where no other chat is routed.
     *
     * **Two real-claude turns**: A's and B's commands.
     */
    @Test
    fun interactiveTurn_permissionPrompts_heldPerConversation() {
        val (serverId, peer) = answerHostPeer()
        try {
            pairAnswerHost()
            val (chatA, nameA) = answerChat(serverId, ANSWER_CHAT_NAME_PREFIX + "pa-")
            val (chatB, nameB) = answerChat(serverId, ANSWER_CHAT_NAME_PREFIX + "pb-")
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }

            // 1. A raises a prompt in A.
            openChatRow(nameA)
            sendFromPhone(ANSWER_PERMISSION_PROMPT)
            val shownA =
                runBlocking {
                    MobileJson.decodeFromJsonElement(
                        ModalShownPayloadDto.serializer(),
                        peer.awaitFrame(chatA, "modal_shown", REPLY_TIMEOUT_MS).payload,
                    )
                }
            val promptA = shownA.modalId
            awaitPromptDialog()

            // 2. B raises its own prompt while A's is outstanding, and B shows it.
            leaveThread()
            openChatRow(nameB)
            sendFromPhone(ANSWER_PERMISSION_PROMPT)
            val promptB = runBlocking { peer.awaitPermissionModal(chatB, REPLY_TIMEOUT_MS) }
            assertNotEquals("the two chats' prompts", promptA, promptB)
            awaitPromptDialog()

            // 3. Back in A, A's prompt is still shown: B's did not replace it.
            leaveThread()
            openChatRow(nameA)
            awaitPromptDialog()

            // 4. Allowing in A (a non-default option arms first) resolves A's prompt from the phone, not B's.
            val allow = hasText(shownA.options.first { it.id == ALLOW_ONCE }.label) and hasClickAction() and inPromptDialog()
            val armed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.modal_armed_option_desc))
            tapInPrompt(allow)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(allow and armed).fetchSemanticsNodes().isNotEmpty()
            }
            tapInPrompt(allow)
            val dismissedA = runBlocking { peer.awaitModalDismissed(promptA, THREAD_TIMEOUT_MS) }
            assertEquals("who resolved A's prompt", REMOTE_SOURCE, peer.field(dismissedA, "source"))
            assertEquals("A's prompt outcome", ALLOW_ONCE, peer.field(dismissedA, "outcome"))
            awaitNoPromptDialog("A's dialog stayed after the phone allowed it")

            // 5. B's prompt is still shown in B, so A's answer left it; the peer allows it and B's dialog closes untouched.
            leaveThread()
            openChatRow(nameB)
            awaitPromptDialog()
            runBlocking { peer.allowOnce(promptB, THREAD_TIMEOUT_MS) }
            awaitNoPromptDialog("B's dialog stayed after the peer allowed it")
            awaitTurnEnd(peer, chatB, 1, "B's peer-allowed turn")
        } finally {
            peer.close()
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverId) }
        }
    }

    /**
     * A clarification answer from the phone reaches the conversation that asked (#966, rung 3), on the same
     * dedicated answer daemon as [interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation]; the
     * daemon gates a question answer on the same `--allow-remote-permissions` bit as a permission answer.
     *  * **The phone's choice reaches claude.** Claude asks one AskUserQuestion with two labels; the phone
     *    picks [QUESTION_PICK] and continues, and claude's reply is that label and not the other one.
     *  * **Resolved elsewhere closes it.** Asked again, the peer answers; the phone's modal closes with no tap.
     *
     * The reply is read from the peer's `assistant_delta` frames, as in the permission method.
     *
     * **Two real-claude turns**: the phone-answered question and the peer-answered one.
     */
    @Test
    fun interactiveTurn_questionAnswer_reachesTheAskingConversation() {
        val (serverId, peer) = answerHostPeer()

        fun <T> step(
            stage: QuestionAnswerStage,
            block: () -> T,
        ): T = questionAnswerStep(stage, peer::linkState, block)
        try {
            step(QuestionAnswerStage.PairPhone) { pairAnswerHost() }
            val (chat, name) = step(QuestionAnswerStage.CreateChat) { answerChat(serverId, ANSWER_CHAT_NAME_PREFIX + "q-") }
            step(QuestionAnswerStage.OpenPeer) { runBlocking { peer.open(CONNECT_TIMEOUT_MS) } }
            step(QuestionAnswerStage.OpenThread) { openChatRow(name) }

            // 1. AC-3: claude asks in this chat, and the phone draws the question.
            step(QuestionAnswerStage.SendFirstQuestion) { sendFromPhone(QUESTION_PROMPT) }
            val batchId = step(QuestionAnswerStage.AwaitFirstQuestion) { runBlocking { peer.awaitQuestion(chat, REPLY_TIMEOUT_MS) } }
            step(QuestionAnswerStage.DrawFirstQuestion) { awaitInlineQuestion() }

            // 2. AC-3: the phone picks one option and continues; the daemon takes it as this batch's answer.
            val mark = peer.recorded(chat).size
            step(QuestionAnswerStage.SubmitPhoneAnswer) {
                composeTestRule
                    .questionAnswerTarget(
                        hasText(QUESTION_PICK, substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("thread-question-row")),
                    ).performTouchInput { click(center) }
                val continueButton =
                    hasText(string(R.string.question_continue)) and hasClickAction() and isEnabled() and
                        hasAnyAncestor(hasTestTag("question-batch-actions"))
                // The actions are a separate lazy item; selecting an option need not compose them (#1702).
                composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("question-batch-actions"))
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                    composeTestRule.onAllNodes(continueButton).fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.questionAnswerTarget(continueButton).performTouchInput { click(center) }
            }
            val dismissed =
                step(QuestionAnswerStage.AwaitPhoneDismissal) { runBlocking { peer.awaitQuestionDismissed(batchId, THREAD_TIMEOUT_MS) } }
            assertEquals("who resolved the question", REMOTE_SOURCE, peer.field(dismissed, "source"))
            assertEquals("the question's outcome", ANSWERED, peer.field(dismissed, "outcome"))

            // 3. AC-3: the turn ends and claude's reply names the chosen option, not the other one.
            step(QuestionAnswerStage.EndPhoneTurn) { awaitTurnEnd(peer, chat, 1, "the phone-answered turn") }
            val reply = assistantText(peer, chat, mark)
            assertTrue("the reply does not name the chosen option", QUESTION_PICK in reply)
            assertTrue("the reply names the option the phone did not choose", QUESTION_OTHER !in reply)
            step(QuestionAnswerStage.RemovePhoneQuestion) { awaitNoInlineQuestion("the question stayed after the phone answered it") }

            // 4. AC-4: asked again, the peer answers, and the phone's inline batch disappears with no tap.
            step(QuestionAnswerStage.SendSecondQuestion) { sendFromPhone(QUESTION_PROMPT) }
            val second =
                step(QuestionAnswerStage.AwaitSecondQuestion) { runBlocking { peer.awaitQuestion(chat, REPLY_TIMEOUT_MS, occurrence = 2) } }
            step(QuestionAnswerStage.DrawSecondQuestion) { awaitInlineQuestion() }
            step(QuestionAnswerStage.SubmitPeerAnswer) { runBlocking { peer.answerQuestion(second, 0, QUESTION_OTHER, THREAD_TIMEOUT_MS) } }
            step(QuestionAnswerStage.RemovePeerQuestion) { awaitNoInlineQuestion("the question stayed after the peer answered it") }
            step(QuestionAnswerStage.EndPeerTurn) { awaitTurnEnd(peer, chat, 2, "the peer-answered turn") }
        } finally {
            peer.close()
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverId) }
        }
    }

    /**
     * A conversation's attention dot follows a real turn (#1090, rung 3), on the #966 answer daemon and its peer.
     * The peer starts every turn, so the phone stays on the list and never views the conversation it marks.
     *  * **A completed turn marks only its row.** The peer's ping in A ends; A's row reads Unread, and every
     *    other composed row, B among them, reads what it read before.
     *  * **Opening reads it.** Opening A and returning to the list shows A Idle.
     *  * **A held prompt marks its row until answered.** The peer's command in B raises a permission prompt;
     *    B reads Waiting for your answer, still after a settle with nothing answered, while A stays Idle. The
     *    peer allows it, B's turn ends, and B reads Unread.
     *
     * Each state is read from the dot's content description, never its colour. Running is never asserted: on a
     * ping it is transient, like the thinking spinner.
     *
     * **Two real-claude turns**: A's ping and B's allowed command.
     */
    @Test
    fun interactiveTurn_attentionDot_followsARealTurn() {
        val (serverId, peer) = answerHostPeer()
        val idle = string(R.string.cd_conversation_attention_idle)
        val unread = string(R.string.cd_conversation_attention_unread)
        val waiting = string(R.string.cd_conversation_attention_waiting)
        try {
            pairAnswerHost()
            val (chatA, nameA) = answerChat(serverId, ATTENTION_CHAT_NAME_PREFIX + "a-")
            val (chatB, nameB) = answerChat(serverId, ATTENTION_CHAT_NAME_PREFIX + "b-")
            peerStep(peer, "open") { peer.open(CONNECT_TIMEOUT_MS) }

            // 1. Both new chats start Idle; every composed row's state is recorded.
            awaitRowAttention(nameA, idle, "A before any turn")
            awaitRowAttention(nameB, idle, "B before any turn")
            val before = treeRowAttention()

            // 2. AC-1: the peer's ping in A completes while the phone shows the list. Only A's row changes.
            peerStep(peer, "send the ping to A") { peer.sendMessage(chatA, PING_PROMPT, THREAD_TIMEOUT_MS) }
            awaitTurnEnd(peer, chatA, 1, "A's ping")
            awaitRowAttention(nameA, unread, "A after its turn completed on the list")
            val after = treeRowAttention()
            assertEquals("B after A's turn", listOf(idle), after[nameB])
            val changed = (before.keys intersect after.keys).filter { it != nameA && before[it] != after[it] }
            assertTrue("rows other than A changed state after A's turn: ${changed.size}", changed.isEmpty())

            // 3. AC-1: opening A and returning to the list reads it.
            openChatRow(nameA)
            leaveThread()
            awaitRowAttention(nameA, idle, "A after it was opened")

            // 4. AC-2: the peer's command in B holds its turn on a permission prompt. B waits, and keeps waiting.
            peerStep(peer, "send the command to B") { peer.sendMessage(chatB, ANSWER_PERMISSION_PROMPT, THREAD_TIMEOUT_MS) }
            val modalId = peerStep(peer, "await B's permission prompt") { peer.awaitPermissionModal(chatB, REPLY_TIMEOUT_MS) }
            awaitRowAttention(nameB, waiting, "B while its prompt is outstanding")
            SystemClock.sleep(SCOPE_SETTLE_MS)
            awaitRowAttention(nameB, waiting, "B after a settle with its prompt unanswered")
            awaitRowAttention(nameA, idle, "A while B's prompt is outstanding")

            // 5. AC-2: the peer answers; B's turn ends and its row stops waiting.
            peerStep(peer, "allow B's prompt") { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            peerStep(peer, "await B's prompt dismissal") { peer.awaitModalDismissed(modalId, THREAD_TIMEOUT_MS) }
            awaitTurnEnd(peer, chatB, 1, "B's allowed turn")
            awaitRowAttention(nameB, unread, "B after its prompt was answered and its turn ended")
        } finally {
            peer.close()
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverId) }
        }
    }

    /** #1735, rung 3: one permission-held real turn in B while A stays visible. */
    @Test
    fun interactiveTurn_otherConversationAttentionPills_waitingAndFinished() {
        val (serverId, peer) = answerHostPeer()
        try {
            pairAnswerHost()
            val (_, nameA) = answerChat(serverId, ATTENTION_CHAT_NAME_PREFIX + "pill-a-")
            val (chatB, nameB) = answerChat(serverId, ATTENTION_CHAT_NAME_PREFIX + "pill-b-")
            peerStep(peer, "open attention peer") { peer.open(CONNECT_TIMEOUT_MS) }
            openChatRow(nameA)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val waitingLabel = context.getString(R.string.thread_attention_waiting, nameB)
            val finishedLabel = context.getString(R.string.thread_attention_finished, nameB)
            peerStep(peer, "start held turn in B") { peer.sendMessage(chatB, ANSWER_PERMISSION_PROMPT, THREAD_TIMEOUT_MS) }
            val modalId = peerStep(peer, "await B's held permission") { peer.awaitPermissionModal(chatB, REPLY_TIMEOUT_MS) }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag("thread_attention_pill") and hasText(waitingLabel)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(waitingLabel).assertIsDisplayed().performTouchInput { click(center) }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText(nameB)).fetchSemanticsNodes().isNotEmpty()
            }
            // Navigation must leave the same prompt outstanding; it is answered only through the peer below.
            awaitPromptDialog()
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag("thread_attention_pill") and hasText(waitingLabel)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(waitingLabel).assertIsDisplayed()
            composeTestRule.onNodeWithText(nameA).assertIsDisplayed()
            peerStep(peer, "allow B's held permission") { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            peerStep(peer, "await B's prompt dismissal") { peer.awaitModalDismissed(modalId, THREAD_TIMEOUT_MS) }
            // Start watching the short-lived pill before awaiting the peer's turn_end to avoid missing it.
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule
                    .onAllNodes(
                        hasTestTag("thread_attention_pill") and hasText(finishedLabel),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeTestRule.onNodeWithText(finishedLabel).assertIsDisplayed()
            awaitTurnEnd(peer, chatB, 1, "B's attention-pill turn")
            // The pill's five-second expiry runs on the rule's virtual clock, which waitUntil advances one frame per
            // poll. In the full suite each poll is slow enough that five virtual seconds outlast a real ten-second
            // wait (#1735; same cause as #1664), so advance the clock past the expiry instead.
            composeTestRule.mainClock.advanceTimeBy(5_100)
            composeTestRule.waitUntil(10_000) {
                composeTestRule.onAllNodes(hasTestTag("thread_attention_pill")).fetchSemanticsNodes().isEmpty()
            }
            composeTestRule.onNodeWithText(finishedLabel).assertDoesNotExist()
            leaveThread()
            openChatRow(nameA)
            composeTestRule.onNodeWithTag("thread_attention_pill").assertDoesNotExist()
        } finally {
            peer.close()
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverId) }
        }
    }

    /**
     * The composer footer's readings and a model change survive a cut-and-restore of the phone's link (#967).
     * The chat is prepared as #545's are: nothing remembered and no saved model.
     *  * **The context reading is held.** After a real turn the footer shows `Cxt: N%`. The host's readings are
     *    kept for the life of its pairing (#1317), so with the link cut it still shows that same reading. Once the
     *    link is back the thread asks for a fresh one (#1410), and the footer shows a percentage.
     *  * **The readings come back.** Effort and permission settle, none pending, on what a fresh reading
     *    taken on the new connection reports. The model is inherited, and the first turn's announcement is
     *    held too (#1317), so the mark follows that announcement (#1308) rather than the default row.
     *  * **A context reading after a turn.** A turn on the new connection pushes a reading newer than the held
     *    one, its token count grown past the held reading's, and the footer shows `Cxt: N%`.
     *  * **A change is confirmed.** A published model picked from the footer is the saved model a fresh
     *    reading reports. It is picked after the last turn, so no turn runs on a model the account may not serve.
     *
     * **Two real-claude turns**: the ping before the cut and the ping after it.
     */
    @Test
    fun interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive() {
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val originals = mutableMapOf<String, SessionSettings>()
        try {
            // 1. One real turn in a fresh chat; the footer shows the post-turn context reading.
            awaitChannelList()
            awaitConnected()
            clearRememberedEffort()
            val name = RECONNECT_FOOTER_NAME_PREFIX + System.currentTimeMillis()
            val chat = prepareChat(name, originals)
            openChatRow(name)
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
            val held = awaitContextSegment(REPLY_TIMEOUT_MS, "a percentage after the first turn") { CONTEXT_REPORTED.matches(it) }
            // The reading the first turn pushed. Its repository reads the host's readings (#1317), so it stays
            // readable while the link is cut.
            val preCutRepository = hostRepository()
            val preCut =
                runBlocking { withTimeout(REPLY_TIMEOUT_MS) { preCutRepository.observeContextUsage(chat.id).filterNotNull().first() } }

            // 2. Cut the link: the host's context reading is held across it (#1317). Checked while the link is down,
            //    because once it is back the thread's reconnect ask (#1410) may replace the reading with its answer.
            setHostLink(serverId, up = false)
            assertEquals(
                "the held context reading while the link is cut",
                preCut,
                runBlocking { preCutRepository.observeContextUsage(chat.id).first() },
            )
            awaitContextSegment(THREAD_TIMEOUT_MS, "the held '$held' while the link is cut") { it == held }
            setHostLink(serverId, up = true)
            awaitContextSegment(THREAD_TIMEOUT_MS, "a percentage after the reconnect") { CONTEXT_REPORTED.matches(it) }

            // 3. Model, effort and permission settle on a fresh reading taken on the new connection.
            val fresh = freshSettings(chat.id)
            assertEquals("the fixture's saved model", "", fresh.model)
            val (effortLabel, effortNote) = appliedEffortFooter(fresh.effectiveEffort)
            val mode = fresh.permissionMode
            assertTrue("the fresh reading after a real turn confirms no permission mode", mode.isNotEmpty())
            // The first turn's announcement is held across the reconnect too (#1317), so the inherited chat
            // marks the row it maps to rather than the default row's resolution (#1308).
            val announced = announcedModel(chat.id)
            check(announced.isNotEmpty()) { "claude's announced model was cut" }
            val menu = publishedMenu(chat.id)
            val marked = announcedRow(menu, announced)
            awaitAnnouncedMark(menu, marked, claudeFamily(announced).ifEmpty { UNAVAILABLE_MODEL_LABEL })
            if (marked != null) awaitFooter(changeModelLabel, marked.dropdownLabel(ConversationAgent.Claude))
            awaitFooter(changeEffortLabel, effortLabel) { it == effortNote }
            awaitFooter(changePermissionLabel, PermissionModeOption.fromWire(mode)?.label ?: mode.inert())

            // 4. A turn on the new connection leaves a context reading, pushed when the turn ends. The held
            //    reading already shows a percentage, so the wait is on the turn itself: its reply is drawn and
            //    the Stop control has gone. Two ping replies in a two-ping thread are both composed in the list.
            //    Then the reading must be newer than the held one: the footer's percentage can round to the same
            //    text, so freshness is the repository's token count growing past the held reading's.
            //    The reconnect ask's answer (#1410) is a detail:"full" count, which need not agree with a post-turn
            //    detail:"summary" push, so it is neither the baseline nor allowed to pass the growth check. The
            //    baseline is the pre-cut push. The answer is let land first, moving the reading off the pre-cut
            //    one; an answer equal to it emits nothing and a refused ask sends none, so that wait may time out.
            //    The growth check then accepts only a reading other than the settled one.
            val repository = hostRepository()
            val settled =
                runBlocking {
                    withTimeoutOrNull(THREAD_TIMEOUT_MS) { repository.observeContextUsage(chat.id).filterNotNull().first { it != preCut } }
                } ?: preCut
            sendFromPhone(PING_PROMPT)
            val stopControl = hasContentDescription(string(R.string.cd_thread_interrupt))
            try {
                composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                    composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).fetchSemanticsNodes().size >= 2 &&
                        composeTestRule.onAllNodes(stopControl).fetchSemanticsNodes().isEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the turn on the new connection never ended with its reply drawn", e)
            }
            try {
                runBlocking {
                    withTimeout(REPLY_TIMEOUT_MS) {
                        repository.observeContextUsage(chat.id).filterNotNull().first {
                            it != settled && it.totalTokens > preCut.totalTokens
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError(
                    "no context reading newer than the held one (${preCut.totalTokens} tokens) after the turn on the new connection",
                    e,
                )
            }
            awaitContextSegment(REPLY_TIMEOUT_MS, "a percentage after the turn on the new connection") { CONTEXT_REPORTED.matches(it) }

            // 5. A model picked from the published menu is confirmed by a fresh reading.
            val target =
                checkNotNull(
                    usableRows(publishedMenu(chat.id)).firstOrNull {
                        it.value != INHERITED_MODEL_VALUE && it.value != fresh.model && it.value != marked?.value
                    },
                ) {
                    "the menu publishes no usable model besides the inherited default and the marked row"
                }
            pickFooterOption(changeModelLabel, target.dropdownLabel(ConversationAgent.Claude))
            awaitFooter(changeModelLabel, target.dropdownLabel(ConversationAgent.Claude))
            assertEquals("the saved model after the change", target.value, freshSettings(chat.id).model)
        } finally {
            restoreSettings(originals)
        }
    }

    /**
     * The slash-command suggestions and the Actions menu's Compact session still work after a cut-and-restore
     * of the phone's link (#967; #885, #884, #874).
     *  * **The commands come back.** After a real turn and the reconnect, typing `/` lists the commands the host
     *    publishes for the chat on the new connection, and picking the first puts its completion in the
     *    composer. The rows, labels and completion come from the production rules, not restated here.
     *  * **Compaction feedback.** Compact session shows the compacting indicator, then the divider for a
     *    compaction by you, and the indicator goes. The thread holds exactly one compaction divider (#1358):
     *    the boundary fills in the divider the falling edge drew rather than adding a second.
     *
     * **Two real-claude turns**: the ping and the compaction.
     */
    @Test
    fun interactiveTurn_reconnect_slashCommandsAndCompactStillWork() {
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))

        // 1. One real turn spawns claude, which publishes its commands; then cut and restore the link.
        awaitChannelList()
        awaitConnected()
        val (chatId, name) = answerChat(serverId, RECONNECT_COMMANDS_NAME_PREFIX)
        openChatRow(name)
        sendFromPhone(PING_PROMPT)
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
        cycleHostLink(serverId)

        // 2. AC-2: `/` lists the published commands; picking the first completes it into the composer. The
        //    menu is read off the host's live connection: the reconnect's fresh connection used to drop and be
        //    redialled when the daemon's connect-time burst overflowed the relay's per-phone outbox
        //    (pyrycode/pyrycode-relay#154), and the app follows any redial, so the read does too.
        val published =
            try {
                val live =
                    checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
                runBlocking {
                    withTimeout(THREAD_TIMEOUT_MS) {
                        live.coordinator.currentRepository.firstOnLive({ it.observeSlashCommandMenu(chatId) }) { it.rows.isNotEmpty() }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("the host published no slash commands for the chat after the reconnect", e)
            }
        val rows = slashCommandTypeAheadRows("/", published.rows)
        val labels = slashCommandOptions(rows).map { it.label }
        composeTestRule.onNode(hasSetTextAction()).performTextInput("/")
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(slashRow(labels.first())).fetchSemanticsNodes().isNotEmpty()
        }
        labels.take(SLASH_ROWS_CHECKED).forEachIndexed { index, label ->
            assertTrue(
                "the suggestions do not list published command ${index + 1} of ${labels.size}",
                composeTestRule.onAllNodes(slashRow(label)).fetchSemanticsNodes().isNotEmpty(),
            )
        }
        composeTestRule.onAllNodes(slashRow(labels.first())).onFirst().performClick()
        val completed = completeSlashCommand(rows.first())
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composerText() == completed }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the pick did not complete the command into the composer (${composerText().length} chars shown)", e)
        }
        composeTestRule.onAllNodes(slashRow(labels.first())).assertCountEquals(0)
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("")

        // 3. AC-2: Compact session shows compaction progress, then the divider for a compaction by you.
        openActions()
        val compact = actionRow { it == ComposerAction.CompactSession.label }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(compact).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            "Compact session is greyed out: the published menu proves /compact absent",
            composeTestRule.onAllNodes(compact and isEnabled()).fetchSemanticsNodes().isNotEmpty(),
        )
        composeTestRule.onAllNodes(compact).onFirst().performClick()
        val compacting = hasContentDescription(string(R.string.cd_thread_compacting))
        try {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.onAllNodes(compacting).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the compacting indicator never showed after Compact session", e)
        }
        val divider = hasText(COMPACTION_DIVIDER, substring = true) and hasAnyAncestor(hasScrollToNodeAction())
        try {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.onAllNodes(divider).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("no compaction divider followed the compacting indicator", e)
        }

        // #1358: the falling edge draws "Conversation compacted" first and the `compaction_boundary` after it
        // fills that same divider in, so wait for the filled-in text rather than reading the first one shown.
        fun dividerText(): String =
            composeTestRule
                .onAllNodes(divider)
                .onFirst()
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { dividerText().endsWith(COMPACTION_BY_YOU) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the divider does not credit the compaction to you", e)
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(compacting).fetchSemanticsNodes().isEmpty() }
        // One compaction leaves one divider: the boundary replaced the edge's divider rather than adding a second.
        val anyDivider =
            (hasText(COMPACTION_DIVIDER, substring = true) or hasText(COMPACTION_FAILED)) and hasAnyAncestor(hasScrollToNodeAction())
        composeTestRule.onAllNodes(anyDivider).assertCountEquals(1)
    }

    /**
     * Compact session from the Actions menu still compacts with a file pending, and takes the file with it
     * (#1460; #1348). The command carries the pending files, so real claude receives `/compact` followed by the
     * daemon's attachment block, which it reads as summary instructions. A ping first spawns claude, which
     * publishes `/compact` and so enables the row. Then:
     *  * the compacting indicator shows, a compaction divider follows and the indicator goes, as in
     *    [interactiveTurn_reconnect_slashCommandsAndCompactStillWork];
     *  * the composer's attachment strip no longer holds the file.
     *
     * **Two real-claude turns**: the ping and the compaction.
     */
    @Test
    fun interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip() {
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        try {
            // 1. A fresh chat with one real turn, so claude publishes /compact.
            awaitChannelList()
            awaitConnected()
            val (_, name) = answerChat(serverId, COMPACT_ATTACH_NAME_PREFIX)
            openChatRow(name)
            sendFromPhone(PING_PROMPT)
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)

            // 2. One small file waits in the strip.
            val fileName = ATTACH_FILE_PREFIX + "compact-${System.currentTimeMillis()}.txt"
            attachDocument(stub, fileName, "e2e1460 compact fixture\n".toByteArray(), inserted)

            // 3. Compact session shows the compacting indicator, then a compaction divider.
            openActions()
            val compact = actionRow { it == ComposerAction.CompactSession.label }
            composeTestRule.waitUntil(
                THREAD_TIMEOUT_MS,
            ) { composeTestRule.onAllNodes(compact and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            composeTestRule.onAllNodes(compact).onFirst().performClick()
            val compacting = hasContentDescription(string(R.string.cd_thread_compacting))
            try {
                composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.onAllNodes(compacting).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the compacting indicator never showed after Compact session with a file attached", e)
            }
            val divider = hasText(COMPACTION_DIVIDER, substring = true) and hasAnyAncestor(hasScrollToNodeAction())
            try {
                composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { composeTestRule.onAllNodes(divider).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("no compaction divider followed the compacting indicator with a file attached", e)
            }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(compacting).fetchSemanticsNodes().isEmpty() }

            // 4. The command took the file: the strip no longer holds it.
            val tile = hasContentDescription(fileName) and hasAnyAncestor(hasTestTag(ATTACHMENT_STRIP_TEST_TAG))
            try {
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(tile).fetchSemanticsNodes().isEmpty() }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the attachment strip still holds the file after Compact session", e)
            }
        } finally {
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
        }
    }

    /**
     * A background task real claude starts shows in the thread pill, opens the same
     * panel from the pill and top menu, and clears the pill when the task finishes (#1296, #967, #678). The phone asks for one backgrounded `sleep`; a
     * permission prompt for it is allowed through the main daemon's privileged peer, the #950 path. The peer's
     * recorded frames supply only timing and the task's identity: the count and the panel are read off the phone.
     *
     * **One real-claude turn**: the prompt that starts the task.
     */
    @Test
    fun interactiveTurn_backgroundTask_countsInActionsMenuAndPanel() {
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val peer = runningToolPeer()
        try {
            // 1. Claude starts a background task in a fresh chat.
            awaitChannelList()
            awaitConnected()
            val (chatId, name) = answerChat(serverId, BACKGROUND_NAME_PREFIX)
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            openChatRow(name)
            sendFromPhone(BACKGROUND_PROMPT)
            val allowed = mutableSetOf<String>()
            val startedFrame =
                allowPromptsUntil(
                    peer,
                    chatId,
                    REPLY_TIMEOUT_MS,
                    "claude started no background task",
                    allowed,
                    frame = "background_task_started",
                ) {
                    it.type ==
                        "background_task_started"
                }
            val started = MobileJson.decodeFromJsonElement(BackgroundTaskStartedPayloadDto.serializer(), startedFrame.payload)
            assertEquals("the background shell task must retain its raw wire type", "local_bash", started.taskType)

            // 2. The live thread pill opens the panel while the task is running.
            val taskPill =
                SemanticsMatcher("running task pill") { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.any {
                        Regex("[1-9]\\d* tasks? running").matches(it)
                    } == true
                }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(taskPill).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodes(taskPill).onFirst().performTouchInput { click() }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText(string(R.string.background_tasks_title))).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText("Command") and inBackgroundPanel()).fetchSemanticsNodes().isNotEmpty()
            }
            closeBackgroundTasks()

            // #1668: Actions keeps only composer actions; the top menu opens the live roster.
            openActions()
            composeTestRule.onAllNodes(actionRow { it.startsWith("Background tasks") }).assertCountEquals(0)
            composeTestRule.onNodeWithText("Compact session").assertIsDisplayed()
            composeTestRule.onNodeWithText("Knowledge capture").assertIsDisplayed()
            Espresso.pressBack()
            openBackgroundTasks()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText("Command") and inBackgroundPanel()).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onAllNodes(hasText(string(R.string.background_tasks_unreported))).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText(string(R.string.background_tasks_empty))).assertCountEquals(0)
            closeBackgroundTasks()

            // 4. Once the task finishes, the count is 0 and the panel no longer lists the task as live. It
            //    labels the task finished until the empty roster claude sends after a finish drops it (#677's
            //    `applyRoster`), then says there are no tasks; the live gate saw the empty roster win.
            allowPromptsUntil(
                peer,
                chatId,
                BACKGROUND_FINISH_TIMEOUT_MS,
                "the background task never finished",
                allowed,
                frame = "background_task_updated",
            ) { frame ->
                frame.type == "background_task_updated" &&
                    runCatching { MobileJson.decodeFromJsonElement(BackgroundTaskUpdatedPayloadDto.serializer(), frame.payload) }
                        .getOrNull()
                        ?.let { it.taskId == started.taskId && it.status.isNotEmpty() } == true
            }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(taskPill).fetchSemanticsNodes().isEmpty()
            }
            openBackgroundTasks()
            val finishedOrGone =
                (hasText(string(R.string.background_tasks_finished)) or hasText(string(R.string.background_tasks_empty))) and
                    inBackgroundPanel()
            try {
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                    composeTestRule.onAllNodes(finishedOrGone).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the panel neither labelled the finished task nor showed no tasks", e)
            }
            composeTestRule.onAllNodes(hasText(string(R.string.background_tasks_unreported))).assertCountEquals(0)
            closeBackgroundTasks()
        } finally {
            peer.close()
        }
    }

    /**
     * A running background task's progress shows on its panel card (#1076, #1044). Real claude starts a
     * `general-purpose` subagent, a `local_agent` task the daemon reports with the background-task frames. The
     * daemon sends a `background_task_progress` frame once the subagent's tool count advances by two (pyrycode
     * `docs/protocol-mobile.md`). The app drops progress when the task finishes, so the card is read while the
     * task still runs.
     *
     * The subagent runs in the foreground, as in the daemon's one measured `task_progress` capture, so the turn
     * stays open while it works. A backgrounded one also costs a second turn when its finish notice arrives.
     * The task is held open by work, not by a permission prompt: on this daemon the phone draws a prompt as a
     * dialog over the composer, which would cover the Actions footer. [BACKGROUND_PROGRESS_PROMPT] instead gives
     * the subagent a run of Read calls, one per message, on missing files inside the chat's working directory.
     * They need no permission and read no host content, and each still counts as a tool call. The daemon's claude
     * has no Glob tool: the first live runs asked for Glob, and the subagent made no call at all.
     *
     * The peer's recorded frames supply the timing, the task's identity and the descriptions the card may show;
     * the card itself is read off the phone. One card must carry both an activity line, a prefix of a recorded
     * progress description, and a meta line with a tools segment of any count. Only the progress block draws the
     * tools segment, so the task's opening description cannot pass for it.
     *
     * The hold does not depend on a fixed delay, only on the subagent taking its Read calls one at a time.
     * **One real-claude turn**: the prompt that starts the subagent.
     */
    @Test
    fun interactiveTurn_backgroundAgentProgress_showsOnRunningCard() {
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val peer = runningToolPeer()
        try {
            // 1. Claude starts a background subagent in a fresh chat.
            awaitChannelList()
            awaitConnected()
            val (chatId, name) = answerChat(serverId, BACKGROUND_PROGRESS_NAME_PREFIX)
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            openChatRow(name)
            sendFromPhone(BACKGROUND_PROGRESS_PROMPT)

            // 2. The peer records a progress frame for a task the daemon reported started. A timeout names the
            //    started-task count, which separates "claude started no task" from "the task never reported".
            try {
                allowPromptsUntil(
                    peer,
                    chatId,
                    BACKGROUND_PROGRESS_TIMEOUT_MS,
                    "no background_task_progress arrived for a started task",
                    frame = "background_task_progress",
                ) { frame -> frame.type == "background_task_progress" && progressFrames(peer, chatId).isNotEmpty() }
            } catch (e: AssertionError) {
                val recorded = peer.recorded(chatId)
                throw AssertionError(
                    "${e.message} (background tasks started: ${recorded.count { it.type == "background_task_started" }}; " +
                        "progress frames: ${recorded.count { it.type == "background_task_progress" }})",
                    e,
                )
            }
            awaitNoPromptDialog("a permission prompt still covers the thread")

            // 3. While the task runs, its card shows an activity line from a recorded frame and a tools segment.
            openBackgroundTasks()
            val tools = toolsSegmentPatterns()

            fun texts(node: SemanticsNode): List<String> =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            val progressCard =
                SemanticsMatcher("a card with a recorded activity line and a tools segment") { node ->
                    val prefixes =
                        progressFrames(peer, chatId)
                            .map { it.description.takeWhile { c -> !c.isISOControl() }.take(ACTIVITY_PREFIX_CHARS) }
                            .filter { it.isNotBlank() }
                    val shown = texts(node)
                    shown.any { line -> prefixes.any { line.contains(it) } } &&
                        shown.any { line -> tools.any { it.containsMatchIn(line) } }
                } and inBackgroundPanel()
            try {
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(progressCard).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: ComposeTimeoutException) {
                val anyTools =
                    composeTestRule.onAllNodes(inBackgroundPanel()).fetchSemanticsNodes().any { node ->
                        texts(node).any { line -> tools.any { it.containsMatchIn(line) } }
                    }
                throw AssertionError(
                    "the panel drew no running card with a recorded activity line and a tools segment " +
                        "(any tools segment shown: $anyTools; progress frames recorded: ${progressFrames(peer, chatId).size})",
                    e,
                )
            }
            closeBackgroundTasks()
        } finally {
            peer.close()
        }
    }

    /**
     * A real push wakes the backgrounded app for a turn that ended while it was away, posts one alert, and
     * the alert's tap opens that conversation's thread (#955, #685). The turn is held on a permission
     * prompt the #950 way while the phone is in front, so the prompt's own alert is spent in the
     * foreground. The app then goes to the background, which closes its host link. The peer allows the
     * command, and the turn ends while the phone is absent. The daemon asks the production relay to wake
     * the phone, and FCM delivers the data message. The wake reconnects, the missed `turn_end` replays,
     * and the notifier posts.
     *
     * LIVE only: the loopback relay cannot send FCM. **One real-claude turn.**
     */
    @Test
    fun interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread() {
        assumeLiveRelay()
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val ruleActivity = composeTestRule.activity
        val peer = runningToolPeer()
        val watch = CoroutineScope(Dispatchers.Default)
        var held: Pair<String, String>? = null
        try {
            cancelAlerts()
            // 1. A named chat with a real turn held on a permission prompt, while the phone is in front.
            awaitChannelList()
            awaitConnected()
            val connectedAt = SystemClock.elapsedRealtime()
            val (chatId, name) = answerChat(serverId, PUSH_TURN_NAME_PREFIX)
            peerStep(peer, "open") { peer.open(CONNECT_TIMEOUT_MS) }
            openChatRow(name)
            sendFromPhone(RUNNING_TOOL_PROMPT)
            val modalId =
                peerStep(peer, "await the held command's permission prompt") { peer.awaitPermissionModal(chatId, REPLY_TIMEOUT_MS) }
            held = chatId to modalId
            awaitPushRegistered(serverId, connectedAt)

            // 2. The app goes to the background; the turn ends while the phone is absent.
            val woke = sendAppToBackground(serverId, watch)
            peerStep(peer, "allow the prompt once and await its dismissal") { peer.allowOnce(modalId, THREAD_TIMEOUT_MS) }
            held = null
            peerStep(peer, "await the allowed turn's turn_end") { peer.awaitFrame(chatId, "turn_end", WAIT_TURN_TIMEOUT_MS) }

            // 3. AC-1: the push wakes the app and exactly one turn alert shows.
            val alert = awaitAlert(string(R.string.notification_turn_completed), woke)

            // 4. AC-1: the tap, the notification's own content intent, opens that conversation's thread.
            checkNotNull(alert.notification.contentIntent) { "the alert carries no tap" }.send()
            try {
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                    composeTestRule.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the alert's tap did not open the conversation's thread", e)
            }
        } finally {
            watch.cancel()
            // A failure before the allow must not leave a claude turn waiting on the prompt for later scenarios.
            held?.let { (chatId, modalId) ->
                runBlocking {
                    runCatching {
                        peer.allowOnce(modalId, THREAD_TIMEOUT_MS)
                        peer.awaitFrame(chatId, "turn_end", WAIT_TURN_TIMEOUT_MS)
                    }
                }
            }
            peer.close()
            cancelAlerts()
            finishActivitiesBesides(ruleActivity)
        }
    }

    /**
     * A permission prompt that surfaces while the app is in the background is alerted exactly once, even
     * across a second reconnect inside the push's wake window (#955, #685). The phone creates a named chat
     * and goes to the background. The peer then starts a turn in that chat whose command waits on a
     * permission prompt. The prompt surfaces while the phone is absent, so the daemon wakes the phone and
     * the wake posts the alert. The test then cuts and restores the host link, and the daemon shows the
     * still-outstanding prompt again. That repeat must not post again, so one notification stays, with
     * the same post time. Since #1337 the new connection clears the host's held prompts, so the source
     * re-emits the re-shown prompt's alert and the notifier's ledger drops the repeat. A second `notify`
     * for the same tag would replace the notification and change its post time, which a count alone
     * cannot see.
     *
     * LIVE only: the loopback relay cannot send FCM. **One real-claude turn**: the peer's held command.
     */
    @Test
    fun interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect() {
        assumeLiveRelay()
        val serverId = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))
        val peer = runningToolPeer()
        val watch = CoroutineScope(Dispatchers.Default)
        var held: Pair<String, String>? = null
        try {
            cancelAlerts()
            // 1. A named chat, the peer open and the push token on the daemon, while the phone is in front.
            awaitChannelList()
            awaitConnected()
            val connectedAt = SystemClock.elapsedRealtime()
            val (chatId, _) = answerChat(serverId, PUSH_PROMPT_NAME_PREFIX)
            // Exercise the suite's shared-token binding even when this method runs alone (#1694, #1698).
            runningToolPeer().use { prior ->
                peerStep(prior, "open prior prompt peer") { prior.open(CONNECT_TIMEOUT_MS) }
            }
            peerStep(peer, "open prompt peer") { peer.open(CONNECT_TIMEOUT_MS) }
            awaitPushRegistered(serverId, connectedAt)

            // 2. The app goes to the background; the peer's turn raises a prompt while the phone is absent.
            val woke = sendAppToBackground(serverId, watch)
            peerStep(peer, "send background prompt message") { peer.sendMessage(chatId, RUNNING_TOOL_PROMPT, THREAD_TIMEOUT_MS) }
            val modalId = peerStep(peer, "await background permission modal") { peer.awaitPermissionModal(chatId, REPLY_TIMEOUT_MS) }
            held = chatId to modalId

            // 3. AC-2: the push wakes the app and exactly one prompt alert shows.
            val first = awaitAlert(string(R.string.notification_prompt), woke)

            // 4. AC-2: a second reconnect inside the wake window re-shows the prompt, and nothing is posted again.
            // The source re-emits the re-shown prompt's alert (#1337) and the notifier's ledger drops it, so nothing
            // observable marks its arrival: settle long enough for `modal_shown` to reach the phone and the notifier.
            cycleHostLink(serverId)
            SystemClock.sleep(RECONNECT_SETTLE_MS)
            val after = attentionAlerts()
            assertEquals("alerts after the reconnect", 1, after.size)
            assertEquals("the prompt's alert was posted again across the reconnect", first.postTime, after.single().postTime)
        } finally {
            watch.cancel()
            held?.let { (chatId, modalId) ->
                runBlocking {
                    runCatching {
                        peer.allowOnce(modalId, THREAD_TIMEOUT_MS)
                        peer.awaitFrame(chatId, "turn_end", WAIT_TURN_TIMEOUT_MS)
                    }
                }
            }
            peer.close()
            cancelAlerts()
        }
    }

    /** The push scenarios (#955) need the production relay, which alone can send FCM. */
    private fun assumeLiveRelay() {
        val relayUrl = InstrumentationRegistry.getArguments().getString(ARG_RELAY_URL).orEmpty()
        assumeTrue("push needs the production relay (LIVE=1); the loopback relay cannot send FCM", relayUrl.startsWith("wss://"))
    }

    /**
     * Make sure the daemon holds this phone's FCM token and can wake it now (#955). The token comes from
     * Play services. Cycling the link then makes the #365 connect-time registration send it. The daemon
     * sends a device no second wake within 30 s of the last one. The phone has been connected since
     * [connectedAt], so no wake has reached it since then, and waiting out the rest of the window keeps
     * an earlier scenario's wake from absorbing this one.
     */
    private fun awaitPushRegistered(
        serverId: String,
        connectedAt: Long,
    ) {
        val preferences = GlobalContext.get().get<AppPreferences>()
        val deadline = SystemClock.elapsedRealtime() + PUSH_TOKEN_TIMEOUT_MS
        // A fresh read each pass: a collector that starts during the first write can miss it (#968).
        while (runBlocking { preferences.pushToken.first() }.isNullOrEmpty()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw AssertionError("no FCM token stored; an in-process token request: ${fcmTokenRequestOutcome()} (#1102)")
            }
            SystemClock.sleep(POLL_MS)
        }
        cycleHostLink(serverId)
        val remaining = connectedAt + PUSH_WAKE_COALESCE_MS - SystemClock.elapsedRealtime()
        if (remaining > 0) SystemClock.sleep(remaining)
    }

    /** What asking FCM for the current token returns right now, so a repeat carries its own cause. Never the token. */
    private fun fcmTokenRequestOutcome(): String {
        val source = GlobalContext.get().get<PushTokenSource>()
        if (!source.isAvailable()) return "not made, no FirebaseApp in this process"
        val result = runBlocking { withTimeoutOrNull(PUSH_TOKEN_TIMEOUT_MS) { runCatching { source.currentToken() } } }
        return when {
            result == null -> "did not complete within ${PUSH_TOKEN_TIMEOUT_MS}ms"
            result.isSuccess -> "returned a token, but none was stored"
            else -> result.exceptionOrNull().let { "failed with ${it?.javaClass?.name}: ${it?.message}" }
        }
    }

    /**
     * Go Home, as the operator does when leaving the app, and wait until the app's host link closes. The
     * `google-atd` image has a Home activity but no Settings activity. `ActivityScenario.moveToState`
     * would put an androidx.test activity in front, in this process, and the process would still count
     * as started. Returns a flag that turns true once a wake reopens the link, set by a watcher launched in
     * [watch], which the caller cancels when the scenario ends.
     */
    private fun sendAppToBackground(
        serverId: String,
        watch: CoroutineScope,
    ): AtomicBoolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val home = "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"
        val started =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(home)).use {
                it.readBytes().decodeToString()
            }
        if (started.contains("Error")) throw AssertionError("Home did not start: $started")
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        // The awaited value is itself null, so the block returns a flag: withTimeoutOrNull's null means only a timeout.
        runBlocking {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                bundle.coordinator.currentRepository
                    .first { it == null }
                    .let { true }
            }
        }
            ?: throw AssertionError("the app did not go to the background: its host link stayed open ($started)")
        val woke = AtomicBoolean(false)
        watch.launch {
            withTimeoutOrNull(PUSH_TIMEOUT_MS + WAIT_TURN_TIMEOUT_MS) {
                bundle.coordinator.currentRepository.first { it != null }
                woke.set(true)
            }
        }
        return woke
    }

    /** The app's posted attention alerts: `activeNotifications` lists only this app's own. */
    private fun attentionAlerts(): List<StatusBarNotification> =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.notification.channelId == ATTENTION_CHANNEL_ID }

    private fun cancelAlerts() {
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSystemService(NotificationManager::class.java)
            .cancelAll()
    }

    /**
     * Wait for the first alert and assert it is the only one and reads [text]. The failure says whether a
     * wake ever reopened the host link: if it did not, the push never arrived.
     */
    private fun awaitAlert(
        text: String,
        woke: AtomicBoolean,
    ): StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + PUSH_TIMEOUT_MS
        while (attentionAlerts().isEmpty()) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw AssertionError(
                    if (woke.get()) {
                        "a push woke the app, but no alert was posted"
                    } else {
                        "no push woke the app: its host link never reopened (relay, FCM project or daemon older than v0.23.0?)"
                    },
                )
            }
            SystemClock.sleep(POLL_MS)
        }
        val alerts = attentionAlerts()
        assertEquals("alerts posted", 1, alerts.size)
        assertEquals(
            "the alert's text",
            text,
            alerts
                .single()
                .notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString(),
        )
        return alerts.single()
    }

    /** Finish the activities a tap started. The tap's `CLEAR_TASK` replaced the rule's own, so the rule cannot. */
    private fun finishActivitiesBesides(ruleActivity: MainActivity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }
                .filter { it !== ruleActivity }
                .forEach { it.finish() }
        }
    }

    /**
     * Files the phone attaches reach another client with their exact bytes (#1016, rung 3). In chat X the
     * phone pastes a small PNG, replaces the clipboard, then picks a ~100 KB document through **Attach files** — the
     * system picker answered by an [ActivityIntentStub] with `MediaStore` URIs, since the app refuses any
     * authority of its own, the test APK's included — and sends one message. Then the [SecondClientPeer], the
     * desktop stand-in, sees exactly what the desktop would:
     *  * X's history holds exactly one user message, naming two attachment ids;
     *  * `request_attachment` for each of those ids returns bytes whose SHA-256 digests are the two fixtures'
     *    digests;
     *  * chat Y on the same host gains no message.
     * The document spans three 45000-byte chunks, so the phone's chunking and the daemon's reassembly both run.
     * The turn may Read the named files; the peer allows each prompt until the turn ends.
     *
     * The name places it last in JUnit's default order, which sorts by name hash. Peers opened on the first
     * daemon after it stopped carrying frames in two live runs: by then the daemon held 15–23 sessions, and its
     * connect-time reconcile burst overflowed the relay's per-phone outbox, which closed every new connection
     * within a second of its handshake (pyrycode/pyrycode-relay#154).
     *
     * **One real-claude turn**: the phone's message.
     */
    @Test
    fun interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        try {
            val stamp = System.currentTimeMillis()
            val png = pngFixture()
            val document = documentFixture("phone-$stamp")
            val pngName = ATTACH_FILE_PREFIX + "phone-$stamp.png"
            val documentName = ATTACH_FILE_PREFIX + "phone-$stamp.txt"
            val picked =
                listOf(insertDownload(pngName, "image/png", png, inserted), insertDownload(documentName, TEXT_MIME, document, inserted))
            stub.answer(Intent.ACTION_OPEN_DOCUMENT) {
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { clipData = ClipData.newRawUri(null, picked.last()) })
            }

            // 1. The peer records from here on; X and Y are fresh named chats, and the phone opens X.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, ATTACH_CHAT_NAME_PREFIX)
            val (chatY, _) = answerChat(serverId, ATTACH_OTHER_NAME_PREFIX)
            assertPeerAnswers(peer, chatX)
            openChatRow(nameX)

            // 2. Paste the PNG while its clipboard grant is valid; only the document uses the picker.
            val clipboard = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
            composeTestRule.runOnUiThread {
                clipboard.setPrimaryClip(ClipData.newUri(instrumentation.targetContext.contentResolver, "image", picked.first()))
            }
            composeTestRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(pngName)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("replacement", "copied after paste")) }
            composeTestRule.onNode(hasContentDescription(attachFilesLabel)).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                listOf(pngName, documentName).all {
                    composeTestRule.onAllNodes(hasContentDescription(it)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            sendFromPhone(PING_PROMPT)

            // 3. The message reaches claude, and its turn ends: the host logs the user turn on delivery.
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the attachment turn in X did not end") { it.type == "turn_end" }

            // 4. AC-1: the peer's view of X holds one user message naming two ids, each fetching its fixture's
            // exact bytes; Y gained nothing.
            val named = userMessageAttachmentIds(runBlocking { peer.history(chatX, THREAD_TIMEOUT_MS) })
            assertEquals("user messages in the peer's view of X", 1, named.size)
            val ids = named.single()
            assertEquals("attachment ids named by X's user message", 2, ids.distinct().size)
            val digests = ids.map { id -> sha256(runBlocking { peer.retrieveAttachment(chatX, id, REPLY_TIMEOUT_MS) }.bytes) }
            assertEquals("digests of the files the peer fetched", setOf(sha256(png), sha256(document)), digests.toSet())
            assertEquals("user messages in the other conversation", 0, userMessages(runBlocking { peer.history(chatY, THREAD_TIMEOUT_MS) }))
        } finally {
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
            peer.close()
        }
    }

    /** Android share intake captures both sources before destination selection; one real Claude turn. */
    @Test
    fun interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val stamp = System.currentTimeMillis()
            val png = pngFixture()
            val document = documentFixture("shared-$stamp")
            val pngName = ATTACH_FILE_PREFIX + "shared-$stamp.png"
            val documentName = ATTACH_FILE_PREFIX + "shared-$stamp.txt"
            val sources =
                arrayListOf(
                    insertDownload(pngName, "image/png", png, inserted),
                    insertDownload(documentName, TEXT_MIME, document, inserted),
                )
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, ATTACH_CHAT_NAME_PREFIX)
            val (chatY, _) = answerChat(serverId, ATTACH_OTHER_NAME_PREFIX)
            assertPeerAnswers(peer, chatX)
            val context = instrumentation.targetContext
            composeTestRule.runOnUiThread {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_SEND_MULTIPLE
                        type = "*/*"
                        putExtra(Intent.EXTRA_TEXT, PING_PROMPT)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, sources)
                        clipData = ClipData.newRawUri("share", sources.first()).apply { addItem(ClipData.Item(sources.last())) }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText("2 files")).fetchSemanticsNodes().isNotEmpty()
            }
            // Readiness, not only the progressive preview: wait for capture to finish before tapping.
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag("share-ready")).fetchSemanticsNodes().isNotEmpty()
            }
            deleteFixtures(inserted)
            sources.forEach { uri ->
                assertTrue(
                    "the original share source must be unreadable",
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() } }.getOrNull() == null,
                )
            }
            openChatRow(nameX)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                listOf(
                    pngName,
                    documentName,
                ).all { composeTestRule.onAllNodes(hasContentDescription(it)).fetchSemanticsNodes().isNotEmpty() }
            }
            composeTestRule.onNode(hasSetTextAction()).assertTextContains(PING_PROMPT)
            assertEquals("no automatic send in X", 0, userMessages(runBlocking { peer.history(chatX, THREAD_TIMEOUT_MS) }))
            composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the shared-content turn in X did not end") { it.type == "turn_end" }
            val history = runBlocking { peer.history(chatX, THREAD_TIMEOUT_MS) }
            val named = userMessageAttachmentIds(history)
            assertEquals("user messages in X", 1, named.size)
            val ids = named.single()
            assertEquals("attachment ids in X", 2, ids.distinct().size)
            val digests = ids.map { sha256(runBlocking { peer.retrieveAttachment(chatX, it, REPLY_TIMEOUT_MS) }.bytes) }
            assertEquals(setOf(sha256(png), sha256(document)), digests.toSet())
            val payload = history.single { it.isUserMessage() }.payload as JsonObject
            assertEquals("peer received the shared text", PING_PROMPT, payload["text"]?.jsonPrimitive?.content)
            assertEquals("messages in Y", 0, userMessages(runBlocking { peer.history(chatY, THREAD_TIMEOUT_MS) }))
        } finally {
            deleteFixtures(inserted)
            peer.close()
        }
    }

    /**
     * A dormant channel shows its stored history when opened, with no pull and no send (#1571, rung 3). The
     * live daemon cannot be restarted mid-run, so `scripts/e2e-emulator.sh` seeds the after-restart state before
     * it starts: a promoted row named [dormantName][ARG_DORMANT_NAME], bound to a session the daemon does not
     * hold, and one finished turn in that conversation's on-disk history log ending in
     * [dormantReply][ARG_DORMANT_REPLY]. The phone has never loaded the run-unique conversation, so the reply
     * can only come from the history page the open thread asks for by itself (#1569, #1572). Before those, the
     * thread opened empty until a send woke the session.
     *
     * **Zero real-claude turns**: opening the thread spawns nothing, and the test never sends or pulls.
     */
    @Test
    fun interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend() {
        val conversationId = dormantArg(ARG_DORMANT_CONVERSATION_ID)
        val name = dormantArg(ARG_DORMANT_NAME)
        val reply = hasText(dormantArg(ARG_DORMANT_REPLY)) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))

        // 1. The seeded row is on the list, and host A really holds it under the seeded id.
        awaitChannelList()
        awaitConnected()
        awaitChannelRow(name)
        assertHostHoldsConversation(twoHostArg(ARG_SERVER_ID), conversationId, name)
        composeTestRule.onAllNodes(reply).assertCountEquals(0)

        // 2. Open it and wait for the stored reply, with no pull toward older messages and no send.
        openRow(name)
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodes(reply).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(reply).onFirst().assertIsDisplayed()
    }

    /** A dormant-channel instrumentation argument (#1571), failing with the script that passes it. */
    private fun dormantArg(key: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString(key)) {
            "missing instrumentation arg '$key' — scripts/e2e-emulator.sh seeds it on rung 3 and LIVE"
        }

    /**
     * Another client's file opens and saves on the phone after a history reload (#1016, rung 3). The
     * [SecondClientPeer] uploads a ~100 KB document into chat X — three chunks, so the phone's reassembly
     * runs — and names it on a message, as the desktop does. The phone never opens X before a restart
     * ([E2eTestApplication.rebuildGraph]) with X's thread cache cleared, so X's rows can only come from
     * history replay, whose user `message` entry keeps the id but no name (#1020). Opening X asks for no
     * history, so the test pulls toward older messages once X is open (#1352). Then:
     *  * the row shows the uploaded filename, which only retrieval supplies, exactly once;
     *  * a tap hands `ACTION_VIEW` a content URI whose bytes have the fixture's digest;
     *  * a long-press writes the same bytes to the `ACTION_CREATE_DOCUMENT` target.
     * Both system activities are answered by an [ActivityIntentStub]; the save target is a `MediaStore` entry.
     *
     * **One real-claude turn**: the peer's message.
     */
    @Test
    fun interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        var relaunched: ActivityScenario<MainActivity>? = null
        try {
            val stamp = System.currentTimeMillis()
            val document = documentFixture("peer-$stamp")
            val documentName = ATTACH_FILE_PREFIX + "peer-$stamp.txt"

            // 1. X is a fresh named chat the phone does not open; the peer uploads into it and names the file.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, ATTACH_CHAT_NAME_PREFIX)
            runBlocking {
                val id = peer.uploadAttachment(chatX, documentName, TEXT_MIME, document, REPLY_TIMEOUT_MS)
                peer.sendMessage(chatX, PING_PROMPT, THREAD_TIMEOUT_MS, attachmentIds = listOf(id))
            }
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the peer's attachment turn in X did not end") { it.type == "turn_end" }

            // 2. Restart with X's thread cache cleared, so the row can only come from history replay.
            relaunched =
                restartApp {
                    val cache = GlobalContext.get().get<ConversationCache>()
                    runBlocking {
                        cache.writeThread(serverId, chatX, emptyList()).getOrThrow()
                        assertTrue("X's thread cache was not cleared", cache.readThread(serverId, chatX).isEmpty())
                    }
                }
            awaitChannelList()
            awaitConnected()
            openChatRow(nameX)

            // 3. AC-2: pull for X's history; the retrieved name shows once, and open and save both carry the
            //    fixture's bytes.
            awaitReadyAttachmentRow(documentName, REPLY_TIMEOUT_MS, poll = ::pullForOlderHistory)
            composeTestRule.onAllNodes(readyAttachmentRow(documentName)).assertCountEquals(1)
            assertOpensAndSaves(stub, documentName, sha256(document), inserted)
        } finally {
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
            peer.close()
            relaunched?.close()
        }
    }

    /**
     * A file claude offers stays on the phone across a restart (#1016, rung 3). With the phone in chat X, the
     * phone's message has claude write a file of known content with a shell command and hand it over with
     * the daemon's `send_file` tool (the `pyry_files` MCP server), which only takes a path inside the
     * conversation's workspace. The [SecondClientPeer] allows each permission prompt until the turn ends.
     *  * the peer's `attachment_offered` for X announces the file's name;
     *  * the phone draws an attachment row with that name, and its thread cache holds it as an assistant row;
     *  * after [E2eTestApplication.rebuildGraph] the row is still there once, and open and save both yield
     *    the content's digest.
     * The offer is live-only, with no replay: after the restart the row comes from the phone's own thread
     * cache. A fresh device, or a cleared cache, would not show it, by design, and that is not asserted.
     * The offer names a `.txt` file with no type, so the row is not fetched until [assertOpensAndSaves] taps it
     * (#1329): the tap loads the file and opens it, and the long-press then saves the loaded file.
     *
     * The name places it before the background-task scenario in JUnit's default order, which sorts by name
     * hash. In two live runs every peer opened before that point carried frames.
     *
     * **One real-claude turn**: the phone's message.
     */
    @Test
    fun interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        var relaunched: ActivityScenario<MainActivity>? = null
        try {
            val stamp = System.currentTimeMillis()
            val content = OFFER_CONTENT_PREFIX + stamp
            val fileName = ATTACH_FILE_PREFIX + "offer-$stamp.txt"

            // 1. The phone is attached to X when claude calls the tool: the offer is never replayed.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, ATTACH_CHAT_NAME_PREFIX)
            assertPeerAnswers(peer, chatX)
            openChatRow(nameX)
            sendFromPhone(offerPrompt(content, fileName))
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the send_file turn in X did not end") { it.type == "turn_end" }

            // 2. AC-3: the offer names the file, and the phone draws it as an assistant-side row it caches.
            val offer =
                peer.recorded(chatX).firstOrNull { it.type == "attachment_offered" }?.let {
                    MobileJson.decodeFromJsonElement(AttachmentOfferedPayloadDto.serializer(), it.payload)
                }
            checkNotNull(offer) { "claude's turn in X offered no file" }
            assertTrue("the offer announced another file name", offer.filename == fileName)
            awaitReadyAttachmentRow(fileName, REPLY_TIMEOUT_MS)
            awaitCachedOffer(serverId, chatX, offer.attachmentId)

            // 3. AC-3: after a restart the cached row is still there once, and open and save carry the content.
            leaveThread()
            relaunched = restartApp()
            awaitChannelList()
            awaitConnected()
            openChatRow(nameX)
            awaitReadyAttachmentRow(fileName, REPLY_TIMEOUT_MS)
            composeTestRule.onAllNodes(readyAttachmentRow(fileName)).assertCountEquals(1)
            assertOpensAndSaves(stub, fileName, sha256(content.toByteArray()), inserted)
        } finally {
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
            peer.close()
            relaunched?.close()
        }
    }

    /**
     * An upload whose link drops recovers into exactly one message with its bytes (#1017, rung 3). In chat X
     * the phone attaches a ~100 KB document (three chunks) and sends [PING_PROMPT]. The link is cut the moment
     * chunk [CUT_AFTER_CHUNK] has been handed to the socket ([cutLinkOn]). The last chunk is never sent, so
     * the daemon can neither complete the file nor acknowledge it: a cut that cannot race `attachment_stored`.
     *  * **The send fails and keeps everything.** The composer still holds the text and the file, and the
     *    peer's view of X holds no user message.
     *  * **The retry sends once.** With the link restored and Send tapped again, the peer's view of X holds
     *    exactly one user message, and `request_attachment` for the one id the phone named returns the
     *    fixture's bytes. The id is the one the peer's history names on that message (#1020).
     *
     * **One real-claude turn**: the retried message.
     */
    @Test
    fun interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        var cut: LinkCut? = null
        try {
            val stamp = System.currentTimeMillis()
            val document = documentFixture("upload-$stamp")
            val documentName = INTERRUPT_FILE_PREFIX + "upload-$stamp.txt"
            val totalChunks = (document.size + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES
            check(totalChunks > CUT_AFTER_CHUNK + 1) { "the document fixture has no chunk after the cut" }

            // 1. The peer records from here on; the phone opens a fresh chat X and attaches the document.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, INTERRUPT_CHAT_NAME_PREFIX)
            assertPeerAnswers(peer, chatX)
            openChatRow(nameX)
            attachDocument(stub, documentName, document, inserted)

            // 2. AC-1: send, and cut the link once chunk CUT_AFTER_CHUNK is sent. The send fails and keeps both.
            cut = cutLinkOn(serverId) { it.startsWith(CHUNK_SENT_EVENT) && it.endsWith(" index=$CUT_AFTER_CHUNK total=$totalChunks") }
            sendFromPhone(PING_PROMPT)
            cut.await("the upload never sent chunk $CUT_AFTER_CHUNK")
            cut.close()
            awaitComposerHolds(PING_PROMPT, documentName)
            assertEquals(
                "user messages in the peer's view of X after the failed send",
                0,
                userMessages(
                    runBlocking {
                        peer.history(chatX, THREAD_TIMEOUT_MS)
                    },
                ),
            )

            // 3. AC-1: restore the link and send again; the message reaches claude and its turn ends.
            setHostLink(serverId, up = true)
            awaitConnected()
            val send = hasContentDescription(CD_SEND_MESSAGE) and isEnabled()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(send).fetchSemanticsNodes().isNotEmpty() }
            composeTestRule.onAllNodes(send).onFirst().performClick()
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the retried attachment turn in X did not end") { it.type == "turn_end" }

            // 4. AC-1: the peer's view of X holds one user message naming one id, which fetches the fixture's bytes.
            val named = userMessageAttachmentIds(runBlocking { peer.history(chatX, THREAD_TIMEOUT_MS) })
            assertEquals("user messages in the peer's view of X", 1, named.size)
            val ids = named.single()
            assertEquals("attachment ids named by X's user message", 1, ids.size)
            val fetched = runBlocking { peer.retrieveAttachment(chatX, ids.single(), REPLY_TIMEOUT_MS) }
            assertEquals("digest of the file the peer fetched", sha256(document), sha256(fetched.bytes))
        } finally {
            cut?.close()
            runCatching { setHostLink(serverId, up = true) }
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
            peer.close()
        }
    }

    /**
     * A retrieval whose link drops fails visibly, and Retry recovers it (#1017, rung 3). The [SecondClientPeer]
     * uploads a ~100 KB document into chat X, which the phone does not open, and names it on a message, as
     * the desktop does. The phone restarts with X's thread cache cleared, so X's row can only come from
     * history replay (#1020), which keeps the id but no name. Opening X asks for no history, so the test pulls
     * toward older messages once X is open (#1352). The link is cut at the retrieval's request for that id
     * ([cutLinkOn]), before the request is sent.
     *  * **Failed with Retry.** The row, unnamed since only retrieval supplies the name, shows the failed state
     *    and its Retry control.
     *  * **Retry recovers.** With the link restored, Retry brings the row to ready under the uploaded name, and
     *    opening it (and saving it) yields the fixture's digest.
     *
     * **One real-claude turn**: the peer's message.
     */
    @Test
    fun interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        var relaunched: ActivityScenario<MainActivity>? = null
        var cut: LinkCut? = null
        try {
            val stamp = System.currentTimeMillis()
            val document = documentFixture("retrieval-$stamp")
            val documentName = INTERRUPT_FILE_PREFIX + "retrieval-$stamp.txt"

            // 1. X is a fresh named chat the phone does not open; the peer uploads into it and names the file.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, INTERRUPT_CHAT_NAME_PREFIX)
            val id =
                runBlocking {
                    val id = peer.uploadAttachment(chatX, documentName, TEXT_MIME, document, REPLY_TIMEOUT_MS)
                    peer.sendMessage(chatX, PING_PROMPT, THREAD_TIMEOUT_MS, attachmentIds = listOf(id))
                    id
                }
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the peer's attachment turn in X did not end") { it.type == "turn_end" }

            // 2. Restart with X's thread cache cleared, then open X with the cut armed for that id's request.
            relaunched =
                restartApp {
                    val cache = GlobalContext.get().get<ConversationCache>()
                    runBlocking {
                        cache.writeThread(serverId, chatX, emptyList()).getOrThrow()
                        assertTrue("X's thread cache was not cleared", cache.readThread(serverId, chatX).isEmpty())
                    }
                }
            awaitChannelList()
            awaitConnected()
            cut = cutLinkOn(serverId) { it.startsWith(RETRIEVAL_REQUEST_EVENT + "id=$id") }
            openChatRow(nameX)
            // Pull for X's history until its row is drawn. The row loads once it is drawn, so keep it on screen
            // until the cut.
            cut.await("the phone never requested the peer's file") {
                if (composeTestRule.onAllNodes(hasTestTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)).fetchSemanticsNodes().isEmpty()) {
                    pullForOlderHistory()
                }
                runCatching { scrollListTo(hasTestTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)) }
            }
            cut.close()

            // 3. AC-2: the row, still unnamed, shows the failed state with its Retry.
            val unnamed = string(R.string.thread_attachment_unnamed)
            val retry = attachmentRetry(unnamed)
            try {
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                    composeTestRule.onAllNodes(retry, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                        composeTestRule
                            .onAllNodes(inAttachmentRow(unnamed, hasText(attachmentFailedText)), useUnmergedTree = true)
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("the interrupted row showed no failed state with Retry", e)
            }

            // 4. AC-2: with the link back, Retry brings the row to ready, and it opens with the fixture's bytes.
            setHostLink(serverId, up = true)
            awaitConnected()
            composeTestRule.onAllNodes(retry, useUnmergedTree = true).onFirst().performClick()
            awaitReadyAttachmentRow(documentName, REPLY_TIMEOUT_MS)
            composeTestRule.onAllNodes(readyAttachmentRow(documentName)).assertCountEquals(1)
            assertOpensAndSaves(stub, documentName, sha256(document), inserted)
        } finally {
            cut?.close()
            runCatching { setHostLink(serverId, up = true) }
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
            peer.close()
            relaunched?.close()
        }
    }

    /**
     * A file the phone sends stays on its host when a second host holds the same conversation id (#1017,
     * rung 3). It uses #847's seeded collision: one conversation id on both test daemons. The harness binds
     * host A's copy to a session the daemon revives on the first send; an unbound copy refuses every send
     * (`no_bound_session`). Host B is paired by code, as #847 does, and removed in `finally`. Each copy's current name is read by id, since #847's method
     * renames host A's copy. The peer is on host A.
     *  * **The pending file stays with A.** While A's composer holds the file, B's copy shows no tile for it.
     *  * **The sent file is on A.** After the send, the peer's view of A's copy holds exactly one user
     *    message, and the one id the phone named fetches the fixture's bytes there.
     *  * **Nothing reaches B.** B's copy shows no row and no tile with the file's name, B's thread cache names
     *    no such id, and host B itself answers the id as not found.
     *
     * **One real-claude turn**: the phone's message on host A.
     */
    @Test
    fun interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost() {
        val serverIdA = twoHostArg(ARG_SERVER_ID)
        val serverIdB = twoHostArg(ARG_SERVER_ID_B)
        val collisionId = twoHostArg(ARG_COLLISION_CONVERSATION_ID)
        val peer = runningToolPeer()
        val stub = ActivityIntentStub()
        val inserted = mutableListOf<Uri>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(stub)
        try {
            val stamp = System.currentTimeMillis()
            val document = documentFixture("collision-$stamp")
            val documentName = INTERRUPT_FILE_PREFIX + "collision-$stamp.txt"

            // 1. Pair host B as #847 does, and read each copy's current name by the shared id.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
            pairHostByCode(twoHostArg(ARG_PAIR_CODE_B))
            val nameA = heldName(serverIdA, collisionId)
            val nameB = heldName(serverIdB, collisionId)
            assertNotEquals("the two copies' names", nameA, nameB)
            assertPeerAnswers(peer, collisionId)

            // 2. AC-3: A's composer holds the file; B's copy shows no tile for it; A's still does.
            openRow(nameA)
            attachDocument(stub, documentName, document, inserted)
            leaveThread()
            openRow(nameB)
            assertNoFileNamed(documentName, "host B's copy while A's composer holds the file")
            leaveThread()
            openRow(nameA)
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasContentDescription(documentName)).fetchSemanticsNodes().isNotEmpty()
            }

            // 3. AC-3: send on A; the turn ends; A's copy holds one user message and the file's bytes.
            sendFromPhone(PING_PROMPT)
            allowPromptsUntil(peer, collisionId, WAIT_TURN_TIMEOUT_MS, "the attachment turn in A's copy did not end") {
                it.type ==
                    "turn_end"
            }
            val named = userMessageAttachmentIds(runBlocking { peer.history(collisionId, THREAD_TIMEOUT_MS) })
            assertEquals("user messages in the peer's view of A's copy", 1, named.size)
            val ids = named.single()
            assertEquals("attachment ids named by A's user message", 1, ids.size)
            val id = ids.single()
            val fetched = runBlocking { peer.retrieveAttachment(collisionId, id, REPLY_TIMEOUT_MS) }
            assertEquals("digest of the file the peer fetched from host A", sha256(document), sha256(fetched.bytes))
            awaitReadyAttachmentRow(documentName, THREAD_TIMEOUT_MS)

            // 4. AC-3: B's copy shows nothing of it, B's cache names no such id, and host B does not hold it.
            leaveThread()
            openRow(nameB)
            assertNoFileNamed(documentName, "host B's copy after A's send")
            val cachedOnB =
                runBlocking { GlobalContext.get().get<ConversationCache>().readThread(serverIdB, collisionId) }.any { item ->
                    item is ThreadItem.MessageItem && item.message.attachments.any { it.attachmentId == id }
                }
            assertTrue("host B's thread cache names the file sent on host A", !cachedOnB)
            val onB = runBlocking { withTimeout(REPLY_TIMEOUT_MS) { hostRepository(serverIdB).fetchAttachment(collisionId, id) } }
            assertEquals("host B's answer for the file sent on host A", AttachmentRetrievalResult.NotFound, onB)
            leaveThread()
        } finally {
            runBlocking { GlobalContext.getOrNull()?.get<PairedServerCollectionStore>()?.remove(serverIdB) }
            instrumentation.removeMonitor(stub)
            deleteFixtures(inserted)
            peer.close()
        }
    }

    /**
     * A markdown link in claude's reply opens the note in the in-app reader, read live (#1050, rung 3). Claude
     * writes a markdown note in its workspace with one `printf` and replies with a link to it. A tap on the
     * link shows the note's heading and its file name in the reader, with the thread's composer gone. While the
     * reader stays open, the peer has claude rewrite the note, and the reader's Refresh (#1067) shows the new
     * heading and not the old one. Back returns to the thread, and the same link shows the new heading too:
     * the reader reads the host's file on every open and keeps nothing between opens.
     *
     * **Two real-claude turns**: the note and the rewrite.
     */
    @Test
    fun interactiveTurn_markdownLink_opensLiveNoteInReader() {
        val serverId = twoHostArg(ARG_SERVER_ID)
        val peer = runningToolPeer()
        try {
            val stamp = System.currentTimeMillis()
            val fileName = NOTE_FILE_PREFIX + "$stamp.md"
            val linkText = NOTE_LINK_PREFIX + stamp
            val first = NOTE_MARKER_PREFIX + "$stamp-first"
            val second = NOTE_MARKER_PREFIX + "$stamp-second"
            val allowed = mutableSetOf<String>()

            // 1. A fresh chat X; claude writes the note and replies with a link to it.
            runBlocking { peer.open(CONNECT_TIMEOUT_MS) }
            awaitChannelList()
            awaitConnected()
            val (chatX, nameX) = answerChat(serverId, NOTE_CHAT_NAME_PREFIX)
            assertPeerAnswers(peer, chatX)
            openChatRow(nameX)
            sendFromPhone(notePrompt(first, fileName, "reply with exactly this markdown link and nothing else: [$linkText]($fileName)"))
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the note turn in X did not end", allowed) { it.type == "turn_end" }

            // 2. AC-1, AC-3: the tap opens the reader on the note as it is now, named by its file.
            assertLinkOpensNote(linkText, fileName, first, stale = null)

            // 3. #1067: with the reader still open, the peer has claude rewrite the note; Refresh shows it.
            runBlocking { peer.sendMessage(chatX, notePrompt(second, fileName, "reply with a single short word"), THREAD_TIMEOUT_MS) }
            allowPromptsUntil(peer, chatX, WAIT_TURN_TIMEOUT_MS, "the rewrite turn in X did not end", allowed) {
                it.type == "turn_end" && peer.recorded(chatX).count { frame -> frame.type == "turn_end" } >= 2
            }
            assertRefreshShowsNote(second, stale = first)

            // 4. AC-3: back returns to the same thread.
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitThreadComposer()

            // 5. AC-2, AC-5: the same link now shows the new content only.
            assertLinkOpensNote(linkText, fileName, second, stale = first)
            composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
            awaitThreadComposer()
        } finally {
            peer.close()
        }
    }

    /**
     * The #1050 note prompt: write a one-heading markdown note [marker] to [fileName] in the workspace with one
     * shell command, then [reply]. `printf` with a quoted literal keeps the bytes exactly as written.
     */
    private fun notePrompt(
        marker: String,
        fileName: String,
        reply: String,
    ): String =
        "Run this exact shell command with your tools: `printf '# $marker\\n' > $fileName`. " +
            "Do not use Write or Edit, and do not create any other file. After the command returns, $reply."

    /**
     * Tap the first [linkText] link in claude's reply and wait for the reader: the note's [marker] heading
     * under a bar named [fileName], the composer gone, and never the [stale] heading.
     */
    private fun assertLinkOpensNote(
        linkText: String,
        fileName: String,
        marker: String,
        stale: String?,
    ) {
        val link = hasText(linkText, substring = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
        try {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                runCatching {
                    scrollListTo(link)
                    composeTestRule.onAllNodes(link, useUnmergedTree = true).onFirst().performFirstLinkClick()
                }.isSuccess
            }
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText(marker)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("tapping the link to $fileName did not show the note $marker in the reader", e)
        }
        // Past the navigation transition, so the thread beneath (whose prompts name both markers) is gone.
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasText(marker)).assertIsDisplayed()
        composeTestRule.onNode(hasText(fileName)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).assertCountEquals(0)
        stale?.let { composeTestRule.onAllNodes(hasText(it)).assertCountEquals(0) }
    }

    /**
     * Choose Refresh from the open reader's overflow (#1067) and wait for the note's [marker] heading, read
     * again from the host: never the [stale] heading, and no could-not-open notice.
     */
    private fun assertRefreshShowsNote(
        marker: String,
        stale: String,
    ) {
        val openFailed = string(R.string.thread_attachment_open_failed)
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.onNode(hasText(string(R.string.markdown_reader_refresh))).performClick()
        try {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasText(openFailed)).fetchSemanticsNodes().isNotEmpty() ||
                    composeTestRule.onAllNodes(hasText(marker)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Refresh in the reader did not show the rewritten note $marker", e)
        }
        composeTestRule.onAllNodes(hasText(openFailed)).assertCountEquals(0)
        composeTestRule.onNode(hasText(marker)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(stale)).assertCountEquals(0)
    }

    /** Wait until the open thread's composer is back. */
    private fun awaitThreadComposer() {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The #1016 offer prompt: write [content] to [fileName] in the workspace with one shell command, then hand
     * it over with `send_file`. The file must be in the workspace, since `send_file` refuses any other path,
     * and `printf` with a quoted literal and no newline keeps its bytes exactly [content].
     */
    private fun offerPrompt(
        content: String,
        fileName: String,
    ): String =
        "Run this exact shell command with your tools: `printf '$content' > $fileName`. " +
            "Then hand that file to the operator by calling the send_file tool from the pyry_files MCP server, " +
            "passing path $fileName. Do not use Write or Edit, and do not change the file. " +
            "After the tool returns, reply with a single short word."

    /** A 4 × 4 PNG in one flat colour: generated, a few dozen bytes. */
    private fun pngFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(FIXTURE_COLOR)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A generated text document of [DOCUMENT_BYTES] or a little more, so it spans more than one chunk. */
    private fun documentFixture(label: String): ByteArray {
        val text = StringBuilder()
        var line = 0
        while (text.length < DOCUMENT_BYTES) {
            text
                .append("e2e1016 ")
                .append(label)
                .append(" line ")
                .append(line++)
                .append('\n')
        }
        return text.toString().toByteArray().also { check(it.size > ATTACHMENT_CHUNK_BYTES) { "the document fixture fits one chunk" } }
    }

    /**
     * A `MediaStore` download named [name] holding [bytes], or empty when [bytes] is null, recorded in
     * [inserted] for [deleteFixtures]. This app owns it, so it reads and writes it without a permission, and
     * its `media` authority is another app's, which is what the picker and the upload read accept.
     */
    private fun insertDownload(
        name: String,
        mimeType: String,
        bytes: ByteArray?,
        inserted: MutableList<Uri>,
    ): Uri {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
            }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "MediaStore refused a fixture" }
        inserted += uri
        if (bytes != null) checkNotNull(resolver.openOutputStream(uri, "wt")) { "a fixture is not writable" }.use { it.write(bytes) }
        return uri
    }

    /** Delete the `MediaStore` entries [insertDownload] made; one already gone is ignored. */
    private fun deleteFixtures(inserted: List<Uri>) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    /** All the bytes behind [uri], read through this app's own resolver. */
    private fun readUri(uri: Uri): ByteArray =
        checkNotNull(
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.contentResolver
                .openInputStream(uri),
        ) {
            "a content URI opened no stream"
        }.use { it.readBytes() }

    /** Lowercase hex SHA-256 of [bytes]. */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The user messages in a history. The host logs the operator's turn as a `message` entry with role `user`
     * when it is delivered; a stored `send_message` counts too, the shape the phone's reducer also reads.
     */
    private fun userMessages(history: List<HistoryEntryDto>): Int = history.count { it.isUserMessage() }

    /** The `attachment_ids` of each user message in a history, in log order; empty for a message naming none. */
    private fun userMessageAttachmentIds(history: List<HistoryEntryDto>): List<List<String>> =
        history.filter { it.isUserMessage() }.map { entry ->
            ((entry.payload as? JsonObject)?.get("attachment_ids") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        }

    private fun HistoryEntryDto.isUserMessage(): Boolean =
        type == "send_message" || (type == "message" && (payload as? JsonObject)?.get("role")?.jsonPrimitive?.content == "user")

    /**
     * Fail fast, and say so, when [peer]'s open session carries no frames: one `request_history` for
     * [conversationId] must be answered. Two live runs had peers whose handshake the daemon accepted and which
     * then saw nothing, so a permission prompt went unanswered until the turn timed out as a feature failure.
     * A peer whose session closed first fails at once the same way (#1063).
     */
    private fun assertPeerAnswers(
        peer: SecondClientPeer,
        conversationId: String,
    ) {
        runBlocking { requirePeerAnswer(THREAD_TIMEOUT_MS) { peer.history(conversationId, THREAD_TIMEOUT_MS) } }
    }

    /**
     * A message attachment's file row named [name] that is ready to act: tap opens, long-press saves. Since
     * #1329 a named file other than an image is not fetched until that tap, so the row acts before it is fetched.
     */
    private fun readyAttachmentRow(name: String): SemanticsMatcher =
        hasTestTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG) and hasText(name) and hasClickAction()

    /**
     * Scroll the thread until [readyAttachmentRow] for [name] is on screen. The name is a fixture's. [poll] runs
     * before each look, while the row is not yet drawn.
     */
    private fun awaitReadyAttachmentRow(
        name: String,
        timeoutMs: Long,
        poll: () -> Unit = {},
    ) {
        try {
            composeTestRule.waitUntil(timeoutMs) {
                if (composeTestRule.onAllNodes(hasTestTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)).fetchSemanticsNodes().isEmpty()) poll()
                runCatching { scrollListTo(readyAttachmentRow(name)) }.isSuccess
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("no ready attachment row named $name within $timeoutMs ms", e)
        }
    }

    /**
     * Tap the ready row named [name] and read what `ACTION_VIEW` was handed; then long-press it and read what
     * was written to the `ACTION_CREATE_DOCUMENT` target. Both must have [digest]. [stub] answers both. The tap
     * comes first: for a named file other than an image it is what fetches the file (#1329), so its wait
     * allows a retrieval.
     */
    private fun assertOpensAndSaves(
        stub: ActivityIntentStub,
        name: String,
        digest: String,
        inserted: MutableList<Uri>,
    ) {
        stub.answer(Intent.ACTION_VIEW) { Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) }
        val views = stub.answered.count { it.action == Intent.ACTION_VIEW }
        composeTestRule.onNode(readyAttachmentRow(name)).performClick()
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) { stub.answered.count { it.action == Intent.ACTION_VIEW } > views }
        val view = stub.answered.last { it.action == Intent.ACTION_VIEW }
        val opened = checkNotNull(view.data) { "ACTION_VIEW carried no URI" }
        assertEquals("the scheme of the URI handed to the viewer", ContentResolver.SCHEME_CONTENT, opened.scheme)
        assertTrue("the viewer got no read grant", (view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals("digest of the opened file", digest, sha256(readUri(opened)))

        val target = insertDownload(ATTACH_FILE_PREFIX + "saved-${System.nanoTime()}.bin", "application/octet-stream", null, inserted)
        stub.answer(Intent.ACTION_CREATE_DOCUMENT) { Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(target)) }
        composeTestRule.onNode(readyAttachmentRow(name)).performTouchInput { longClick() }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            listOf(savedNotice, saveFailedNotice).any { composeTestRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }
        composeTestRule.onAllNodesWithText(saveFailedNotice).assertCountEquals(0)
        assertEquals("digest of the saved file", digest, sha256(readUri(target)))
    }

    /**
     * Restart the app as [E2eTestApplication.rebuildGraph] can (#1016, after #847): the rule's activity goes
     * first, since no activity may outlive the graph it resolved; [beforeLaunch] runs on the new graph before
     * the relaunch. Close the returned scenario in the test's `finally`.
     */
    private fun restartApp(beforeLaunch: () -> Unit = {}): ActivityScenario<MainActivity> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        instrumentation.runOnMainSync {
            (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph()
        }
        beforeLaunch()
        return ActivityScenario.launch(MainActivity::class.java)
    }

    /** Wait until the phone's thread cache for [conversationId] holds an assistant row carrying [attachmentId]. */
    private fun awaitCachedOffer(
        serverId: String,
        conversationId: String,
        attachmentId: String,
    ) {
        val cache = GlobalContext.get().get<ConversationCache>()
        try {
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    while (cache.readThread(serverId, conversationId).none { item ->
                            item is ThreadItem.MessageItem &&
                                item.message.role == Role.Assistant &&
                                item.message.attachments.any { it.attachmentId == attachmentId }
                        }
                    ) {
                        delay(CACHE_POLL_MS)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("the phone's thread cache holds no assistant row with the offered file", e)
        }
    }

    /**
     * Pick the one document [bytes], named [name], through the composer's **Attach files** action (#1017) and
     * wait for its strip tile. [stub] answers the picker with a `MediaStore` fixture recorded in [inserted].
     */
    private fun attachDocument(
        stub: ActivityIntentStub,
        name: String,
        bytes: ByteArray,
        inserted: MutableList<Uri>,
    ) {
        val picked = insertDownload(name, TEXT_MIME, bytes, inserted)
        stub.answer(Intent.ACTION_OPEN_DOCUMENT) {
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply { clipData = ClipData.newRawUri(null, picked) })
        }
        composeTestRule.onNode(hasContentDescription(attachFilesLabel)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Wait until a send has ended with [text] still in the composer and the file [name] still pending (#1017).
     * The tile's remove control is drawn only while no send is under way.
     */
    private fun awaitComposerHolds(
        text: String,
        name: String,
    ) {
        val remove =
            hasContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_remove_attachment, name))
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(remove).fetchSemanticsNodes().isNotEmpty() && composerText() == text
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the failed send did not leave its text and its file in the composer", e)
        }
    }

    /** No tile and no row in the open thread carries the file name [name] (#1017); [where] names the check. */
    private fun assertNoFileNamed(
        name: String,
        where: String,
    ) {
        composeTestRule.waitForIdle()
        assertEquals(
            "tiles named the file in $where",
            0,
            composeTestRule.onAllNodes(hasContentDescription(name), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertEquals(
            "rows named the file in $where",
            0,
            composeTestRule.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    /** A node matching [node] inside the message attachment file row named [name], in the unmerged tree. */
    private fun inAttachmentRow(
        name: String,
        node: SemanticsMatcher,
    ): SemanticsMatcher = node and hasAnyAncestor(hasTestTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG) and hasAnyDescendant(hasText(name)))

    /** The Retry control of the failed file row named [name] (#984), in the unmerged tree. */
    private fun attachmentRetry(name: String): SemanticsMatcher =
        inAttachmentRow(
            name,
            hasClickAction() and hasAnyDescendant(hasText(attachmentRetryLabel)),
        )

    /** [conversationId]'s current name on [serverId], read from that host's own repository. */
    private fun heldName(
        serverId: String,
        conversationId: String,
    ): String {
        val repository = hostRepository(serverId)
        val held =
            runBlocking {
                withTimeout(LIST_TIMEOUT_MS) {
                    repository
                        .observeConversations(ConversationFilter.All)
                        .first { rows -> rows.any { it.id == conversationId } }
                        .first { it.id == conversationId }
                }
            }
        return checkNotNull(held.name) { "the seeded conversation has no name" }
    }

    /**
     * Cut [serverId]'s link from inside the app, at the moment the app logs a line [matches] accepts (#1017).
     * The lines are [RelayLog]'s debug lines, which carry ids, indices and totals only.
     */
    private fun cutLinkOn(
        serverId: String,
        matches: (String) -> Boolean,
    ): LinkCut {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        return LinkCut(serverId, { bundle.supervisor.close() }, matches)
    }

    /**
     * A one-shot link cut (#1017). A wrapping [RelayLog.sink] still forwards every line. The first line that
     * [matches] accepts closes the link on the thread that logged it, before that code's next step. So an
     * upload's next chunk or a retrieval's request is refused, never raced. [close] restores the sink.
     */
    private inner class LinkCut(
        private val serverId: String,
        closeLink: () -> Unit,
        matches: (String) -> Boolean,
    ) : AutoCloseable {
        private val previous = RelayLog.sink
        private val armed = AtomicBoolean(true)
        private val fired = CountDownLatch(1)

        init {
            RelayLog.sink = { priority, tag, message ->
                previous(priority, tag, message)
                if (matches(message) && armed.compareAndSet(true, false)) {
                    closeLink()
                    fired.countDown()
                }
            }
        }

        /**
         * Wait until the cut has fired, running [poll] meanwhile, and then until the link's repository is gone.
         * The failure names [failure].
         */
        fun await(
            failure: String,
            poll: () -> Unit = {},
        ) {
            try {
                composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                    poll()
                    fired.count == 0L
                }
            } catch (e: ComposeTimeoutException) {
                throw AssertionError("$failure within $REPLY_TIMEOUT_MS ms, so the link was never cut", e)
            }
            setHostLink(serverId, up = false)
        }

        override fun close() {
            armed.set(false)
            RelayLog.sink = previous
        }
    }

    /**
     * Wait until the footer circle's accessible reading passes [shows], and return that description. The failure
     * names [what] was expected and what the segment showed; the text is the app's own, never claude's.
     */
    private fun awaitContextSegment(
        timeoutMs: Long,
        what: String,
        shows: (String) -> Boolean,
    ): String {
        fun shown(): List<String> =
            composeTestRule.onAllNodes(hasTestTag(CONTEXT_USAGE_TEST_TAG)).fetchSemanticsNodes().map { node ->
                node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .joinToString("")
            }
        var matched: String? = null
        try {
            composeTestRule.waitUntil(timeoutMs) {
                matched = shown().firstOrNull(shows)
                matched != null
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the footer's context segment never showed $what; it shows ${shown()}", e)
        }
        return checkNotNull(matched)
    }

    /** A slash-command suggestion labelled [label]; the composer, whose text a pick can equal, is excluded. */
    private fun slashRow(label: String): SemanticsMatcher = hasText(label) and hasClickAction() and !hasSetTextAction()

    /** The composer's current text. */
    private fun composerText(): String =
        composeTestRule
            .onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    /** Open the footer's Actions menu (#884). */
    private fun openActions() {
        val control = footerControl(string(R.string.thread_footer_open_actions))
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(control).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onAllNodes(control).onFirst().performClick()
    }

    /** An Actions menu row, a button whose label passes [label]. */
    private fun actionRow(label: (String) -> Boolean): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, SemanticsRole.Button) and hasClickAction() and
            SemanticsMatcher("action row") { node ->
                label(
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .joinToString("") { it.text },
                )
            }

    /** Open the count-free Background tasks row from the top menu (#1668). */
    private fun openBackgroundTasks() {
        composeTestRule.onNodeWithContentDescription(CD_MORE_ACTIONS).performClick()
        val row = hasText(string(R.string.background_tasks_title)) and hasClickAction()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNode(row).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertDoesNotExist()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasText(string(R.string.background_tasks_title))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A node inside the open background-task panel, the dialog holding its title. */
    private fun inBackgroundPanel(): SemanticsMatcher =
        hasAnyAncestor(isDialog() and hasAnyDescendant(hasText(string(R.string.background_tasks_title))))

    /**
     * The `background_task_progress` frames [peer] recorded for [conversationId], decoded, that join on the
     * `task_id` of a recorded `background_task_started` (#1076). A frame that fails to decode is skipped.
     */
    private fun progressFrames(
        peer: SecondClientPeer,
        conversationId: String,
    ): List<BackgroundTaskProgressPayloadDto> {
        val frames = peer.recorded(conversationId)
        val started =
            frames
                .filter { it.type == "background_task_started" }
                .mapNotNull {
                    runCatching { MobileJson.decodeFromJsonElement(BackgroundTaskStartedPayloadDto.serializer(), it.payload) }
                        .getOrNull()
                        ?.taskId
                }.toSet()
        return frames
            .filter { it.type == "background_task_progress" }
            .mapNotNull {
                runCatching { MobileJson.decodeFromJsonElement(BackgroundTaskProgressPayloadDto.serializer(), it.payload) }.getOrNull()
            }.filter { it.taskId in started }
    }

    /**
     * Patterns for a progress meta line's tools segment of any count, built from the
     * `background_tasks_progress_tools` plural's own templates rather than restating its wording (#1044).
     */
    private fun toolsSegmentPatterns(): List<Regex> {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        return listOf(1, 2).map { quantity ->
            val template = resources.getQuantityText(R.plurals.background_tasks_progress_tools, quantity).toString()
            Regex("(?<!\\d)" + template.split("%1\$d").joinToString("\\d+") { Regex.escape(it) })
        }
    }

    /** Close the background-task panel and wait until it is gone. */
    private fun closeBackgroundTasks() {
        composeTestRule.onNode(hasContentDescription(CD_CLOSE_SHEET) and inBackgroundPanel()).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasText(string(R.string.background_tasks_title))).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Poll [peer]'s frames for [conversationId] until one passes [done], and return it. Every permission prompt
     * raised there on the way is allowed once through the peer, the main daemon's privileged device (#950).
     * [allowed] holds the modal ids already answered; share it across calls on one peer so a prompt still in
     * its recorded frames is not answered twice. The failure names [failure], a count and the peer's link state,
     * never a frame's text. If the peer is closed under it, it fails at once naming [frame], the type it awaits (#1064).
     */
    private fun allowPromptsUntil(
        peer: SecondClientPeer,
        conversationId: String,
        timeoutMs: Long,
        failure: String,
        allowed: MutableSet<String> = mutableSetOf(),
        frame: String = "turn_end",
        done: (Envelope) -> Boolean,
    ): Envelope =
        try {
            runBlocking {
                peer.awaiting(frame, timeoutMs) {
                    var found: Envelope? = null
                    while (found == null) {
                        val frames = peer.recorded(conversationId)
                        found = frames.firstOrNull(done)
                        if (found == null) {
                            frames
                                .filter { it.type == "modal_shown" }
                                .mapNotNull {
                                    runCatching {
                                        MobileJson.decodeFromJsonElement(
                                            ModalShownPayloadDto.serializer(),
                                            it.payload,
                                        )
                                    }.getOrNull()
                                }.filter { shown ->
                                    shown.modalClass == PERMISSION_CLASS &&
                                        shown.modalId !in allowed &&
                                        shown.options.any { it.id == ALLOW_ONCE }
                                }.forEach { shown ->
                                    allowed += shown.modalId
                                    peer.allowOnce(shown.modalId, THREAD_TIMEOUT_MS)
                                }
                            delay(CACHE_POLL_MS)
                        }
                    }
                    checkNotNull(found)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "$failure within $timeoutMs ms (permission prompts allowed: ${allowed.size}; peer: ${peer.linkState()})",
                e,
            )
        }

    /**
     * The answer daemon's server id and a peer for it (#966), or the failure for its unmet prerequisite. The
     * peer is built here and opened by the test; nothing is paired yet.
     */
    private fun answerHostPeer(): Pair<String, SecondClientPeer> {
        val args = InstrumentationRegistry.getArguments()
        args.getString(ARG_ANSWER_UNMET)?.let {
            throw AssertionError(
                "the answer daemon (#966) did not start: " + (ANSWER_UNMET_REASONS[it] ?: "unmet prerequisite '$it'") +
                    ". See scripts/e2e-emulator.sh § 4a' and its log.",
            )
        }
        val serverId = answerArg(ARG_ANSWER_SERVER_ID)
        val peer =
            SecondClientPeer(
                PairedServer(
                    serverId = serverId,
                    token = answerArg(ARG_ANSWER_PEER_TOKEN),
                    relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                    serverStaticPublicKey = answerArg(ARG_ANSWER_SERVER_STATIC_PUBLIC_KEY),
                ),
            )
        return serverId to peer
    }

    /** Pair the answer daemon by code, as #687 pairs its host. This pairing is the phone's only privileged one. */
    private fun pairAnswerHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        awaitChannelList()
        awaitConnected()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        pairHostByCode(answerArg(ARG_ANSWER_PAIR_CODE), ANSWER_HOST_NAME)
    }

    /**
     * A new chat on [serverId] with a run-unique name starting [prefix]: its id and name. A connection that
     * drops mid-request is retried on the host's redial (#1029); that can leave one unnamed stray chat, which
     * no scenario finds, because each finds its chat by this name.
     */
    private fun answerChat(
        serverId: String,
        prefix: String,
    ): Pair<String, String> {
        val name = prefix + System.currentTimeMillis()
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        val chat =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    bundle.coordinator.currentRepository.callOnLive(REDIAL_WAIT_MS) { it.rename(it.createDiscussion().id, name) }
                }
            }
        return chat.id to name
    }

    /** The Chats row whose name contains [name], with its dot reading [state] (#1090). */
    private fun attentionRow(
        name: String,
        state: String,
    ): SemanticsMatcher = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(name, substring = true) and hasContentDescription(state)

    /** Scroll to the Chats row named [name] and wait until its dot reads [state], naming [what] and the state it reads. */
    private fun awaitRowAttention(
        name: String,
        state: String,
        what: String,
    ) {
        val row = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(name, substring = true)
        try {
            composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
                runCatching { scrollListTo(row) }.isSuccess &&
                    composeTestRule.onAllNodes(attentionRow(name, state)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            val shown = composeTestRule.onAllNodes(row).fetchSemanticsNodes().map { attentionOf(it) }
            throw AssertionError("$what: the row's dot never read '$state' within $LIST_TIMEOUT_MS ms (it reads $shown)", e)
        }
    }

    /**
     * Every composed Channels and Chats tree row's dot state, grouped by the row's name and sorted, so two rows
     * sharing a name on different hosts compare as a set of states.
     */
    private fun treeRowAttention(): Map<String, List<String>> =
        composeTestRule
            .onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG) or hasTestTag(TREE_CHANNEL_ROW_TEST_TAG))
            .fetchSemanticsNodes()
            .groupBy(
                { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .joinToString(" ") { it.text }
                },
                { node -> attentionOf(node) },
            ).mapValues { (_, states) -> states.sorted() }

    /** The attention state a tree row's merged node carries: whichever of the dot's four descriptions it holds. */
    private fun attentionOf(node: SemanticsNode): String {
        val states =
            listOf(
                R.string.cd_conversation_attention_waiting,
                R.string.cd_conversation_attention_running,
                R.string.cd_conversation_attention_unread,
                R.string.cd_conversation_attention_idle,
            ).map(::string)
        return node.config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
            .firstOrNull { it in states } ?: "none"
    }

    /** An answer-daemon argument (#966), failing with the script that passes it. */
    private fun answerArg(key: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString(key)) {
            "missing instrumentation arg '$key' — scripts/e2e-emulator.sh passes it once the answer daemon (#966) is up"
        }

    /** The inline permission request's card (#1306), which holds its prompt, context, grant offer and options. */
    private fun promptDialog(): SemanticsMatcher = hasTestTag(PERMISSION_CARD_TEST_TAG)

    /** A node inside the open request's card. */
    private fun inPromptDialog(): SemanticsMatcher = hasAnyAncestor(promptDialog())

    /** The request's lazy rows can be merely offscreen, so bring the card into view before judging it present. */
    private fun awaitPromptDialog() {
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            runCatching { composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(promptDialog()) }.isSuccess &&
                composeTestRule.onAllNodes(promptDialog()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoPromptDialog(failure: String) {
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                listOf(PERMISSION_CARD_TEST_TAG, "permission-request-title", "permission-request-cancel").all { tag ->
                    composeTestRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
                }
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(failure, e)
        }
    }

    /** Scroll the thread to [matcher] inside the open request, then tap it with a real pointer. */
    private fun tapInPrompt(matcher: SemanticsMatcher) {
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        composeTestRule.onNode(matcher).performTouchInput { click(center) }
    }

    private fun awaitInlineQuestion() {
        val title = hasTestTag("question-batch-title")
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            runCatching { composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(title) }.isSuccess &&
                composeTestRule.onAllNodes(title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoInlineQuestion(failure: String) {
        val title = hasTestTag("question-batch-title")
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                // Lazy prompt rows can be absent merely offscreen; the status band's label is always composed.
                composeTestRule.onAllNodes(title).fetchSemanticsNodes().isEmpty() &&
                    composeTestRule.onAllNodes(hasTestTag("thread-question-row")).fetchSemanticsNodes().isEmpty() &&
                    composeTestRule.onAllNodes(hasTestTag("question-batch-actions")).fetchSemanticsNodes().isEmpty() &&
                    composeTestRule
                        .onAllNodes(hasText(string(R.string.question_waiting_for_answers)))
                        .fetchSemanticsNodes()
                        .isEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(failure, e)
        }
    }

    /**
     * The phone draws a decision-context row for the context [shown] carries (#817). A frame carrying none
     * fails here: claude sent the prompt without the context this proof needs.
     */
    private fun assertContextDrawn(shown: ModalShownPayloadDto) {
        val reason = shown.reason.toString().takeUnless { it == "\"\"" || it == "null" }
        assertTrue(
            "the prompt's modal_shown carried no decision context (reason, reason_type, description, blocked_path)",
            reason != null || shown.reasonType != null || shown.description != null || shown.blockedPath != null,
        )
        val labels =
            listOf(
                string(R.string.modal_context_reason),
                string(R.string.modal_context_reason_classifier),
                string(R.string.modal_context_reason_rule),
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .getString(R.string.modal_context_reason_type, "")
                    .trim(),
                string(R.string.modal_context_description),
                string(R.string.modal_context_blocked_path),
            )
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                labels.any { label ->
                    composeTestRule.onAllNodes(hasText(label, substring = true) and inPromptDialog()).fetchSemanticsNodes().isNotEmpty()
                }
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the prompt dialog draws no decision-context row for the context its frame carried", e)
        }
    }

    /** Wait for [conversationId]'s [occurrence]th `turn_end` on the peer, naming [what] on a timeout. */
    private fun awaitTurnEnd(
        peer: SecondClientPeer,
        conversationId: String,
        occurrence: Int,
        what: String,
    ) {
        try {
            runBlocking { peer.awaitFrame(conversationId, "turn_end", REPLY_TIMEOUT_MS, occurrence) }
        } catch (e: TimeoutCancellationException) {
            val prompts = peer.recorded(conversationId).count { it.type == "modal_shown" || it.type == "question_shown" }
            throw AssertionError("$what never ended within $REPLY_TIMEOUT_MS ms (prompts raised in the chat: $prompts)", e)
        }
    }

    /**
     * Claude's reply text in [conversationId] as the peer recorded it, from the [from]th recorded frame on:
     * its `assistant_delta` texts joined. Compared in the test only, never put into a message.
     */
    private fun assistantText(
        peer: SecondClientPeer,
        conversationId: String,
        from: Int = 0,
    ): String =
        peer
            .recorded(conversationId)
            .drop(from)
            .filter { it.type == "assistant_delta" }
            .mapNotNull {
                runCatching {
                    MobileJson
                        .decodeFromJsonElement(
                            AssistantDeltaPayloadDto.serializer(),
                            it.payload,
                        ).text
                }.getOrNull()
            }.joinToString("")

    /**
     * Assert that claude ran a command in [conversationId]'s turn from the [from]th recorded frame on: the
     * peer recorded a `Bash` `tool_use` there and a `tool_result` for that call with `is_error` false. The
     * failure names counts and booleans only.
     */
    private fun assertBashRan(
        peer: SecondClientPeer,
        conversationId: String,
        from: Int,
        what: String,
    ) {
        val frames = peer.recorded(conversationId).drop(from)
        val bashIds =
            frames
                .filter { it.type == "tool_use" }
                .mapNotNull { runCatching { MobileJson.decodeFromJsonElement(ToolUsePayloadDto.serializer(), it.payload) }.getOrNull() }
                .filter { it.name == TOOL_NAME }
                .map { it.toolUseId }
                .toSet()
        val results =
            frames
                .filter { it.type == "tool_result" }
                .mapNotNull { runCatching { MobileJson.decodeFromJsonElement(ToolResultPayloadDto.serializer(), it.payload) }.getOrNull() }
                .filter { it.toolUseId in bashIds }
        assertTrue(
            "$what ran no successful Bash call (Bash calls: ${bashIds.size}, their results: ${results.size}, " +
                "any error: ${results.any { it.isError }})",
            results.any { !it.isError },
        )
    }

    /**
     * Wait until the open thread shows a permission prompt that names this Read: a node in the inline request's
     * card (#1306) whose text carries the file's [baseName] or the tool name `Read`. The phone's own message
     * names both, so the card scope is what makes the match the prompt's.
     */
    private fun awaitReadPrompt(baseName: String) {
        awaitPromptDialog()
        val inPrompt = inPromptDialog()
        val namesRead =
            SemanticsMatcher("names the Read of $baseName") { node ->
                val text =
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .joinToString("") { it.text }
                baseName in text || READ_TOOL_WORD.containsMatchIn(text)
            }
        try {
            composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
                composeTestRule.onAllNodes(namesRead and inPrompt).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            val shown = composeTestRule.onAllNodes(promptDialog()).fetchSemanticsNodes().size
            throw AssertionError("no permission prompt naming the Read appeared on the phone (request cards: $shown)", e)
        }
    }

    /**
     * Why the ended Read turn left no token on the phone (#977): the phone's bubbles, the peer's [frames]
     * for the chat (opened just before the Read was sent, so all of them are this turn's; the allow was
     * sent at index [mark]), and the [turnEnd] that ended it [endedAfterMs] after the allow. Counts,
     * booleans and the turn's bounded enum-like fields only; never bubble text, payload text or [token].
     */
    private fun readReplyDiagnosis(
        frames: List<Envelope>,
        mark: Int,
        token: String,
        turnEnd: Envelope,
        endedAfterMs: Long,
    ): String {
        val bubbles =
            composeTestRule
                .onAllNodes(hasTestTag(MESSAGE_BUBBLE_TEST_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.textOfTree() }
        val afterAllow = frames.drop(mark)
        val readIds =
            frames
                .filter { it.type == "tool_use" }
                .mapNotNull { runCatching { MobileJson.decodeFromJsonElement(ToolUsePayloadDto.serializer(), it.payload) }.getOrNull() }
                .filter { it.name == "Read" }
                .map { it.toolUseId }
                .toSet()
        val readResults =
            frames
                .filter { it.type == "tool_result" }
                .map { runCatching { MobileJson.decodeFromJsonElement(ToolResultPayloadDto.serializer(), it.payload) }.getOrNull() }
                .filter { it == null || it.toolUseId in readIds }
        val readResultIsError =
            when {
                readResults.isEmpty() -> "absent"
                readResults.any { it == null } -> "undecodable"
                else -> readResults.map { it?.isError }.distinct().joinToString("/")
            }
        val end = runCatching { MobileJson.decodeFromJsonElement(TurnEndPayloadDto.serializer(), turnEnd.payload) }.getOrNull()
        val endFields =
            end?.let { "stop_reason=${it.stopReason.inert()} outcome=${it.outcome.inert()} is_error=${it.isError}" }
                ?: "turn_end undecodable"
        return "bubbles=${bubbles.size} bubbleLengths=${bubbles.map { it.length }} " +
            "anyBlocked=${bubbles.any { it.trim().equals(BLOCKED_REPLY, ignoreCase = true) }} " +
            "readResultIsError=$readResultIsError $endFields endedAfterMs=$endedAfterMs " +
            "peerFramesAfterAllow=${afterAllow.size} peerFramesWithToken=${afterAllow.any { token in it.payload.toString() }}"
    }

    /**
     * Whether the phone's own thread for [conversationId] holds an assistant row carrying [token] (#981):
     * true names the screen (the row was kept but never shown), false names the repository fold. A
     * boolean only; never the row's text.
     */
    private fun threadHoldsReply(
        repository: ConversationRepository,
        conversationId: String,
        token: String,
    ): String =
        runCatching {
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    repository.observeMessages(conversationId).first().any {
                        it is ThreadItem.MessageItem && it.message.role == Role.Assistant && token in it.message.content
                    }
                }
            }
        }.fold(onSuccess = { it.toString() }, onFailure = { "unread" })

    /** The concatenated `Text` of this node and its descendants, in tree order. */
    private fun SemanticsNode.textOfTree(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text } +
            children.joinToString("") { it.textOfTree() }

    /**
     * Poll fresh readings for [conversationId] on [serverId] until one reports [mode], and return it. The
     * mode comes from the child's own confirmation, which can trail a write or a turn by a moment.
     */
    private fun awaitPermissionReading(
        serverId: String,
        conversationId: String,
        mode: String,
    ): SessionSettings {
        var last: SessionSettings? = null
        return try {
            runBlocking {
                withTimeout(PERMISSION_READING_TIMEOUT_MS) {
                    var reading = freshSettings(conversationId, serverId)
                    while (reading.permissionMode != mode) {
                        last = reading
                        delay(PERMISSION_POLL_MS)
                        reading = freshSettings(conversationId, serverId)
                    }
                    reading
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "no fresh reading reported '$mode'; the last reported '${last?.permissionMode?.inert()}' yolo=${last?.yolo}",
                e,
            )
        }
    }

    /** A dedicated-daemon argument (#687), failing with the script that passes it. */
    private fun bypassArg(key: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString(key)) {
            "missing instrumentation arg '$key' — scripts/e2e-emulator.sh passes it once the operator-bypass daemon (#687) is up"
        }

    /** The failure for an unmet dedicated-daemon prerequisite: the script's code, and what it means. */
    private fun bypassUnmetMessage(code: String): String =
        "the operator-bypass daemon (#687) did not start: " + (BYPASS_UNMET_REASONS[code] ?: "unmet prerequisite '$code'") +
            ". See scripts/e2e-emulator.sh § 4a and its log."

    /**
     * The effort control's label and note for [applied], a reading taken after a real turn (#545, #889). A
     * reading that omits `effective_effort` fails.
     */
    private fun appliedEffortFooter(applied: EffectiveEffort): Pair<String, String?> =
        when (applied) {
            EffectiveEffort.Unavailable -> throw AssertionError("the reply after a real turn omitted effective_effort")
            EffectiveEffort.NotReported -> EFFORT_PLACEHOLDER_LABEL to claudeNote(R.string.thread_effort_note_not_reported)
            is EffectiveEffort.Applied ->
                if (applied.value.isEmpty()) {
                    EFFORT_PLACEHOLDER_LABEL to claudeNote(R.string.thread_effort_note_default_unavailable)
                } else {
                    effortLabel(applied.value) to null
                }
        }

    /**
     * In the open thread of [conversationId]: the recall settles the footer on [level], a fresh reply's saved
     * effort is [level], and after one real turn claude applies exactly [level].
     */
    private fun assertRecalledThenApplied(
        conversationId: String,
        level: String,
    ) {
        awaitFooter(changeEffortLabel, effortLabel(level))
        assertEquals("the recalled saved effort before the first message", level, freshSettings(conversationId).effort)
        sendFromPhone(PING_PROMPT)
        composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
        assertEquals(EffectiveEffort.Applied(level), freshSettings(conversationId).effectiveEffort)
    }

    /**
     * A paired host's current repository, by default the harness's first host, read from the current graph
     * so it survives a restart.
     */
    private fun hostRepository(
        serverId: String = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID)),
    ): ConversationRepository {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        return runBlocking { withTimeout(CONNECT_TIMEOUT_MS) { checkNotNull(bundle.coordinator.currentRepository.first { it != null }) } }
    }

    /**
     * A new `request_session_settings` for [conversationId] and its reply: each collection sends its own read.
     * The subscription starts with the host's held reading (#1320), which is skipped, so only the live
     * connection's reply is returned. [serverId] is the host that holds the conversation, by default the
     * harness's first host.
     */
    private fun freshSettings(
        conversationId: String,
        serverId: String = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID)),
    ): SessionSettings {
        val repository = hostRepository(serverId)
        return runBlocking {
            withTimeout(THREAD_TIMEOUT_MS) { repository.observeSessionSettings(conversationId).filterNotNull().first { !it.held } }
        }
    }

    /** The model menu the host publishes for [conversationId]; the first collection asks for it. */
    private fun publishedMenu(conversationId: String): ModelMenu {
        val repository = hostRepository()
        return runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repository.observeModelMenu(conversationId).filterNotNull().first() } }
    }

    /** Usable Claude rows, including default for inherited-effort setup. Ordinary labels must be unique
     * so tapping a model in Run configuration cannot pick another published row. */
    private fun usableRows(menu: ModelMenu): List<ModelMenuRow> =
        menu.rows.filter { row ->
            row.agent == ConversationAgent.Claude &&
                row.truncatedFields.orEmpty().none { it in CUT_FIELDS_IN_USE } &&
                row.displayName.inert().isNotBlank() &&
                (
                    row.value == INHERITED_MODEL_VALUE ||
                        menu.rows.count {
                            it.agent == ConversationAgent.Claude &&
                                it.value != INHERITED_MODEL_VALUE &&
                                it.dropdownLabel(ConversationAgent.Claude) == row.dropdownLabel(ConversationAgent.Claude)
                        } == 1
                )
        }

    /** The raw model claude announced for [conversationId]'s latest turn, as the phone's host repository holds it. */
    private fun announcedModel(conversationId: String): String {
        val repository = hostRepository()
        val announced =
            runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repository.observeAnnouncedModel(conversationId).filterNotNull().first() } }
        return announced.model.takeUnless { announced.truncated }.orEmpty()
    }

    /**
     * Run configuration's effort label for the published [level] (#1497): its first letter capitalised,
     * restated here so the scenario does not share the code it checks. Saved and applied values stay verbatim.
     */
    private fun effortLabel(level: String): String = level.inert().replaceFirstChar { it.uppercaseChar() }

    /** Desktop's family rule, restated here so the scenario does not share the code it checks. */
    private fun claudeFamily(identifier: String): String =
        identifier
            .removePrefix("claude-")
            .takeWhile { it in 'A'..'Z' || it in 'a'..'z' }
            .replaceFirstChar { it.uppercaseChar() }

    /**
     * The Claude row an inherited conversation marks for [announced] (#1308): exact value, else
     * `resolved_model`, else family. The first tier with any candidate decides; more than one marks nothing.
     */
    private fun announcedRow(
        menu: ModelMenu,
        announced: String,
    ): ModelMenuRow? {
        if (announced.isEmpty()) return null
        val rows = menu.rows.filter { it.agent == ConversationAgent.Claude && it.value != INHERITED_MODEL_VALUE }
        val family = claudeFamily(announced)
        val tiers =
            listOf<(ModelMenuRow) -> Boolean>(
                { it.value == announced },
                { it.resolvedModel == announced && "resolved_model" !in it.truncatedFields.orEmpty() },
                { family.isNotEmpty() && claudeFamily(it.value) == family },
            )
        for (matches in tiers) {
            val candidates = rows.filter(matches)
            if (candidates.isNotEmpty()) return candidates.singleOrNull()
        }
        return null
    }

    /**
     * Run configuration marks exactly [marked]'s radio, or, with [marked] `null`, no model radio at all and
     * shows [note] outside the radios (#1308). Model rows draw only their label (#1497), so two rows sharing
     * a family label read alike; the marked radio is told apart by its position among the model radios,
     * which render in [menu] order. Every non-default Claude row of [menu] counts, including rows whose
     * family label another row shares, since those are the rows a wrong mark would land on.
     */
    private fun awaitAnnouncedMark(
        menu: ModelMenu,
        marked: ModelMenuRow?,
        note: String,
    ) {
        val rows = menu.rows.filter { it.agent == ConversationAgent.Claude && it.value != INHERITED_MODEL_VALUE }
        val rowLabels = rows.map { it.dropdownLabel(ConversationAgent.Claude) }
        val labels = rowLabels.toSet()
        val modelRadio =
            SemanticsMatcher("a model radio") { node ->
                node.config.getOrNull(SemanticsProperties.Role) == SemanticsRole.RadioButton &&
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        ?.firstOrNull()
                        ?.text in labels
            }
        val expectedMarks = listOfNotNull(marked?.let { row -> rows.indexOfFirst { it.value == row.value } })

        // The model radios in sheet order, as (label, marked) pairs.
        fun radios() =
            composeTestRule.onAllNodes(modelRadio).fetchSemanticsNodes().map { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString("") { it.text } to (node.config.getOrNull(SemanticsProperties.Selected) == true)
            }

        fun markedIndices() = radios().withIndex().filter { it.value.second }.map { it.index }
        openRunConfiguration()
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { radios().map { it.first } == rowLabels }
            if (marked != null) {
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { markedIndices() == expectedMarks }
            } else {
                val standaloneNote = hasText(note) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)
                composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                    composeTestRule.onAllNodes(standaloneNote).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("a model radio is marked for an ambiguous or unmatched announcement", emptyList<Int>(), markedIndices())
            }
        } finally {
            composeTestRule.onNodeWithContentDescription("Close").performClick()
        }
    }

    /** No radio in Run configuration reads "Default" (#1308). */
    private fun assertNoDefaultModelRadio() {
        openRunConfiguration()
        try {
            val defaultRadio =
                SemanticsMatcher.expectValue(SemanticsProperties.Role, SemanticsRole.RadioButton) and
                    hasText("Default", substring = true)
            assertTrue("a radio reads Default", composeTestRule.onAllNodes(defaultRadio).fetchSemanticsNodes().isEmpty())
        } finally {
            composeTestRule.onNodeWithContentDescription("Close").performClick()
        }
    }

    /** Expected inherited label from the host's fresh published list, independent of a running turn. */
    private fun inheritedModelLabel(menu: ModelMenu): String {
        val claudeRows = menu.rows.filter { it.agent == ConversationAgent.Claude }
        val default = claudeRows.filter { it.value == INHERITED_MODEL_VALUE }.singleOrNull()
        val resolved = default?.resolvedModel.orEmpty()
        if (resolved.isBlank() || resolved.startsWith("<") || "resolved_model" in default?.truncatedFields.orEmpty()) {
            return UNAVAILABLE_MODEL_LABEL
        }
        // #1308: with no unique row, the label names the default resolution's family, never "Default".
        return claudeRows
            .filter { it.value != INHERITED_MODEL_VALUE && it.resolvedModel == resolved }
            .singleOrNull()
            ?.dropdownLabel(ConversationAgent.Claude)
            ?: claudeFamily(resolved).ifEmpty { UNAVAILABLE_MODEL_LABEL }
    }

    /**
     * Create a chat on the host, named [name] so its row can be found, and record its first reading in
     * [originals] for [restoreSettings].
     */
    private fun prepareChat(
        name: String,
        originals: MutableMap<String, SessionSettings>,
    ): Conversation {
        val repository = hostRepository()
        val created =
            runBlocking {
                withTimeout(THREAD_TIMEOUT_MS) {
                    val id = repository.createDiscussion().id
                    repository.rename(id, name)
                }
            }
        originals[created.id] = freshSettings(created.id)
        return created
    }

    /** Create a channel named [name] in [workspace] on the host and record its first reading in [originals]. */
    private fun prepareChannel(
        name: String,
        workspace: String,
        originals: MutableMap<String, SessionSettings>,
    ): Conversation {
        val repository = hostRepository()
        val created = runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repository.createChannel(name, workspace) } }
        originals[created.id] = freshSettings(created.id)
        return created
    }

    /** Write [model] and [effort] (`null` leaves one unchanged) to the session a fresh reply names. */
    private fun writeSettings(
        conversationId: String,
        model: String? = null,
        effort: String? = null,
    ) {
        val sessionId = freshSettings(conversationId).sessionId
        check(sessionId.isNotEmpty()) { "the conversation has no session to write to" }
        val repository = hostRepository()
        runBlocking { withTimeout(THREAD_TIMEOUT_MS) { repository.setSessionSettings(sessionId, model = model, effort = effort) } }
    }

    /** A fresh reply for [conversationId] carries exactly [model] and [effort] as the saved choices. */
    private fun assertSaved(
        conversationId: String,
        model: String,
        effort: String,
    ) {
        val saved = freshSettings(conversationId)
        assertEquals("saved model", model, saved.model)
        assertEquals("saved effort", effort, saved.effort)
    }

    /**
     * Write each changed conversation's first reading back to it and clear the remembered level, so the
     * scenario leaves nothing for a later one. A later new chat would otherwise send a recall write. A
     * failed restore is logged and does not replace the scenario's own failure.
     */
    private fun restoreSettings(originals: Map<String, SessionSettings>) {
        originals.forEach { (conversationId, original) ->
            runCatching {
                val now = freshSettings(conversationId)
                val model = original.model.takeIf { it != now.model }
                val effort = original.effort.takeIf { it != now.effort }
                if (model != null || effort != null) {
                    val repository = hostRepository()
                    runBlocking {
                        withTimeout(
                            THREAD_TIMEOUT_MS,
                        ) { repository.setSessionSettings(now.sessionId, model = model, effort = effort) }
                    }
                }
            }.onFailure { Log.w("E2E", "settings restore failed: ${it::class.simpleName}") }
        }
        runCatching { clearRememberedEffort() }.onFailure { Log.w("E2E", "remembered effort clear failed: ${it::class.simpleName}") }
    }

    private fun clearRememberedEffort() {
        runBlocking {
            GlobalContext
                .get()
                .get<AppPreferences>()
                .clearRememberedEffort()
                .getOrThrow()
        }
    }

    private fun rememberedEffort(): String? =
        runBlocking {
            GlobalContext
                .get()
                .get<AppPreferences>()
                .rememberedEffort
                .first()
        }

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /**
     * Selects an Archive tab by its label resource. Archive opens on Channels (#1487), so archived
     * chats need [R.string.archived_tab_discussions].
     */
    private fun openArchiveTab(labelId: Int) {
        val tab = string(labelId).substringBefore(" (")
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(tab, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(tab, substring = true).onFirst().performClick()
    }

    /** An effort note as a Claude conversation words it (#1115); the harness runs Claude conversations only. */
    private fun claudeNote(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, string(R.string.agent_name_claude))

    /** A footer control, found by the click label its merged node announces (#808). */
    private fun footerControl(clickLabel: String): SemanticsMatcher =
        SemanticsMatcher("footer control '$clickLabel'") { it.config.getOrNull(SemanticsActions.OnClick)?.label == clickLabel }

    /** Read the selected run setting from the sheet reached by the footer's tuning button. */
    private fun awaitFooter(
        clickLabel: String,
        label: String,
        state: (String?) -> Boolean = { it != footerPending },
    ) {
        val section = runConfigSection(clickLabel)
        openRunConfiguration()
        val selected =
            SemanticsMatcher("selected run setting '$label'") { node ->
                node.config.getOrNull(SemanticsProperties.Selected) == true &&
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .any { it.text == label }
            }
        try {
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                val hasSelection = label == "Effort" || composeTestRule.onAllNodes(selected).fetchSemanticsNodes().isNotEmpty()
                val pending = composeTestRule.onAllNodesWithText("$section · applying…").fetchSemanticsNodes().isNotEmpty()
                val texts =
                    composeTestRule
                        .onAllNodes(SemanticsMatcher("text") { it.config.getOrNull(SemanticsProperties.Text) != null })
                        .fetchSemanticsNodes()
                        .flatMap {
                            it.config
                                .getOrNull(SemanticsProperties.Text)
                                .orEmpty()
                                .map { text -> text.text }
                        }
                val descriptions = listOf<String?>(if (pending) footerPending else null) + texts
                hasSelection && descriptions.any(state)
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("run configuration '$section' never settled on '$label'", e)
        } finally {
            composeTestRule.onNodeWithContentDescription("Close").performClick()
        }
    }

    /** Open the run configuration sheet and choose its published value. */
    private fun pickFooterOption(
        clickLabel: String,
        optionLabel: String,
    ) {
        runConfigSection(clickLabel)
        openRunConfiguration()
        val option = hasText(optionLabel) and hasClickAction() and isEnabled()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { composeTestRule.onAllNodes(option).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule
            .onAllNodes(option)
            .onFirst()
            .performScrollTo()
            .performClick()
    }

    private fun runConfigSection(clickLabel: String): String =
        when (clickLabel) {
            changeModelLabel -> "Model"
            changeEffortLabel -> "Effort"
            changePermissionLabel -> "Permission"
            else -> error("unknown run configuration control")
        }

    private fun openRunConfiguration() {
        composeTestRule.onNodeWithContentDescription(statusExpandDescription).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("Run configuration").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Tap the Chats row whose name contains [name] and wait for its thread, as [openRow] does for channels. */
    private fun openChatRow(name: String) {
        val row = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(name, substring = true)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) { runCatching { scrollListTo(row) }.isSuccess }
        composeTestRule.onAllNodes(row).onFirst().performClick()
        // A reopened chat can already be running (#1313), including while it awaits permission. Its
        // empty composer shows Stop rather than Send, so either control proves the thread arrived.
        val composerControl = hasContentDescription(CD_SEND_MESSAGE) or hasContentDescription(string(R.string.cd_thread_interrupt))
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(composerControl).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Pull toward older messages on the open thread (#1352): a touch drag down its message region. Only this
     * gesture asks for history, and it asks when it starts at the thread's oldest end, as on a fresh or short
     * thread. A pull while the repository is not yet published asks nothing, so callers repeat it in a wait.
     */
    private fun pullForOlderHistory() {
        runCatching {
            composeTestRule.onNodeWithTag(THREAD_MESSAGE_REGION_TEST_TAG).performTouchInput {
                swipeDown(startY = height * 0.3f, endY = height * 0.7f)
            }
        }
    }

    /** Leave the open thread for the channel list. */
    private fun leaveThread() {
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
    }

    /**
     * Wait until the phone's thread cache for [conversationId] holds an assistant reply. The cache only
     * ever holds settled rows, and the open thread's collector writes them after drawing them, so this is
     * the phone's own proof that the reply settled — not another device's copy of `turn_end`.
     */
    private fun awaitCachedAssistantReply(
        serverId: String,
        conversationId: String,
    ) {
        val cache = GlobalContext.get().get<ConversationCache>()
        runBlocking {
            withTimeout(THREAD_TIMEOUT_MS) {
                while (cache
                        .readThread(
                            serverId,
                            conversationId,
                        ).none { it is ThreadItem.MessageItem && it.message.role == Role.Assistant }
                ) {
                    delay(CACHE_POLL_MS)
                }
            }
        }
    }

    /**
     * Wait until the phone's stored read position for [conversationId] names [turnId] as its completed turn, then
     * assert it is unread (#1581). The attention fold records a `turn_end` as it handles it, after every frame
     * before it on the one inbound stream, and marks it read only while the conversation is viewed.
     */
    private fun awaitUnreadCompletion(
        serverId: String,
        conversationId: String,
        turnId: String,
    ) {
        val cache = GlobalContext.get().get<ConversationCache>()
        val position =
            runBlocking {
                withTimeoutOrNull(THREAD_TIMEOUT_MS) {
                    var stored = cache.readReadPositions(serverId)[conversationId]
                    while (stored?.completedTurnId != turnId) {
                        delay(CACHE_POLL_MS)
                        stored = cache.readReadPositions(serverId)[conversationId]
                    }
                    stored
                }
            }
        assertNotNull("the phone never recorded the off-screen turn's turn_end", position)
        assertTrue("the phone recorded the off-screen turn as read", checkNotNull(position).unread)
    }

    /** Assert the phone's thread cache for [conversationId] holds an assistant row, and none whose text is [reply]. */
    private fun assertNoCachedReply(
        serverId: String,
        conversationId: String,
        reply: String,
    ) {
        val assistantRows =
            runBlocking { GlobalContext.get().get<ConversationCache>().readThread(serverId, conversationId) }
                .filterIsInstance<ThreadItem.MessageItem>()
                .filter { it.message.role == Role.Assistant }
        assertTrue("the thread cache holds no assistant row, so its read proves nothing", assistantRows.isNotEmpty())
        assertTrue(
            "the thread cache already holds the off-screen reply",
            assistantRows.none {
                it.message.content
                    .trim()
                    .equals(reply, ignoreCase = true)
            },
        )
    }

    /** The open thread is [name]'s: its name is shown and [other]'s is not. */
    private fun assertShowingThread(
        name: String,
        other: String,
    ) {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(name).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(other).assertCountEquals(0)
        composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).assertCountEquals(0)
    }

    /** Each of [matchers] matches exactly one node in the unmerged tree. */
    private fun assertDrawnOnce(vararg matchers: SemanticsMatcher) {
        matchers.forEach { composeTestRule.onAllNodes(it, useUnmergedTree = true).assertCountEquals(1) }
    }

    /** The peer's offline reply as a delivered bubble: exactly [OFFLINE_REPLY], as [pingReplyMatcher] anchors ping. */
    private fun offlineReplyMatcher(): SemanticsMatcher =
        hasText(OFFLINE_REPLY, ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))

    /**
     * Type [text] and send it from the open thread. While a turn runs the button is Stop until the composer
     * holds text, so the tap waits for Send rather than interrupting the turn.
     */
    private fun sendFromPhone(text: String) {
        composeTestRule.onNode(hasSetTextAction()).performTextInput(text)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()
    }

    /** A node inside the thread's scrollable list with exactly [text]: a bubble or a queued row, not the top bar. */
    private fun inThreadList(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasScrollToNodeAction())

    /** The queued row for [text]: its merged node carries the text and the "Waiting to send" state. */
    private fun queuedRow(text: String): SemanticsMatcher =
        hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, queuedStateDescription)

    /** Wait until [text] draws as a queued row in the open thread. */
    private fun awaitQueuedRow(text: String) {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(queuedRow(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Wait until nothing in the open thread's list carries [text] — neither a queued row nor a bubble. */
    private fun awaitGoneFromThread(text: String) {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(inThreadList(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** The ids [serverId]'s live repository lists, read off whichever connection is current (#1036). */
    private fun hostConversationIds(serverId: String): Set<String> = hostConversationIds(serverId, "its conversation list") { true }

    /**
     * The one conversation id [serverId]'s live repository lists that [before] does not: the chat the phone
     * just created. Read off whichever connection is current (#1036).
     */
    private fun newHostConversationId(
        serverId: String,
        before: Set<String>,
    ): String = (hostConversationIds(serverId, "a new chat's id") { ids -> (ids - before).isNotEmpty() } - before).single()

    /** Delete a created chat even if a UI or repository wait failed before its id was captured. */
    private fun cleanupCreatedConversation(
        serverId: String,
        before: Set<String>,
        knownId: String?,
        failureMessage: String,
    ) {
        runCatching {
            val id = knownId ?: newHostConversationId(serverId, before)
            runBlocking { withTimeout(THREAD_TIMEOUT_MS) { hostRepository(serverId).delete(id) } }
        }.onFailure { Log.w("E2E", "$failureMessage: ${it::class.simpleName}") }
    }

    /**
     * The first conversation-id set [serverId]'s repository lists that satisfies [ready], following the host's
     * redial with `firstOnLive` as #1029's reads do; a timeout names [what] the phone was reading. [filter]
     * narrows the list read, `All` by default.
     */
    private fun hostConversationIds(
        serverId: String,
        what: String,
        filter: ConversationFilter = ConversationFilter.All,
        ready: (Set<String>) -> Boolean,
    ): Set<String> {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        return runBlocking {
            withTimeoutOrNull(LIST_TIMEOUT_MS) {
                bundle.coordinator.currentRepository.firstOnLive({ repository ->
                    repository.observeConversations(filter).map { rows -> rows.mapTo(mutableSetOf()) { it.id } }
                }, ready)
            }
        } ?: throw AssertionError("the phone's live repository never listed $what within $LIST_TIMEOUT_MS ms")
    }

    /**
     * Run one suspending [peer] call, and name [step] and the peer's session state if it times out (#1036):
     * a coroutine timeout's own stack says nothing about which wait ran out.
     */
    private fun <T> peerStep(
        peer: SecondClientPeer,
        step: String,
        block: suspend () -> T,
    ): T =
        runBlocking {
            withTimeoutDiagnostic({ "peer step '$step' timed out; ${peer.linkState()}" }, block)
        }

    /**
     * [awaitDisplayedPingReply] for a ping sent after [priorTurnEnds] of [conversationId]'s turns ended. On
     * timeout it names the first layer that did not hold the reply (#1456): the [peer]'s recorded frames, the
     * phone's live repository for [serverId], or the thread screen's nodes.
     */
    private fun awaitPingReplyNamingLayer(
        peer: SecondClientPeer,
        serverId: String,
        conversationId: String,
        priorTurnEnds: Int,
    ) {
        try {
            composeTestRule.awaitDisplayedPingReply(REPLY_TIMEOUT_MS)
        } catch (e: ComposeTimeoutException) {
            val frames = peer.recorded(conversationId)
            val nodes = composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).fetchSemanticsNodes().size
            throw PingReplyEvidence(
                peerTurnEnds = frames.count { it.type == "turn_end" },
                peerSawReply = followUpPingReplyRecorded(frames, priorTurnEnds),
                repositoryHoldsReply = liveRepositoryHoldsPingReply(serverId, conversationId),
                replyNodes = nodes,
                replyDisplayed = nodes == 1 && composeTestRule.onNode(pingReplyMatcher(), useUnmergedTree = true).isDisplayed(),
            ).failure(expectedTurnEnds = priorTurnEnds + 1, cause = e)
        }
    }

    /**
     * Whether [serverId]'s current live repository holds an assistant `ping` row for [conversationId] (#1456),
     * or null when there is no repository or its thread does not emit in time.
     */
    private fun liveRepositoryHoldsPingReply(
        serverId: String,
        conversationId: String,
    ): Boolean? {
        val repository =
            GlobalContext
                .get()
                .get<RelayConnectionRegistry>()
                .connectionFor(serverId)
                ?.coordinator
                ?.currentRepository
                ?.value ?: return null
        return runBlocking { withTimeoutOrNull(THREAD_TIMEOUT_MS) { holdsPingReply(repository.observeMessages(conversationId).first()) } }
    }

    /** The #849 peer on the first test daemon, the one device allowed to answer its permission prompts. */
    private fun runningToolPeer(): SecondClientPeer {
        val args = InstrumentationRegistry.getArguments()
        return SecondClientPeer(
            PairedServer(
                serverId = twoHostArg(ARG_SERVER_ID),
                token = twoHostArg(ARG_PEER_TOKEN),
                relayUrl = requireNotNull(args.getString(ARG_RELAY_URL)),
                serverStaticPublicKey = requireNotNull(args.getString(ARG_SERVER_STATIC_PUBLIC_KEY)),
            ),
        )
    }

    /**
     * Create a chat, open [peer], send [prompt] from the phone and wait until claude's command waits on a
     * permission prompt (#950). Returns the chat's id and the prompt's modal id; the tool call stays open
     * until the peer answers.
     */
    private fun holdToolOnPermission(
        peer: SecondClientPeer,
        prompt: String,
    ): Pair<String, String> {
        val serverId = twoHostArg(ARG_SERVER_ID)
        awaitChannelList()
        awaitConnected()
        val before = hostConversationIds(serverId)
        createChat()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        val conversationId = newHostConversationId(serverId, before)
        peerStep(peer, "open running-tool peer") { peer.open(CONNECT_TIMEOUT_MS) }
        sendFromPhone(prompt)
        val modalId = peerStep(peer, "await held tool's permission modal") { peer.awaitPermissionModal(conversationId, REPLY_TIMEOUT_MS) }
        return conversationId to modalId
    }

    /** A two-host instrumentation argument (#847), failing with the script that passes it. */
    private fun twoHostArg(key: String): String =
        requireNotNull(InstrumentationRegistry.getArguments().getString(key)) {
            "missing instrumentation arg '$key' — scripts/e2e-emulator.sh passes it on rung 3 and LIVE"
        }

    /** A conversation row in the Channels tier carrying exactly [name]. */
    private fun channelRow(name: String): SemanticsMatcher = hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText(name)

    /** Scroll the tree until a node matching [matcher] is composed, so a long list cannot hide it. */
    private fun scrollListTo(matcher: SemanticsMatcher) {
        composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
    }

    /** Wait until the Channels row named [name] is on screen. */
    private fun awaitChannelRow(name: String) {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            runCatching { scrollListTo(channelRow(name)) }.isSuccess
        }
        composeTestRule.onAllNodes(channelRow(name)).onFirst().assertIsDisplayed()
    }

    /**
     * The seeded collision is real: [serverId]'s own repository holds [conversationId] under [name]. Without
     * this a seed that wrote two different ids would pass every UI check below trivially.
     */
    private fun assertHostHoldsConversation(
        serverId: String,
        conversationId: String,
        name: String,
    ) {
        assertEquals(name, heldConversationName(serverId, conversationId))
    }

    /** The name [serverId]'s own live repository holds for [conversationId] now. */
    private fun heldConversationName(
        serverId: String,
        conversationId: String,
    ): String? = heldConversation(serverId, conversationId).name

    /** The row [serverId]'s own live repository holds for [conversationId] now. */
    private fun heldConversation(
        serverId: String,
        conversationId: String,
    ): Conversation {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        return runBlocking {
            withTimeout(LIST_TIMEOUT_MS) {
                val repository = bundle.coordinator.currentRepository.first { it != null }
                checkNotNull(repository)
                    .observeConversations(ConversationFilter.All)
                    .first { rows -> rows.any { it.id == conversationId } }
                    .first { it.id == conversationId }
            }
        }
    }

    /** What #1085 holds host A to while host B is renamed and unpaired, captured before B is paired. */
    private data class FirstHost(
        val serverId: String,
        val label: String,
        val bundle: RelayConnectionBundle,
        val conversationIds: Set<String>,
        val conversationName: String,
    )

    /**
     * Host A is as [first] recorded it: the same stored label on its Edit control, the **same** connection
     * bundle with a live repository (the registry keys bundles by record, so managing B must neither close
     * nor rebuild A's), the same conversation ids, and its conversation's row still drawn.
     */
    private fun assertFirstHostUntouched(first: FirstHost) {
        assertEquals("host A's label changed", first.label, hostLabel(first.serverId))
        val bundle = GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(first.serverId)
        assertSame("host A's connection was replaced", first.bundle, bundle)
        runBlocking {
            withTimeout(CONNECT_TIMEOUT_MS) {
                first.bundle.coordinator.currentRepository
                    .first { it != null }
            }
        }
        assertEquals("host A's conversations changed", first.conversationIds, hostConversationIds(first.serverId))
        scrollListTo(hasContentDescription(hostEditDescription(first.label)))
        awaitChannelRow(first.conversationName)
    }

    /** The host row's Edit control's content description for a host labelled [label]. */
    private fun hostEditDescription(label: String): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_tree_host_edit, label)

    /** Tap [serverId]'s Edit control on its host row and wait for the Edit host modal. */
    private fun openHostEditor(serverId: String) {
        val tag = treeHostEditTestTag(serverId)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            runCatching { scrollListTo(hasTestTag(tag)) }.isSuccess
        }
        composeTestRule.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        awaitEditorTitle(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    /** Wait until the Edit host modal shows its editor (not its unpair confirmation). */
    private fun awaitEditorTitle(context: Context) {
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(context.getString(R.string.edit_host_title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The Edit host modal is gone: neither its editor title nor its unpair confirmation title is drawn. */
    private fun editorClosed(context: Context): Boolean =
        composeTestRule.onAllNodesWithText(context.getString(R.string.edit_host_title)).fetchSemanticsNodes().isEmpty() &&
            composeTestRule.onAllNodesWithText(context.getString(R.string.edit_host_unpair_confirm_title)).fetchSemanticsNodes().isEmpty()

    /** Tap the open editor's Unpair host and wait for the in-place confirmation. */
    private fun requestUnpair(context: Context) {
        composeTestRule.onNode(hasText(context.getString(R.string.edit_host_unpair)) and hasClickAction()).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(context.getString(R.string.edit_host_unpair_confirm_title))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** Open Settings through the channel list header menu. */
    private fun openSettings() {
        openListMenuEntry(R.string.settings_title)
    }

    private fun openListMenuEntry(label: Int) {
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_open_menu))).performClick()
        composeTestRule.onNodeWithText(string(label)).performClick()
    }

    /**
     * On Settings, make [serverId]'s Settings the one on screen: tap its Connection row unless it is already
     * the owner's, then wait until that row carries the owner badge and exactly one Settings is composed, so
     * no screen leaving the hop can answer a later check. The badge, not selection, proves where it landed.
     *
     * The row's texts and badge are a descendant of its click target, not on it: the row's `ListItem` merges
     * its own semantics, so the clickable `Column` around it carries the click action alone.
     */
    private fun showHostSettings(serverId: String) {
        val row = hasClickAction() and hasAnyDescendant(hasText(serverId))
        val owner = row and hasAnyDescendant(hasText(string(R.string.settings_host_current)))
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(row).fetchSemanticsNodes().size == 1
        }
        if (composeTestRule.onAllNodes(owner).fetchSemanticsNodes().isEmpty()) {
            composeTestRule.onNode(row).performScrollTo().performClick()
        }
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(owner).fetchSemanticsNodes().size == 1 &&
                composeTestRule.onAllNodesWithText(DEFAULT_WORKSPACE_ROW).fetchSemanticsNodes().size == 1
        }
    }

    /** Create from [serverId]'s Chats section and return its id once its thread is open. */
    private fun createChatOn(serverId: String): String {
        val before = hostConversationIds(serverId)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            runCatching { scrollListTo(hasTestTag(treeHostChatAddTestTag(serverId))) }.isSuccess
        }
        createChat(serverId)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }
        return newHostConversationId(serverId, before)
    }

    /** Wait until the list can scroll to a row carrying [text]. */
    private fun awaitListText(text: String) {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            runCatching { scrollListTo(hasText(text, substring = true)) }.isSuccess
        }
    }

    /** Archive the open thread's conversation from its overflow and wait for the pop back to the list. */
    private fun archiveOpenThread() {
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(ARCHIVE_ITEM).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(ARCHIVE_ITEM).performClick()
        awaitChannelList()
    }

    /** The first archived-id set [serverId]'s live repository lists that satisfies [ready]. */
    private fun archivedIds(
        serverId: String,
        ready: (Set<String>) -> Boolean,
    ): Set<String> = hostConversationIds(serverId, "its archive", ConversationFilter.Archived, ready)

    /** The host row's label as the tree draws it: the saved display name, else "Unnamed host". */
    private fun hostLabel(serverId: String): String {
        val saved = runBlocking { GlobalContext.get().get<PairedServerCollectionStore>().loadById(serverId) }
        return saved?.displayName?.takeIf { it.isNotBlank() }
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.unnamed_host)
    }

    /**
     * Pair a second host by pasting [pairCode]: the toolbar's add control opens the scanner, whose
     * every state offers a paste link ([PASTE_CODE_LINK]), which opens `PairCodeScreen`. Pair → confirm the
     * fingerprint → the screen waits for Connected and returns to the list.
     */
    private fun pairHostByCode(
        pairCode: String,
        hostName: String = HOST_B_NAME,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pairControl = context.getString(R.string.cd_pair_another_host)
        composeTestRule.onNode(hasContentDescription(pairControl)).performClick()
        val pasteLink = hasText(PASTE_CODE_LINK, substring = true) and hasClickAction()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(pasteLink).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodes(pasteLink).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction() and hasText(PAIR_CODE_FIELD)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and hasText(HOST_NAME_FIELD)).performTextInput(hostName)
        composeTestRule.onNode(hasSetTextAction() and hasText(PAIR_CODE_FIELD)).performTextInput(pairCode)
        composeTestRule.onNode(hasText(PAIR_BUTTON) and hasClickAction()).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CONFIRM_PAIRING).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(CONFIRM_PAIRING).performClick()
        // Save, then up to the view model's 30 s connection wait, then the pop back to the list.
        composeTestRule.waitUntil(PAIR_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Pair a host through the scanner's own confirm (#1386), with [payload] standing in for a decoded QR. The
     * list's pair-another-host control opens the scanner; once its view model is
     * [ReadyToScan][ScannerUiState.ReadyToScan] the payload goes in as [ScannerEvent.QrDecoded] and the route
     * prepares the confirmation itself. Confirm → save → wait for the host → only on
     * [Paired][ScannerUiState.Paired] does the route open the list.
     *
     * `MainActivity` keeps its nav controller to itself, so the route's view model is captured where Koin
     * builds it ([scannerViewModelModule]) and the plain definition is restored afterwards.
     */
    private fun pairHostByScanner(payload: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // The scanner asks for CAMERA at runtime; granting it first keeps the system dialog off screen.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val captured = AtomicReference<ScannerViewModel?>()
        loadKoinModules(scannerViewModelModule { captured.set(it) })
        try {
            composeTestRule.onNode(hasContentDescription(context.getString(R.string.cd_pair_another_host))).performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) { captured.get()?.state?.value == ScannerUiState.ReadyToScan }
            val scanner = checkNotNull(captured.get())
            instrumentation.runOnMainSync { scanner.onEvent(ScannerEvent.QrDecoded(payload)) }
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText(CONFIRM_PAIRING).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText(CONFIRM_PAIRING).performClick()
            // Save, then up to the view model's 30 s connection wait, then the navigation to the list.
            composeTestRule.waitUntil(PAIR_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(ScannerUiState.Paired, scanner.state.value)
        } finally {
            loadKoinModules(scannerViewModelModule {})
        }
    }

    /**
     * A mirror of `AppModule`'s [ScannerViewModel] definition that also hands [onCreated] each instance.
     * The scenario's `finally` reloads this mirror, so later live methods resolve the scanner VM from it:
     * keep it in step with `AppModule`.
     */
    private fun scannerViewModelModule(onCreated: (ScannerViewModel) -> Unit): Module =
        module {
            viewModel {
                val registry = get<RelayConnectionRegistry>()
                ScannerViewModel(get(), registry, registry::pairingStatus).also(onCreated)
            }
        }

    /** Both halves of "separate", for both hosts: see [assertEachUnderOwnHost] and [assertRowOpensOwnThread]. */
    private fun assertHostsStaySeparate(
        hostA: Pair<String, String>,
        hostB: Pair<String, String>,
    ) {
        assertEachUnderOwnHost(hostA, hostB)
        assertEachUnderOwnHost(hostB, hostA)
        assertRowOpensOwnThread(hostA.second, hostB.second)
        assertRowOpensOwnThread(hostB.second, hostA.second)
    }

    /**
     * Folding [host]'s Channels row (label to conversation name) hides its own conversation and leaves
     * [other]'s: the row was drawn under that host. The first fold control of a label is the Channels
     * section's, which the tree draws before the Chats section. The fold is undone before returning.
     */
    private fun assertEachUnderOwnHost(
        host: Pair<String, String>,
        other: Pair<String, String>,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val collapse = hasContentDescription(context.getString(R.string.cd_tree_row_collapse, host.first))
        val expand = hasContentDescription(context.getString(R.string.cd_tree_row_expand, host.first))
        awaitChannelRow(host.second)
        scrollListTo(collapse)
        composeTestRule.onAllNodes(collapse).onFirst().performClick()
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(channelRow(host.second)).fetchSemanticsNodes().isEmpty()
        }
        awaitChannelRow(other.second)
        scrollListTo(expand)
        composeTestRule.onAllNodes(expand).onFirst().performClick()
        awaitChannelRow(host.second)
    }

    /** [name]'s row opens a thread titled [name], never [other]; then back to the list. */
    private fun assertRowOpensOwnThread(
        name: String,
        other: String,
    ) {
        openRow(name)
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(name).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(other).assertCountEquals(0)
        composeTestRule.onNode(hasContentDescription(CD_BACK)).performClick()
        awaitChannelList()
    }

    /**
     * Tap the Channels row named [name] and wait for its thread: the send button is drawn and the list has
     * left composition, so no row still fading out of the transition can answer a thread-side name check.
     */
    private fun openRow(name: String) {
        awaitChannelRow(name)
        composeTestRule.onAllNodes(channelRow(name)).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Rename the open thread's conversation to [newName] and wait for its top bar to re-label (#537's drive).
     * A chat renames through its menu's Rename dialog; a channel through its menu's Edit, which opens Edit
     * channel (#1561).
     */
    private fun renameOpenThread(newName: String) {
        val edit = string(R.string.thread_overflow_edit)
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithText(edit).fetchSemanticsNodes().isNotEmpty()
        }
        if (composeTestRule.onAllNodesWithText(edit).fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onAllNodesWithText(edit).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasTestTag(CHANNEL_NAME_FIELD_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextReplacement(newName)
            composeTestRule.onNodeWithText(EDIT_CHANNEL_OK).performClick()
            awaitChannelEditorClosed()
        } else {
            composeTestRule.onAllNodesWithText(RENAME_ITEM).onFirst().performClick()
            composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
                composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(hasSetTextAction() and isFocused()).performTextReplacement(newName)
            composeTestRule.onNodeWithText(RENAME_SAVE).performClick()
        }
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(newName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Cut and restore one host's link, the per-host form of the rung-4 close / connect drive. Readiness is
     * the coordinator's repository, not a `ConnectionState`: an idle `Connected` after `close()` would
     * false-green a state check.
     */
    private fun cycleHostLink(serverId: String) {
        setHostLink(serverId, up = false)
        setHostLink(serverId, up = true)
    }

    /**
     * Cut ([up] false) or restore one host's link, and wait until its coordinator's repository is gone or
     * back. Cutting tears down the connection-scoped repository, so what the phone still draws is retained.
     */
    private fun setHostLink(
        serverId: String,
        up: Boolean,
    ) {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId)) { "host not registered" }
        runBlocking {
            withTimeoutDiagnostic({
                "host link ${if (up) "reconnect" else "close"} timed out; repository present=${bundle.coordinator.currentRepository.value != null}"
            }) {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    if (up) bundle.supervisor.connect() else bundle.supervisor.close()
                    bundle.coordinator.currentRepository.first { (it != null) == up }
                }
            }
        }
    }

    /**
     * Block until the channel list is on screen, keyed on the app-authored marker the list screen sets
     * ([CHANNEL_LIST_TEST_TAG], #736) rather than on anything drawn on it. Every scenario arrives through
     * here, including the two `@Ignore`d manual ones.
     *
     * Deliberately **weaker** than the "New discussion" wait it replaced. That control drew only on a loaded
     * flat state, so waiting for it implied a loaded list; this marker is on both of the list's draws — the
     * blank placeholder and the assembled tree — and implies only that the list is the destination on
     * screen. [awaitHostChatAddControl] carries that wait now for the creation helper:
     * acting on a control before it is drawn fails the drive, not the wait.
     */
    private fun awaitChannelList() {
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Create a fresh chat directly from one host's Chats section. */
    private fun createChat(serverId: String = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID))) {
        awaitHostChatAddControl(serverId).performClick()
    }

    /** Drive the thread's own picker through the overflow path, usable after the chip disappears. */
    private fun openThreadWorkspacePicker() {
        composeTestRule.onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(CHANGE_WORKSPACE_ITEM, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText(CHANGE_WORKSPACE_ITEM, substring = true).onFirst().performClick()
    }

    /** Wait for one host's Chats plus by the host id supplied by the harness. */
    private fun awaitHostChatAddControl(
        serverId: String = requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_SERVER_ID)),
    ): SemanticsNodeInteraction {
        val tag = treeHostChatAddTestTag(serverId)
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        return composeTestRule.onAllNodes(hasTestTag(tag)).onFirst()
    }

    /** Block until the relay connection reports [ConnectionState.Connected], or fail after a timeout. */
    private fun awaitConnected() {
        val source = GlobalContext.get().get<ConnectionStateSource>()
        runBlocking {
            withTimeout(CONNECT_TIMEOUT_MS) {
                source.observe().first { it is ConnectionState.Connected }
            }
        }
    }

    private companion object {
        /** The inline permission request's card (#1306), from `permissionRequestItems`. */
        const val PERMISSION_CARD_TEST_TAG = "permission-request-card"

        // Tool-use determinism lever (#481): a direct imperative to RUN a shell command reliably makes
        // real claude use its shell tool (claude names it "Bash"), where "what does X output?" might be
        // answered inline. `echo <fixed string>` is read-only, side-effect-free, and harmless on the
        // host. "then stop without commentary" keeps surrounding prose minimal. The prompt contains
        // neither "Bash" nor "bash" so the asserted tool-name substring can only come from the tool row.
        const val TOOL_PROMPT = "Run this exact shell command with your tools, then stop without commentary: echo pyry481"

        // Claude's verbatim shell-tool name; renders in the tool-row header (#388) in all three states.
        const val TOOL_NAME = "Bash"

        // #1311: a tool call and then a text answer in one turn, so the band is sampled across thinking, a
        // running tool and the responding text. `echo` is read-only and auto-allowed, as in TOOL_PROMPT.
        const val TOOL_THEN_TEXT_PROMPT =
            "Run this exact shell command with your tools: echo pyry1311. Then reply with one short sentence " +
                "saying what it printed."

        // Negative control: a real, distinct tool name the read-only echo prompt never asks claude to
        // use, so the matcher's selectivity is what is proven (not a nonsense string).
        const val TOOL_NEVER_USED = "Edit"

        // Pure-reasoning determinism lever (#482): keeps real claude *thinking* (no tool call → the #428
        // permission modal never interposes, unlike TOOL_PROMPT) and asks it to reason a beat before
        // replying with only a short token, widening the transient turn_state(thinking) window the spinner
        // shows during. Correctness does NOT depend on the window being reliably catchable — that is why
        // the test ships @Ignore'd; the exact wording is the developer's to tune on first operator run.
        const val THINK_PROMPT =
            "Without using any tools, take a moment to reason this through silently, then reply with only " +
                "the single word: ready. (Reason through first: what is the 12th prime number?)"

        // Production UI strings. Keep in sync with res/values/strings.xml:
        //   cd_send_message = "Send message", cd_back = "Back".
        // The button's fixed "New discussion" description went with the button (#738). Creation is now
        // addressed by the paired host's own tag, built in awaitHostChatAddControl from the harness's own
        // serverId argument, so nothing here has to name it.
        const val CD_SEND_MESSAGE = "Send message"
        const val CD_BACK = "Back"

        // #541 new-session scenario. Overflow-menu production strings (no test tags): CD_MORE_ACTIONS opens
        // the menu; NEW_SESSION_ITEM is the tap target. The durable matcher is SESSION_BOUNDARY_TEST_TAG,
        // the reason-independent test tag that only the rendered SessionBoundaryDelimiter carries (#1578).
        // Keep in sync with res/values/strings.xml:
        //   cd_more_actions = "More actions", thread_overflow_new_session = "Reset session".
        const val CD_MORE_ACTIONS = "More actions"
        const val NEW_SESSION_ITEM = "Reset session"

        // #566 create-workspace-folder scenario. Picker/dialog production strings (no test tags):
        //   the WorkspacePickerSheet create row (matched as a substring so the trailing ellipsis need
        //   not be reproduced), the CreateFolderDialog confirm button, and the picker's "Recent" header.
        const val CREATE_FOLDER_ROW = "Create new folder under pyry-workspace"
        const val CREATE_BUTTON = "Create"
        const val RECENT_SECTION = "Recent"

        // #904: Add workspace's submit — MobileModal's fixed footer label.
        const val OK_BUTTON = "OK"

        // Collision-resistant folder-name prefix: a clean single path element (lowercase alphanumerics +
        // dash — the daemon rejects empty / absolute / separator-bearing / ".." names). Suffixed with
        // System.currentTimeMillis() at runtime so repeated LIVE gate runs never collide under the
        // operator's real ~/pyry-workspace (#527 isolates the pyry instance name, not $HOME).
        const val FOLDER_NAME_PREFIX = "e2e566-"

        // #562 change-workspace scenario. Reuses the #566 picker/dialog constants (CREATE_FOLDER_ROW,
        // CREATE_BUTTON) and the overflow opener (CD_MORE_ACTIONS); adds only these two. CHANGE_WORKSPACE_ITEM
        // is the overflow item, matched as a SUBSTRING — the production string is "Change workspace…" with a
        // real U+2026 ellipsis (mirrors how CREATE_FOLDER_ROW drops the trailing ellipsis). Keep in sync with
        // res/values/strings.xml: thread_overflow_change_workspace = "Change workspace…".
        const val CHANGE_WORKSPACE_ITEM = "Change workspace"

        // Runtime-unique target-folder prefix: "e2e562-" + System.currentTimeMillis(). Distinct from #566's
        // FOLDER_NAME_PREFIX (the shared companion forbids redeclaration). A clean single path element
        // (lowercase alphanumerics + dash — the daemon rejects empty / absolute / separator-bearing / ".."
        // names). Unique so a substring match cannot pre-exist on screen — the absence guard (step 3) and its
        // inversion, the chip presence after change_workspace (step 6), are both genuine; also keeps repeated
        // LIVE gate runs clean under the operator's real ~/pyry-workspace (no collision/accumulation).
        const val WORKSPACE_FOLDER_PREFIX = "e2e562-"

        // #554 delete-conversation scenario. Overflow / sheet / dialog production strings (no test tags).
        // RENAME_ITEM + RENAME_SAVE drive the rename that gives the seeded discussion a runtime-unique,
        // list-visible identity; CHANNEL_INFO_ITEM (ungated) opens the sheet whose Actions block holds the
        // Delete affordance. DELETE_ACTION is the ONE gotcha: the sheet's ActionCell label AND the confirm
        // dialog's button are BOTH the literal "Delete", and ThreadEvent.Delete leaves the sheet composed
        // behind the dialog, so both nodes are on screen at confirm time — the confirm tap is disambiguated
        // by DELETE_DIALOG_CANCEL, the dialog's sibling button the sheet has no equivalent of.
        // DELETE_DIALOG_TITLE is the unique wait anchor for the opened dialog. Keep in sync with
        // res/values/strings.xml: thread_overflow_rename = "Rename", rename_dialog_save = "Save",
        // thread_overflow_channel_info = "Channel info", delete_dialog_confirm = "Delete" (== the sheet's
        // ActionCell literal), delete_dialog_title = "Delete conversation?", delete_dialog_cancel = "Cancel".
        const val RENAME_ITEM = "Rename"
        const val RENAME_SAVE = "Save"
        const val CHANNEL_INFO_ITEM = "Channel info"

        // #1346: Channel info's own strings — the absent-value text and the sheet's close button.
        const val SESSION_VALUE_NOT_REPORTED = "Not reported"
        const val CD_CLOSE_SHEET = "Close"

        // #1344: Channel info's MCP section — the Show built-in switch's label and the daemon's own approval server.
        const val MCP_SHOW_BUILT_IN = "Show built-in"
        const val MCP_BUILT_IN_APPROVE = "pyry_approve"
        const val DELETE_ACTION = "Delete"
        const val DELETE_DIALOG_TITLE = "Delete conversation?"
        const val DELETE_DIALOG_CANCEL = "Cancel"

        // Runtime-unique rename target: "e2e554-" + System.currentTimeMillis(). Unique so a substring match
        // cannot pre-exist on screen — the presence check (step 5) and its inversion, assertCountEquals(0)
        // after delete (step 9), are both genuine. #566 unique-folderName / #481 token-omission discipline,
        // applied here to an ABSENCE assertion. Also keeps repeated LIVE gate runs clean (no accumulation).
        const val CONVERSATION_NAME_PREFIX = "e2e554-"

        // #551 archive/restore round-trip scenario. Archive from the thread overflow (ARCHIVE_ITEM,
        // mutationsSupported-gated) is IMMEDIATE — no confirm dialog, unlike #554's DELETE_ACTION. Restore
        // opens Archive from the list header menu; ARCHIVED_TITLE is its top-bar arrival anchor; RESTORED_SNACKBAR
        // is the restore-completion guard (a prefix of "Restored %1$s", appearing in no other on-screen
        // string). The restore affordance is keyed on the unique name via its content-description ("Restore
        // <name>"), so it needs no constant. Keep in sync with res/values/strings.xml:
        //   thread_overflow_archive = "Archive", archived_title = "Archived",
        //   restored_snackbar = "Restored %1$s".
        const val ARCHIVE_ITEM = "Archive"

        // Other scenarios still reference the retired Settings row while their #1245 ignores remain.
        const val ARCHIVED_ROW = "Archived discussions"
        const val ARCHIVED_TITLE = "Archived"
        const val RESTORED_SNACKBAR = "Restored"

        // Runtime-unique rename target: "e2e551-" + System.currentTimeMillis(). Distinct from #554's
        // CONVERSATION_NAME_PREFIX (the shared companion forbids redeclaration). Unique so a substring match
        // cannot pre-exist on screen — the presence check (step 5), its inversion after archive (step 8), and
        // the re-appearance after restore (step 13) are all genuine; also keeps repeated LIVE gate runs clean.
        const val ARCHIVE_NAME_PREFIX = "e2e551-"

        // #1332 archive-order scenario: runtime-unique names for its two chats.
        const val ARCHIVE_ORDER_PREFIX = "e2e1332-"

        // #537 rename-conversation scenario. Reuses the #554 rename constants (RENAME_ITEM, RENAME_SAVE) and
        // the overflow opener (CD_MORE_ACTIONS); adds only this prefix. Runtime-unique rename target:
        // "e2e537-" + System.currentTimeMillis(). Distinct prefix (the shared companion forbids redeclaration;
        // each scenario owns its own). Unique so a substring match cannot pre-exist on screen — the absence
        // guard (step 4) and its inversions on the top bar (step 6) and the list row (step 7) are all genuine;
        // also keeps repeated LIVE gate runs green (no collision with titles left by prior runs) and does not
        // collide as a substring with top-bar / list chrome the assertion also matches.
        const val RENAME_NAME_PREFIX = "e2e537-"

        // #581 save-as-channel (promote) scenario, driven through the #957 modal. Reuses the overflow opener
        // (CD_MORE_ACTIONS) and the list / thread markers. SAVE_AS_CHANNEL_ITEM and SAVE_AS_CHANNEL_TITLE are
        // matched EXACTLY — the production menu item ends in a real U+2026 ellipsis and the modal title is the
        // same literal WITHOUT it, so substring matching would conflate them. SAVE_AS_CHANNEL_OK is the
        // MobileModal footer's submit label. SAVE_AS_CHANNEL_PROMPT is typed into the prompt field so the
        // post-promote set_system_prompt write rides the run; it takes effect at the next session start, which
        // this scenario never reaches. WORKSPACE_CHIP_PREFIX is the in-thread isPromoted tier signal: the
        // WorkspaceChip's label is a hardcoded literal ("Workspace: <label> (change)"), not a string resource, so
        // this prefix is the matchable token — mounted before the promote, unmounted after. The tier read needs no
        // constant of its own: it matches the row test tags the assembling screen sets (#731). Keep in sync with
        // res/values/strings.xml: save_as_channel_action = "Save as channel…",
        // save_as_channel_dialog_title = "Save as channel".
        const val SAVE_AS_CHANNEL_ITEM = "Save as channel…"
        const val SAVE_AS_CHANNEL_TITLE = "Save as channel"
        const val SAVE_AS_CHANNEL_OK = "OK"
        const val SAVE_AS_CHANNEL_PROMPT = "e2e957: answer briefly."
        const val WORKSPACE_CHIP_PREFIX = "Workspace:"

        // Runtime-unique promote target: "e2e581-" + System.currentTimeMillis(). Distinct prefix (the shared
        // companion forbids redeclaration; each scenario owns its own). Unique so a substring match cannot
        // pre-exist on screen — the absence guard (step 4), its inversions on the top bar (step 6) and the main
        // list (step 7), and the chat-tier absence (step 8) are all genuine; also keeps repeated LIVE
        // gate runs green (no collision with channels left by prior runs) and does not collide as a substring
        // with top-bar / list chrome the assertions also match.
        const val PROMOTE_NAME_PREFIX = "e2e581-"

        // #1021 mute scenario: a run-unique channel name, and Edit channel's OK button.
        const val MUTE_NAME_PREFIX = "e2e1021-"
        const val EDIT_CHANNEL_OK = "OK"

        // #847 two-host scenario. The five arguments scripts/e2e-emulator.sh passes on rung 3 and LIVE
        // (host A's own four stay E2eTestApplication's). PAIR_CODE_B carries a pairing token: never log it.
        const val ARG_SERVER_ID_B = "serverIdB"
        const val ARG_PAIR_CODE_B = "pairCodeB"
        const val ARG_COLLISION_CONVERSATION_ID = "collisionConversationId"
        const val ARG_COLLISION_NAME_A = "collisionNameA"
        const val ARG_COLLISION_NAME_B = "collisionNameB"

        // #1571 dormant-channel scenario: the seeded conversation's id, run-unique name and stored reply text.
        const val ARG_DORMANT_CONVERSATION_ID = "dormantConversationId"
        const val ARG_DORMANT_NAME = "dormantName"
        const val ARG_DORMANT_REPLY = "dormantReply"

        // #848 peer scenario. PEER_TOKEN is the second device's pairing token that scripts/e2e-emulator.sh
        // mints on host A for the SecondClientPeer (rung 3 and LIVE): never log it. The chat's run-unique
        // name shares no substring with PING_PROMPT, "ping" or the other scenarios' prefixes.
        const val ARG_PEER_TOKEN = "peerToken"
        const val PEER_CHAT_NAME_PREFIX = "e2e848-"

        // #849 queue scenario. WAIT_PROMPT reuses #481's shell-tool lever to hold the peer's turn open for
        // the queue steps. What holds it is the permission prompt the command raises, not the command's
        // run time: a `python3` command is never auto-allowed, and the harness's devices answer nothing
        // until the peer, paired with `--allow-remote-permissions`, allows it. The command itself is quick; a
        // timed wait is not needed, and a bare `sleep` of 25 s or more is refused by claude's Bash tool. DROP_PROMPT and PEER_QUEUED_PROMPT ask for reply tokens no other prompt produces, so an exact-text node with either
        // would mean a dropped message reached claude. None of the texts or the chat prefix contains the exact
        // word "ping".
        const val WAIT_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, then reply " +
                "with exactly: pyrywait. Command: python3 -c \"print(849)\""
        const val DROP_PROMPT = "Reply with exactly: pyrydropped"

        // #1558: WAIT_PROMPT's reply, the wait turn's last row. Matched exactly, so the prompt that names it
        // never matches.
        const val WAIT_REPLY = "pyrywait"

        // #950 running-tool label. All three hold claude's Bash call on WAIT_PROMPT's permission lever. Once
        // allowed, HELD_TOOL_PROMPT's command stays open for 10 s so the label can be seen after the prompt
        // closes (#1528), and ELAPSED_TOOL_PROMPT's runs past claude's first tool_progress heartbeat. The
        // sleeps sit inside python3 because claude's Bash tool refuses a bare `sleep` of 25 s or more.
        // ELAPSED_SLOT stands in for the reading when the elapsed label's text is turned into a pattern.
        const val RUNNING_TOOL_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, then reply " +
                "with exactly: pyryran. Command: python3 -c \"print(950)\""
        const val HELD_TOOL_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, and wait " +
                "for it to finish, then reply with exactly: pyryran. Command: python3 -c \"import time; time.sleep(10); print(950)\""
        const val ELAPSED_TOOL_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, and wait " +
                "for it to finish, then reply with exactly: pyryran. Command: python3 -c \"import time; time.sleep(45)\""
        const val ELAPSED_SLOT = "\u0000"
        const val DROP_REPLY = "pyrydropped"
        const val PEER_QUEUED_PROMPT = "Reply with exactly: pyrypeerdropped"
        const val PEER_QUEUED_REPLY = "pyrypeerdropped"
        const val QUEUE_CHAT_NAME_PREFIX = "e2e849-"

        // The peer's turn while the phone is offline (#850). The reply is matched as exactly the token in a
        // bubble, which the prompt's own text is not.
        const val OFFLINE_PROMPT = "Reply with exactly: pyryoffline"
        const val OFFLINE_REPLY = "pyryoffline"
        const val OFFLINE_CHAT_NAME_PREFIX = "e2e850-"

        /** #1581: the two chats of the off-screen reply scenario, as "e2e1581-a-<ms>" and "e2e1581-b-<ms>". */
        const val OFFSCREEN_CHAT_NAME_PREFIX = "e2e1581-"

        /** #1410: the chat the peer runs a turn in while the phone is offline. */
        const val CONTEXT_ASK_CHAT_NAME_PREFIX = "e2e1410-"

        // #965 stop scenario. The command waits on an event nothing sets, so only the phone's Stop (or, far
        // outside the test's step, claude's own Bash timeout) ends it; no time value is involved. It is a
        // `python3` command, so it needs permission, which the peer grants — see WAIT_PROMPT for why it is not
        // a `sleep`. STOP_HOLD_REPLY is a token no other prompt asks for; neither text contains "ping".
        const val STOP_HOLD_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, and without " +
                "a timeout, then reply with exactly: pyryheld. Command: python3 -c \"import threading; threading.Event().wait()\""
        const val STOP_HOLD_REPLY = "pyryheld"

        // Pairing-flow production strings (hardcoded in the composables, no resources). PASTE_CODE_LINK is
        // the common tail of all three scanner states' paste links — "Trouble scanning? Paste the pairing
        // code instead", "Paste the pairing code instead", "Paste code instead" — matched as a substring so
        // the camera state the emulator lands in does not matter, and only with a click action: the camera-error
        // and denied states draw a plain message ending in the same words above their button, and tapping that
        // message navigates nowhere. PAIR_BUTTON is matched exactly and with a
        // click action, apart from the "Pairing" title and the "Pairing code" label.
        const val PASTE_CODE_LINK = "code instead"
        const val HOST_NAME_FIELD = "Host name"
        const val PAIR_CODE_FIELD = "Pairing code"
        const val PAIR_BUTTON = "Pair"
        const val CONFIRM_PAIRING = "Confirm pairing"

        // Host B's display name, typed on the pair-code screen. Shares no substring with the seeded
        // "e2e847-a-" / "e2e847-b-" conversation names or RENAMED_NAME_PREFIX, so no exact match can
        // confuse a host row with a conversation row.
        const val HOST_B_NAME = "Second e2e host"

        // Runtime-unique rename target for host A's seeded conversation, distinct from #537's prefix.
        const val RENAMED_NAME_PREFIX = "e2e847-renamed-"

        // #1085 host management. The run-unique name host B is renamed to, sharing no substring with
        // HOST_B_NAME or another scenario's names, and the Edit host shell's hardcoded submit label.
        const val HOST_RENAME_PREFIX = "e2e1085-host-"
        const val EDIT_HOST_OK = "OK"

        // #1086's remaining two-host Archive proof uses a run-unique chat name.
        const val TWO_HOST_ARCHIVE_CHAT_PREFIX = "e2e1086-chat-"

        // Retained for ignored default-workspace scenarios and their helpers.
        const val DEFAULT_WORKSPACE_ROW = "Default workspace"

        // #1088 channel create, edit and archive. The anchor's folder is "e2e1088-<ms>" and the channel is
        // "e2e1088-<ms>-a", renamed "e2e1088-<ms>-b". Neither prompt changes what the ping replies.
        const val CHANNEL_E2E_PREFIX = "e2e1088-"
        const val CHANNEL_PROMPT_FIRST = "e2e1088 first prompt: answer briefly."
        const val CHANNEL_PROMPT_SECOND = "e2e1088 second prompt: answer briefly and plainly."

        // How often #1088 re-reads the channel's prompt status while the post-edit session starts.
        const val PROMPT_STATUS_POLL_MS = 1_000L

        // #545 settings scenarios. Run-unique names for the chats and channel each method prepares on the host,
        // none containing "ping" or another scenario's prefix.
        const val MODEL_X_NAME_PREFIX = "e2e545-model-x-"
        const val MODEL_Y_NAME_PREFIX = "e2e545-model-y-"
        const val INHERITED_EFFORT_NAME_PREFIX = "e2e545-inherited-"
        const val CHOSEN_EFFORT_NAME_PREFIX = "e2e545-chosen-"
        const val RECALL_PRIMING_NAME_PREFIX = "e2e545-priming-"
        const val RECALL_CHAT_NAME_PREFIX = "e2e545-recall-chat-"
        const val RECALL_CHANNEL_NAME_PREFIX = "e2e545-recall-channel-"
        const val RECALL_EXPLICIT_NAME_PREFIX = "e2e545-explicit-"

        // #687 operator-bypass scenario. The arguments scripts/e2e-emulator.sh passes once its dedicated
        // daemon is up, or ARG_BYPASS_UNMET naming the prerequisite it lacked. The pair code and the peer
        // token carry pairing tokens: never log them. The witness token authorizes nothing.
        const val ARG_BYPASS_UNMET = "bypassUnmet"
        const val ARG_BYPASS_TOKEN_FILE = "bypassTokenFile"
        const val ARG_BYPASS_TOKEN = "bypassToken"

        // What each of the script's BYPASS_UNMET codes means.
        val BYPASS_UNMET_REASONS =
            mapOf(
                "no_credential" to "no Claude credential; set CLAUDE_CODE_OAUTH_TOKEN (the live gate's route) or ANTHROPIC_API_KEY",
                "claude_json_unreadable" to
                    "CLAUDE_CODE_OAUTH_TOKEN is set but ~/.claude.json, copied into the isolated HOME, is unreadable",
                "revision_unavailable" to "the daemon revision is unavailable, so it cannot be shown to contain pyrycode 475c406a",
                "revision_unverifiable" to
                    "the daemon revision cannot be checked for pyrycode 475c406a; set PYRYCODE_SRC to a checkout holding both",
                "revision_lacks_475c406a" to "the daemon revision does not contain pyrycode 475c406a",
                "claude_missing" to "claude is not on PATH",
                "instance_name" to "the dedicated instance name is not a test instance name",
                "isolated_home" to "the isolated HOME, its config or the token file could not be written",
                "daemon_not_ready" to "the dedicated daemon did not answer `pyry status` within 15 s",
                "pairing_fixture" to "the scenario-entry pairing fixture did not start",
            )

        // #966 answer daemon. The arguments scripts/e2e-emulator.sh passes once it is up, or ARG_ANSWER_UNMET
        // naming the prerequisite it lacked. The pair code and the peer token carry pairing tokens, and the
        // phone's code is privileged: never log them.
        const val ARG_ANSWER_UNMET = "answerUnmet"
        const val ARG_ANSWER_SERVER_ID = "answerServerId"
        const val ARG_ANSWER_PAIR_CODE = "answerPairCode"
        const val ARG_ANSWER_PEER_TOKEN = "answerPeerToken"
        const val ARG_ANSWER_SERVER_STATIC_PUBLIC_KEY = "answerServerStaticPublicKey"

        // What each of the script's ANSWER_UNMET codes means.
        val ANSWER_UNMET_REASONS =
            mapOf(
                "no_credential" to "no Claude credential; set CLAUDE_CODE_OAUTH_TOKEN (the live gate's route) or ANTHROPIC_API_KEY",
                "claude_json_unreadable" to
                    "CLAUDE_CODE_OAUTH_TOKEN is set but ~/.claude.json, copied into the isolated HOME, is unreadable",
                "claude_missing" to "claude is not on PATH",
                "instance_name" to "the answer daemon's instance name is not a test instance name",
                "isolated_home" to "the isolated HOME or its config could not be written",
                "daemon_not_ready" to "the answer daemon did not answer `pyry status` within 15 s",
                "pairing" to "the phone's --allow-remote-permissions pairing could not be minted",
                "peer_pairing" to "the peer's --allow-remote-permissions pairing could not be minted",
            )

        // The answer host's display name and its chats' run-unique prefix: neither contains "ping".
        const val ANSWER_HOST_NAME = "Answer e2e host"
        const val ANSWER_CHAT_NAME_PREFIX = "e2e966-"

        // #1090: the attention-dot scenario's run-unique chat prefix on the answer host; it does not contain "ping".
        const val ATTENTION_CHAT_NAME_PREFIX = "e2e1090-"

        // #1016: the attachment exchange. Fixture names are plain ASCII, which the daemon stores unchanged, and
        // run-unique, so MediaStore never renames one. The document is about 100 KB: three 45000-byte chunks.
        const val ATTACH_CHAT_NAME_PREFIX = "e2e1016-"

        // #1352: ThreadScreen's message region, where the reader's pull toward older messages starts.
        const val THREAD_MESSAGE_REGION_TEST_TAG = "thread-message-region"
        const val ATTACH_OTHER_NAME_PREFIX = "e2e1016-other-"
        const val ATTACH_FILE_PREFIX = "e2e1016-"
        const val OFFER_CONTENT_PREFIX = "pyrycode-mobile-offer-"
        const val TEXT_MIME = "text/plain"
        const val DOCUMENT_BYTES = 100_000
        const val FIXTURE_COLOR = 0xFF2A6FDB.toInt()

        // A diagnostic archive can carry a multi-megabyte recording in 45000-byte chunks.
        const val DEBUG_BUNDLE_TIMEOUT_MS = 180_000L

        // The daemon's fixed archive members and manifest keys (pyrycode `debugbundle.Assemble`, `Manifest`).
        const val BUNDLE_MANIFEST = "manifest.json"
        const val BUNDLE_LOGS = "logs.txt"
        const val BUNDLE_RECORDING = "recording.cast"
        val BUNDLE_MEMBERS = setOf(BUNDLE_MANIFEST, BUNDLE_LOGS, BUNDLE_RECORDING)
        const val MANIFEST_RECORDING_PRESENT = "recording_present"
        const val MANIFEST_LOG_BYTES = "log_bytes"
        const val MANIFEST_RECORDING_BYTES = "recording_bytes"

        // The ustar header fields the in-memory walk reads.
        const val TAR_BLOCK = 512
        const val TAR_NAME_BYTES = 100
        const val TAR_SIZE_OFFSET = 124
        const val TAR_SIZE_BYTES = 12
        const val TAR_TYPE_OFFSET = 156

        // #1050: the live-note link. Run-unique names; the markers are what the note's heading renders as.
        const val NOTE_CHAT_NAME_PREFIX = "e2e1050-"
        const val NOTE_FILE_PREFIX = "e2e1050-note-"
        const val NOTE_LINK_PREFIX = "e2e1050-open-"
        const val NOTE_MARKER_PREFIX = "pyrycode-mobile-note-"

        // #1017: the interrupted transfers. The cut follows chunk 1 of the three-chunk document, so one chunk is
        // never sent. The two log prefixes are RelayLog's own event names, which carry ids and counts only.
        const val INTERRUPT_CHAT_NAME_PREFIX = "e2e1017-"
        const val INTERRUPT_FILE_PREFIX = "e2e1017-"
        const val CUT_AFTER_CHUNK = 1
        const val CHUNK_SENT_EVENT = "event=attachment_chunk "
        const val RETRIEVAL_REQUEST_EVENT = "event=attachment_request "

        // A `python3` command, so it needs permission (see WAIT_PROMPT). The token is its output, which no
        // prompt contains; claude could still compute it, so the tests prove the run by a successful Bash
        // tool_result (assertBashRan) and the token only shows the reply reports it.
        const val ANSWER_PERMISSION_PROMPT =
            "Run this exact shell command with your tools in the foreground, not in the background, then reply " +
                "with exactly the number it printed and nothing else. Command: python3 -c \"print(966 * 7)\""
        const val ANSWER_PERMISSION_TOKEN = "6762"

        // One clarification question with two labels; the phone picks QUESTION_PICK, the peer QUESTION_OTHER.
        const val QUESTION_PICK = "pyrymagenta"
        const val QUESTION_OTHER = "pyrycyan"
        const val QUESTION_PROMPT =
            "Use your AskUserQuestion tool exactly once to ask me one single-choice question with exactly two " +
                "options, labelled $QUESTION_OTHER and $QUESTION_PICK. Do not use any other tool. After I answer, " +
                "reply with exactly the label I chose and nothing else."

        // Wire sentinels the #966 checks compare: the allow option id, and a dismissal's source and outcome.
        const val PERMISSION_CLASS = "permission"
        const val ALLOW_ONCE = "allow_once"
        const val REMOTE_SOURCE = "remote"
        const val ANSWERED = "answered"

        // How long the other thread gets to draw a prompt that is not its own before the check that it did not.
        const val SCOPE_SETTLE_MS = 3_000L

        // #967: the reconnect and background-task scenarios' run-unique chat prefixes; none contains "ping".
        const val RECONNECT_FOOTER_NAME_PREFIX = "e2e967-footer-"
        const val RECONNECT_COMMANDS_NAME_PREFIX = "e2e967-commands-"
        const val COMPACT_ATTACH_NAME_PREFIX = "e2e1460-compact-"
        const val BACKGROUND_NAME_PREFIX = "e2e967-background-"

        // #955: the push scenarios' chat names, and their waits. The daemon sends a device no second wake
        // within 30 s of the last (pyrycode cmd/pyry/push_wake.go, pushWakeWindow); the margin covers clock
        // skew between the phone and the daemon. The first FCM token can take a while on a fresh image.
        const val PUSH_TURN_NAME_PREFIX = "e2e955-turn-"
        const val PUSH_PROMPT_NAME_PREFIX = "e2e955-prompt-"
        const val PUSH_WAKE_COALESCE_MS = 32_000L
        const val PUSH_TOKEN_TIMEOUT_MS = 60_000L
        const val PUSH_TIMEOUT_MS = 60_000L

        // After a reconnect, the time for the daemon's re-shown `modal_shown` to reach the phone and the
        // notifier. Nothing observable marks its arrival: the notifier's ledger drops the re-emitted alert.
        const val RECONNECT_SETTLE_MS = 5_000L
        const val POLL_MS = 250L

        // The published row value of the inherited-default model (#972), which the model change skips.
        const val INHERITED_MODEL_VALUE = "default"

        // An available accessible percentage, including the warning and high states (#1660).
        val CONTEXT_REPORTED = Regex("Context usage(?: warning,| high,)? \\d+%")

        // How many of the published commands the suggestions must list after the reconnect.
        const val SLASH_ROWS_CHECKED = 3

        // The compaction divider's client-owned label (#874): it starts with the first and, for a manual
        // compaction, ends with the second.
        const val COMPACTION_DIVIDER = "Conversation compacted"
        const val COMPACTION_BY_YOU = " by you"
        const val COMPACTION_FAILED = "Compaction failed"

        // A `python3` command, so it needs permission (see WAIT_PROMPT) and no `sleep` refusal applies, run in
        // the background so it outlives the turn. Forty seconds is long enough to open the menu while it runs.
        const val BACKGROUND_PROMPT =
            "Run this exact shell command with your tools in the background (run_in_background), then stop " +
                "without commentary: python3 -c \"import time; time.sleep(40)\""
        const val BACKGROUND_FINISH_TIMEOUT_MS = 180_000L

        // #1076: the progress scenario's run-unique chat prefix (no "ping", no other scenario's prefix), and a
        // foreground subagent held open by permission-free Read calls on missing files in the chat's working
        // directory, taken one per message (the daemon's claude has no Glob tool). Its first progress frame
        // needs two tool calls; the rest keep the task running while the phone opens the panel. The timeout
        // covers the subagent's start and those two calls.
        const val BACKGROUND_PROGRESS_NAME_PREFIX = "e2e1076-progress-"
        const val BACKGROUND_PROGRESS_PROMPT =
            "Use your Agent tool once to start one general-purpose subagent (not in the background), wait for it, " +
                "then reply with exactly: done. Use no other tool yourself. Give the subagent exactly these " +
                "instructions: \"Use the Read tool twenty times, one call per message and never in parallel, waiting " +
                "for each result before the next call. Call n reads the file e2e1076-n.txt in the current working " +
                "directory, for n from 1 to 20. These files do not exist, so every call reports a missing file; that " +
                "is expected, so do not stop, retry, or investigate, just make the next call. Do not use any other " +
                "tool. When all twenty calls are done, reply with exactly: done.\""
        const val BACKGROUND_PROGRESS_TIMEOUT_MS = 180_000L

        // How much of a recorded progress description the card must show. The panel filters control characters
        // and cuts long text, so a short prefix before any control character is what reliably survives.
        const val ACTIVITY_PREFIX_CHARS = 16

        // The dedicated host's display name and its chat's run-unique name: neither contains "ping" or
        // another scenario's prefix.
        const val BYPASS_HOST_NAME = "Bypass e2e host"
        const val BYPASS_CHAT_NAME_PREFIX = "e2e687-"

        // pyrycode#2510's outside-workspace Read, with the path filled in. The token is in the file only.
        const val READ_PROMPT_TEMPLATE =
            "Use the Read tool once to read the file at %s, then reply with its exact contents and nothing else. " +
                "Do not use any other tool. If the read is denied or fails, do not retry it and reply with the single word blocked."
        val READ_TOOL_WORD = Regex("""\bRead\b""")

        // #977: the word the template asks for on a failed Read, and how long the phone may trail the
        // peer's turn_end before the missing token is diagnosed.
        const val BLOCKED_REPLY = "blocked"
        const val PHONE_TRAIL_MS = 15_000L

        // How long a fresh reading may take to report a mode, and how often it is re-asked.
        const val PERMISSION_READING_TIMEOUT_MS = 30_000L
        const val PERMISSION_POLL_MS = 500L

        // A model row whose `truncated_fields` names any of these cannot be used: its value would not be
        // accepted, its levels would be incomplete, or its label would be cut.
        val CUT_FIELDS_IN_USE = setOf("value", "effort_levels", "display_name")

        // The pair-code screen waits up to 30 s for the new host to connect before it returns to the list.
        const val PAIR_TIMEOUT_MS = 60_000L

        const val LIST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val THREAD_TIMEOUT_MS = 30_000L

        // How long a failed one-shot waits for the host's redial (#1029): the supervisor's first three
        // backoffs are at most 1.2 + 2.4 + 4.8 s, plus a dial.
        const val REDIAL_WAIT_MS = 10_000L

        // How often the #850 cut re-reads the thread cache while it waits for the settled reply.
        const val CACHE_POLL_MS = 200L

        // Generous: a real claude turn over the relay can take many seconds end to end.
        const val REPLY_TIMEOUT_MS = 90_000L

        // #1480: how long Claude may take to ask for a tool after the send; one upstream response took 5.5 minutes.
        const val UPSTREAM_PERMISSION_TIMEOUT_MS = 360_000L

        // #849: two real claude turns back to back, the allowed wait turn and the drained ping.
        const val WAIT_TURN_TIMEOUT_MS = 240_000L
    }
}
