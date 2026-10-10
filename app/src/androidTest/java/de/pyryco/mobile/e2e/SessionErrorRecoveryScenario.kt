package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.MessagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.WireRole
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import java.net.HttpURLConnection
import java.net.URL

/** Same phone flow for rung 3 and rung 4; only the private daemon's recovery executable differs. */
internal class SessionErrorRecoveryScenario(
    private val compose: ComposeTestRule,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val port = requireNotNull(arguments.getString("sessionErrorPort")) { "session-error fixture missing (#1731)" }.toInt()
    private val authorization = requireNotNull(arguments.getString("sessionErrorAuthorization")) { "session-error authorization missing" }

    private var phase = "fixture_start"
    private var fixtureReady = false
    private var diagnosticServerId: String? = null

    fun run() {
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val registry = GlobalContext.get().get<RelayConnectionRegistry>()
        val precedingPairings = runBlocking { store.list() }
        val precedingHost = requireNotNull(precedingPairings.lastOrNull()) { "preceding harness host missing" }.record.serverId
        val precedingConnection =
            runBlocking {
                withTimeout(30_000) {
                    registry.selected.first { it != null && it === registry.connectionFor(precedingHost) }
                }
            }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.CAMERA,
        )
        for (arm in listOf("retained", "dropped")) {
            phase = "fixture_start"
            fixtureReady = false
            diagnosticServerId = null
            var diagnosed: AssertionError? = null
            try {
                sessionErrorCleanup(cleanup = { request(arm, "close") }) {
                    val fixture = request(arm, "start")
                    fixtureReady = true
                    val serverId = fixture.value("serverId")
                    diagnosticServerId = serverId
                    sessionErrorCleanup(cleanup = { runBlocking { store.remove(serverId) } }) {
                        // Pairing can save before its UI wait fails; ownership starts before pair().
                        try {
                            runCase(arm, fixture)
                        } catch (failure: Throwable) {
                            // Observe the owned host before finally removes its pairing/connection.
                            val diagnostic = sessionErrorDiagnosticFailure(arm, phase, fixtureReady, failure, ::diagnosticSnapshot)
                            diagnosed = diagnostic
                            throw diagnostic
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure === diagnosed) throw failure
                throw sessionErrorDiagnosticFailure(arm, phase, fixtureReady, failure, ::diagnosticSnapshot)
            }
        }
        // Regression: this Application/Koin graph is reused by subsequent instrumentation methods.
        assertEquals("fixture pairings survived teardown", precedingPairings, runBlocking { store.list() })
        runBlocking {
            withTimeout(30_000) {
                registry.selected.first { it === precedingConnection }
                GlobalContext
                    .get()
                    .get<ConnectionStateSource>()
                    .observe()
                    .first { it is ConnectionState.Connected }
            }
        }
        assertSame("preceding host connection was replaced", precedingConnection, registry.connectionFor(precedingHost))
    }

    private fun runCase(
        arm: String,
        fixture: JsonObject,
    ) {
        val conversation = fixture.value("conversationId")
        val held = fixture.value("heldMarker")
        val fresh = fixture.value("freshMarker")
        val heldPrompt = sessionErrorRecoveryPrompt(held)
        val freshPrompt = sessionErrorRecoveryPrompt(fresh)
        pair(arm, fixture.value("pairCode"), fixture.value("channel"))
        SecondClientPeer(
            PairedServer(
                serverId = fixture.value("serverId"),
                token = fixture.value("peerToken"),
                relayUrl = fixture.value("relayUrl"),
                serverStaticPublicKey = fixture.value("serverStaticPublicKey"),
            ),
        ).use { peer ->
            stage(arm, "observer_open")
            runBlocking { peer.open(30_000) }
            stage(arm, "channel_open")
            val channel = hasText(fixture.value("channel")) and hasTestTag(TREE_CHANNEL_ROW_TEST_TAG)
            await(channel)
            compose.onNode(channel).performScrollTo().performClick()
            await(hasSetTextAction())
            request(arm, "ready") // The child has closed stdin; its first exit remains gated.
            stage(arm, "held_send")
            send(heldPrompt)
            val queued = runBlocking { peer.awaitQueue(conversation, 20_000) { it.size == 1 && it.single().text == heldPrompt } }.single()
            assertTrue("phone send has no correlation identity", queued.messageId.isNotBlank())
            assertEquals("the closed-stdin child must not receive a turn", 0, deliveries(peer, conversation).size)
            assertTrue("crash signal preceded queued observation", errors(peer, conversation).isEmpty())
            val queuedRow =
                hasText(heldPrompt) and
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        context.getString(R.string.thread_queued_state_desc),
                    )
            request(arm, "exit")
            val expectedId: String
            if (arm == "retained") {
                stage(arm, "retained_status")
                awaitError(peer, conversation, "session.child_crashing")
                assertPill(CRASHING)
                compose.onNode(queuedRow).assertIsDisplayed()
                val stillQueued = runBlocking { peer.awaitQueue(conversation, 5_000) { it.size == 1 } }.single()
                assertEquals(queued.messageId, stillQueued.messageId)
                assertEquals(queued.queuedMsgId, stillQueued.queuedMsgId)
                assertEquals(0, deliveries(peer, conversation).size)
                assertTrue("retained backlog gave up", "session.blocked" !in errors(peer, conversation))
                expectedId = queued.messageId
                request(arm, "release")
            } else {
                stage(arm, "dropped_status")
                awaitError(peer, conversation, "session.blocked")
                runBlocking { peer.awaitQueue(conversation, 10_000) { it.isEmpty() } }
                assertPill(BLOCKED)
                compose.waitUntil(10_000) { nodes(queuedRow).isEmpty() }
                compose.onAllNodes(queuedRow).assertCountEquals(0)
                assertEquals("dropped turn was delivered", 0, deliveries(peer, conversation).size)
                // The 3s give-up can precede the crash notice; observe its pill as it arrives.
                awaitError(peer, conversation, "session.child_crashing")
                request(arm, "release")
                request(arm, "recovered") // Automatic respawn, before a fresh phone send.
                stage(arm, "fresh_send")
                send(freshPrompt)
                runBlocking {
                    peer.awaiting("fresh phone delivery", 120_000) {
                        while (deliveries(peer, conversation).isEmpty()) delay(25)
                    }
                }
                val freshDelivery = deliveries(peer, conversation).single()
                assertEquals(freshPrompt, freshDelivery.text)
                assertTrue("dropped identity silently replayed", freshDelivery.messageId != queued.messageId)
                expectedId = freshDelivery.messageId
            }

            stage(arm, "reply_complete")
            runBlocking {
                peer.awaiting("assistant reply followed by idle", 120_000) {
                    while (!completed(peer, conversation)) delay(25)
                }
                peer.awaitQueue(conversation, 10_000) { it.isEmpty() }
            }
            stage(arm, "reply_render")
            val reply = sessionErrorReplyMatcher()
            await(reply, unmerged = true)
            compose.onAllNodes(reply, useUnmergedTree = true).onFirst().assertIsDisplayed()
            compose.waitUntil(20_000) { nodes(hasText(CRASHING)).isEmpty() && nodes(hasText(BLOCKED)).isEmpty() }
            request(arm, "complete") // Independently counts actual prompts read by the completed child.
            stage(arm, "recovery_assertions")
            val delivered = deliveries(peer, conversation)
            assertEquals("one recovery must deliver exactly one user message", 1, delivered.size)
            assertEquals(expectedId, delivered.single().messageId)
            if (arm == "retained") {
                assertEquals(heldPrompt, delivered.single().text)
                assertTrue("retained message reached give-up", "session.blocked" !in errors(peer, conversation))
            } else {
                assertTrue("dropped prompt replayed", delivered.none { it.messageId == queued.messageId || it.text == heldPrompt })
            }
            assertLocalStatusAbsent()
        }
        stage(arm, "list_return")
        Espresso.pressBack()
        await(hasTestTag(CHANNEL_LIST_TEST_TAG))
    }

    private fun pair(
        arm: String,
        code: String,
        name: String,
    ) {
        stage(arm, "pair_entry")
        await(hasTestTag(CHANNEL_LIST_TEST_TAG))
        compose.onNode(hasContentDescription(context.getString(R.string.cd_pair_another_host))).performClick()
        val paste = hasText("code instead", substring = true) and hasClickAction()
        await(paste)
        compose.onAllNodes(paste).onFirst().performClick()
        stage(arm, "pair_code")
        await(hasSetTextAction() and hasText("Pairing code"))
        compose.onNode(hasSetTextAction() and hasText("Host name")).performTextInput(name)
        compose.onNode(hasSetTextAction() and hasText("Pairing code")).performTextInput(code)
        compose.onNode(hasText("Pair") and hasClickAction()).performClick()
        stage(arm, "pair_confirm")
        await(hasText("Confirm pairing"))
        stage(arm, "confirm_ready")
        compose.onNodeWithText("Confirm pairing").performClick()
        stage(arm, "pair_return")
        await(hasTestTag(CHANNEL_LIST_TEST_TAG))
    }

    private fun stage(
        arm: String,
        value: String,
    ) {
        phase = value
        Log.i("SessionErrorRecovery", "arm=$arm phase=$phase fixture_ready=$fixtureReady")
    }

    private fun diagnosticSnapshot(): String {
        val serverId = diagnosticServerId ?: return "snapshot=not_paired"
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val registry = GlobalContext.get().get<RelayConnectionRegistry>()
        val connection = registry.connectionFor(serverId)
        val saved = runBlocking { withTimeout(1_000) { store.loadById(serverId) } } != null
        val status = connection?.coordinator?.connectionStatus?.value
        val selected = connection != null && registry.selected.value === connection
        val unavailable = nodes(hasText("temporarily unavailable", substring = true)).isNotEmpty()
        val rejected = nodes(hasText("Pairing rejected", substring = true)).isNotEmpty()
        val saveFailed = nodes(hasText("Could not save pairing", substring = true)).isNotEmpty()
        val nameFailed = nodes(hasText("host name could not be saved", substring = true)).isNotEmpty()
        val updateRequired = nodes(hasText("This app is too old", substring = true)).isNotEmpty()
        val confirm = nodes(hasText("Confirm pairing")).isNotEmpty()
        return "saved=$saved connection=${connection != null} selected=$selected " +
            "relay=${status?.relay?.javaClass?.simpleName} session=${status?.pyrycode?.javaClass?.simpleName} " +
            "unavailable=$unavailable rejected=$rejected save_failed=$saveFailed name_failed=$nameFailed " +
            "update_required=$updateRequired confirm=$confirm"
    }

    private fun send(prompt: String) {
        compose.onNode(hasSetTextAction()).performTextInput(prompt)
        val send = hasContentDescription(context.getString(R.string.cd_send_message))
        await(send)
        compose.onNode(send).performClick()
    }

    private fun awaitError(
        peer: SecondClientPeer,
        conversation: String,
        code: String,
    ) = runBlocking {
        peer.awaiting("conversation session error $code", 40_000) {
            while (code !in errors(peer, conversation)) delay(25)
        }
    }

    private fun errors(
        peer: SecondClientPeer,
        conversation: String,
    ): List<String> = peer.recorded(conversation).filter { it.type == "session_error" }.mapNotNull { peer.field(it, "code") }

    private fun deliveries(
        peer: SecondClientPeer,
        conversation: String,
    ): List<MessagePayloadDto> =
        peer
            .recorded(conversation)
            .filter { it.type == "message" }
            .map { MobileJson.decodeFromJsonElement(MessagePayloadDto.serializer(), it.payload) }
            .filter { it.role == WireRole.User }

    private fun completed(
        peer: SecondClientPeer,
        conversation: String,
    ): Boolean {
        val frames = peer.recorded(conversation)
        val reply = frames.indexOfLast { it.type == "assistant_delta" && !peer.field(it, "text").isNullOrBlank() }
        return reply >= 0 && frames.drop(reply + 1).any { it.type == "turn_state" && peer.field(it, "state") == "idle" }
    }

    private fun assertPill(text: String) {
        await(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
        assertLocalStatusAbsent()
    }

    private fun assertLocalStatusAbsent() {
        for (id in listOf(R.string.thread_sending_label, R.string.thread_waiting_label, R.string.thread_waiting_label_codex)) {
            compose.onAllNodesWithText(context.getString(id)).assertCountEquals(0)
        }
    }

    private fun await(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ) {
        compose.waitUntil(40_000) { nodes(matcher, unmerged).isNotEmpty() }
    }

    private fun nodes(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ) = compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes()

    private fun JsonObject.value(key: String): String = requireNotNull(this[key]).jsonPrimitive.content

    private fun request(
        arm: String,
        action: String,
    ): JsonObject {
        if (action != "close") stage(arm, "fixture_$action")
        val connection = URL("http://10.0.2.2:$port/$arm/$action").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $authorization")
            connection.connectTimeout = 5_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            check(connection.responseCode == 200) { "session-error fixture $arm/$action failed; inspect retained control/daemon evidence" }
            return MobileJson.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CRASHING = "Claude keeps failing to start. Your message is waiting."
        const val BLOCKED = "Claude did not pick up the last message. It was not delivered."
    }
}
