package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.dropdownLabel
import de.pyryco.mobile.ui.conversations.thread.inert
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext

/** Three real turns. Only the model selections and their confirmations mutate agent settings. */
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
        ) {
            val prompt = "Reply with exactly $marker. No tools or other text."
            expected += Role.User to prompt
            expected += Role.Assistant to marker
            send(prompt)
            val messages = assertSwitchHistory(rule, repository(), channel.id, expected)
            val reply = messages.single { it.role == Role.Assistant && it.content.trim() == marker }
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
            Log.i("AgentSwitchE2E", "event=agent_switch_reply outcome=finalized ordinal=$occurrence")
        }

        turn("baseline2052", 1)
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
            turn(if (index == 0) "codex2052" else "claude2052", index + 2)
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
    val list = rule.onNode(hasScrollToNodeAction())
    // Adjacent authored rows are deliberately short enough to compare in the same viewport.
    for (pair in expected.zipWithNext()) {
        fun target(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
        list.performScrollToNode(target(pair.second.second))
        list.performScrollToNode(target(pair.first.second))
        rule.onNode(target(pair.first.second), useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(target(pair.second.second), useUnmergedTree = true).assertIsDisplayed()
        val first = rule.onNode(target(pair.first.second), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val next = rule.onNode(target(pair.second.second), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("authored message order changed in the thread", first.top < next.top)
    }
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
