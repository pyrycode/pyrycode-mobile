package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.ui.conversations.thread.dropdownLabel
import de.pyryco.mobile.ui.conversations.thread.inert
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import java.net.HttpURLConnection
import java.net.URI

/** Five real turns: three authored replies and two automatic outgoing hand-over turns. */
internal fun runAgentSwitchScenario(
    rule: ComposeTestRule,
    repository: () -> ConversationRepository,
    openChannel: (String) -> Unit,
    send: (String) -> Unit,
    ready: () -> Unit,
) {
    val args = InstrumentationRegistry.getArguments()
    val prerequisite = args.getString("codexPrerequisite")
    check(prerequisite == "") { "agent_switch_prerequisite=${switchPrerequisiteReason(prerequisite)}" }
    ready()
    val host = requireNotNull(args.getString(E2eTestApplication.ARG_SERVER_ID))
    val peer =
        SecondClientPeer(
            PairedServer(
                serverId = host,
                token = requireNotNull(args.getString("peerToken")),
                relayUrl = requireNotNull(args.getString(E2eTestApplication.ARG_RELAY_URL)),
                serverStaticPublicKey = requireNotNull(args.getString(E2eTestApplication.ARG_SERVER_STATIC_PUBLIC_KEY)),
            ),
        )
    var created: String? = null
    var failure: Throwable? = null
    try {
        val repo = repository()
        val name = "e2e2052-${System.currentTimeMillis()}"
        val channel = runBlocking { withTimeout(30_000) { repo.createChannel(name, null) } }
        created = channel.id
        check(channel.agent == ConversationAgent.Claude) { "agent_switch_prerequisite=baseline_agent" }
        val baseline = runBlocking { withTimeout(30_000) { repo.observeSessionSettings(channel.id).filterNotNull().first { !it.held } } }
        val rows = awaitSwitchRows(repo, channel.id)
        val predecessors = mutableSetOf(baseline.sessionId)
        val expected = mutableListOf<Pair<Role, String>>()
        runBlocking { peer.open(30_000) }
        openChannel(name)

        fun turn(
            marker: String,
            occurrence: Int,
            session: String,
            agent: ConversationAgent,
        ) {
            val prompt = "Reply with exactly $marker. No tools or other text."
            expected += Role.User to prompt
            send(prompt)
            val reply =
                runBlocking {
                    withTimeout(180_000) {
                        repository()
                            .observeMessages(channel.id)
                            .map { items ->
                                finalizedSwitchReply(items.filterIsInstance<ThreadItem.MessageItem>().map { it.message }, marker)
                            }.filterNotNull()
                            .first()
                    }
                }
            val turnId = reply.segment?.turnId ?: reply.ordinaryId
            check(!turnId.isNullOrEmpty()) { "agent_switch_reply_identity_missing" }
            // Switching can itself emit a hand-over turn_end; count alone cannot prove this authored reply.
            val completed =
                runBlocking {
                    withTimeout(180_000) {
                        var result: TurnEndPayloadDto? = null
                        while (result == null) {
                            result =
                                peer
                                    .recorded(channel.id)
                                    .filter { it.type == "turn_end" }
                                    .map {
                                        MobileJson.decodeFromJsonElement(TurnEndPayloadDto.serializer(), it.payload)
                                    }.singleOrNull { it.turnId == turnId }
                            if (result == null) delay(100)
                        }
                        result
                    }
                }
            check(!completed.isError && completed.stopReason == "end_turn") { "agent_switch_reply_not_successful" }
            runBlocking {
                withTimeout(30_000) {
                    while (!matchesSwitchTurnSession(args.getString("daemonFaultPort"), channel.id, turnId, session, agent)) delay(100)
                }
            }
            expected += Role.Assistant to reply.content.trim()
            assertSwitchHistory(rule, repository(), channel.id, expected)
            Log.i("AgentSwitchE2E", "event=agent_switch_reply outcome=finalized ordinal=$occurrence")
        }

        turn("baseline2052", 1, baseline.sessionId, ConversationAgent.Claude)
        for ((index, row) in rows.withIndex()) {
            selectSwitchModel(rule, row)
            val successor = awaitSwitchSuccessor(repository(), channel.id, predecessors, row)
            predecessors += successor.sessionId
            // The successor belongs to this same authenticated host connection. No compatibility/global facade.
            assertTrue(
                "host identity changed",
                GlobalContext
                    .get()
                    .get<RelayConnectionRegistry>()
                    .connectionFor(host)
                    ?.coordinator
                    ?.currentRepository
                    ?.value === repository(),
            )
            assertSwitchEfforts(rule, row)
            assertSwitchHistory(rule, repository(), channel.id, expected)
            turn(if (index == 0) "codex2052" else "claude2052", index + 2, successor.sessionId, requireNotNull(row.agent))
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        peer.close()
        created?.let { id ->
            try {
                runBlocking { withTimeout(30_000) { repository().delete(id) } }
                Log.i("AgentSwitchE2E", "event=agent_switch_fixture outcome=deleted")
            } catch (cleanup: Throwable) {
                val original = failure
                if (original != null) original.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}

private fun awaitSwitchRows(
    repository: ConversationRepository,
    id: String,
): List<ModelMenuRow> =
    try {
        runBlocking {
            withTimeout(30_000) {
                var result: List<ModelMenuRow>? = null
                while (result == null) {
                    val menu = repository.observeModelMenu(id).filterNotNull().first()
                    val codex = switchModelRow(menu, ConversationAgent.Codex)
                    val claude = switchModelRow(menu, ConversationAgent.Claude)
                    if (codex != null && claude != null) result = listOf(codex, claude) else delay(250)
                }
                result
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("agent_switch_prerequisite=model_rows_unavailable", timeout)
    }

private fun selectSwitchModel(
    rule: ComposeTestRule,
    row: ModelMenuRow,
) {
    rule.onNodeWithContentDescription("Expand status details").performClick()
    val option =
        hasText(row.dropdownLabel(requireNotNull(row.agent))) and isSelectable() and
            hasAnyAncestor(hasTestTag("run-config-Model")) and isEnabled()
    rule.waitUntil(30_000) { rule.onAllNodes(option).fetchSemanticsNodes().size == 1 }
    rule.onNode(option).performScrollTo().performClick()
    val agentName = if (row.agent == ConversationAgent.Codex) "Codex" else "Claude"
    rule.waitUntil(30_000) { rule.onAllNodes(hasText("Switch to $agentName?")).fetchSemanticsNodes().isNotEmpty() }
    rule.onNode(hasText("Switch") and hasClickAction() and isEnabled()).performClick()
}

private fun awaitSwitchSuccessor(
    repository: ConversationRepository,
    id: String,
    predecessors: Set<String>,
    row: ModelMenuRow,
): SessionSettings =
    runBlocking {
        withTimeout(120_000) {
            combine(repository.observeConversations(ConversationFilter.All), repository.observeSessionSettings(id)) { channels, settings ->
                channels.singleOrNull { it.id == id } to settings
            }.first { (channel, settings) ->
                channel != null && settings != null && isSwitchSuccessor(channel, settings, id, predecessors, row)
            }.second ?: error("agent_switch_successor_unavailable")
        }
    }

private fun assertSwitchEfforts(
    rule: ComposeTestRule,
    row: ModelMenuRow,
) {
    rule.onNodeWithContentDescription("Expand status details").performClick()
    val label = row.dropdownLabel(requireNotNull(row.agent))
    rule.awaitRunConfiguration("Model", label, 30_000)
    rule.onNode(hasText(label) and isSelectable() and hasAnyAncestor(hasTestTag("run-config-Model"))).assertIsSelected()
    val radios = isSelectable() and hasAnyAncestor(hasTestTag("run-config-Effort"))
    val advertised = row.effortLevels.map { it.inert().replaceFirstChar(Char::uppercaseChar) }
    rule.waitUntil(30_000) {
        rule.onAllNodesWithTag("run-config-Effort").fetchSemanticsNodes().isNotEmpty() &&
            rule.onAllNodes(radios).fetchSemanticsNodes().map { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString("") { it.text }
            } == advertised
    }
    // Even when empty, section presence above makes this assertion non-vacuous.
    assertEquals("effort option count", advertised.size, rule.onAllNodes(radios).fetchSemanticsNodes().size)
    rule.onNodeWithContentDescription("Close").performClick()
}

private fun assertSwitchHistory(
    rule: ComposeTestRule,
    repository: ConversationRepository,
    id: String,
    expected: List<Pair<Role, String>>,
): List<Message> {
    val messages =
        runBlocking {
            withTimeout(180_000) {
                repository
                    .observeMessages(id)
                    .first { items ->
                        hasOrderedSwitchMessages(items.filterIsInstance<ThreadItem.MessageItem>().map { it.message }, expected)
                    }.filterIsInstance<ThreadItem.MessageItem>()
                    .map { it.message }
            }
        }
    assertRenderedSwitchHistory(rule, messages, expected)
    return messages
}

private fun switchPrerequisiteReason(reason: String?): String =
    reason?.takeIf {
        it in
            setOf(
                "configuration_missing",
                "unsafe_home",
                "binary_unavailable",
                "version_incompatible",
                "auth_unavailable",
                "home_busy",
                "instance_home_exists",
            )
    }
        ?: "not_provisioned"

private fun matchesSwitchTurnSession(
    port: String?,
    channel: String,
    turn: String,
    session: String,
    agent: ConversationAgent,
): Boolean {
    val number = requireNotNull(port?.toIntOrNull()).also { require(it in 1..65535) }
    val connection = URI("http://10.0.2.2:$number/turn-provenance").toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body =
            JsonObject(
                mapOf(
                    "conversation" to JsonPrimitive(channel),
                    "turn" to JsonPrimitive(turn),
                    "session" to JsonPrimitive(session),
                    "agent" to JsonPrimitive(agent.name.lowercase()),
                ),
            )
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        check(connection.responseCode == 200) { "agent_switch_turn_provenance_unavailable" }
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        return MobileJson
            .parseToJsonElement(response)
            .jsonObject["matches"]
            ?.jsonPrimitive
            ?.booleanOrNull == true
    } finally {
        connection.disconnect()
    }
}
