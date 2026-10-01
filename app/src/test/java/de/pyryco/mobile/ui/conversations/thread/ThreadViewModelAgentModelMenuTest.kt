package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1110: one merged `multi_agent` menu is the same for every conversation, and each conversation lists
 * only its own agent's rows — in the footer and the Status sheet alike, since both read
 * [ThreadRunConfig.choices].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelAgentModelMenuTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun claudeConversation_listsOnlyClaudeRows_andKeepsDroppedModels() =
        runTest {
            val config = runConfigFor(ConversationAgent.Claude)

            assertEquals(listOf("sonnet", "opus"), config.choices.map { it.value })
            assertEquals(5, config.droppedModels)
        }

    @Test
    fun inheritedClaudeSetting_selectsOnlyTheOrdinaryRowWithTheSameConcreteModel() =
        runTest {
            val menu =
                ModelMenu(
                    rows =
                        listOf(
                            row("default", ConversationAgent.Claude, resolvedModel = "claude-sonnet-5"),
                            row("sonnet", ConversationAgent.Claude, resolvedModel = "claude-sonnet-5"),
                            row("opus", ConversationAgent.Claude, resolvedModel = "claude-opus-5"),
                        ),
                    droppedModels = 0,
                )
            val config = runConfigFor(ConversationAgent.Claude, menu = menu, savedModel = "")

            assertEquals(listOf("sonnet", "opus"), config.choices.map { it.value })
            assertEquals("sonnet", config.selectedChoice?.value)
            assertEquals("Sonnet", config.modelLabel)
        }

    @Test
    fun inheritedResolutionDoesNotClaimUniquenessWhenAnotherMatchIsPastTheRenderCap() =
        runTest {
            val rows =
                listOf(row("sonnet", ConversationAgent.Claude, resolvedModel = "same-model")) +
                    (1..31).map { row("other-$it", ConversationAgent.Claude, resolvedModel = "other-$it") } +
                    listOf(
                        row("shadow", ConversationAgent.Claude, resolvedModel = "same-model"),
                        row("default", ConversationAgent.Claude, resolvedModel = "same-model"),
                    )
            val config = runConfigFor(ConversationAgent.Claude, ModelMenu(rows, 0), savedModel = "")

            assertEquals(1, config.hiddenChoices)
            assertEquals(listOf("shadow"), config.overflowChoices.map { it.value })
            assertEquals(null, config.selectedChoice)
            // #1308: nothing marked, so the label names the default resolution's family.
            assertEquals("Same", config.modelLabel)
        }

    @Test
    fun codexConversation_listsOnlyCodexRows_inDaemonOrder_withTheDaemonsNames() =
        runTest {
            val config = runConfigFor(ConversationAgent.Codex)

            assertEquals(listOf("gpt-6-luna", "gpt-6-sol"), config.choices.map { it.value })
            assertEquals(listOf("GPT-6 Luna", "GPT-6 Sol"), config.choices.map { it.label })
            assertEquals("gpt-6-luna-2026", config.choices.first().detail)
        }

    @Test
    fun claudeRowsUseRawValueFamiliesAndFallBackToPublishedNames() =
        runTest {
            val menu =
                ModelMenu(
                    rows =
                        listOf(
                            row("claude-fable-5[1m]", ConversationAgent.Claude, displayName = "Fable tier"),
                            row("5[1m]", ConversationAgent.Claude, displayName = "Numbered tier"),
                            row("gpt-6-sol", ConversationAgent.Codex, displayName = "Vendor Sol face"),
                        ),
                    droppedModels = 0,
                )

            assertEquals(listOf("Fable", "Numbered tier"), runConfigFor(ConversationAgent.Claude, menu).choices.map { it.label })
            assertEquals(listOf("Vendor Sol face"), runConfigFor(ConversationAgent.Codex, menu).choices.map { it.label })
        }

    @Test
    fun longClaudeFamilyIsBoundedWithoutChangingTheWriteValue() =
        runTest {
            val rawValue = "claude-" + "a".repeat(200)
            val menu = ModelMenu(listOf(row(rawValue, ConversationAgent.Claude)), 0)

            val choice = runConfigFor(ConversationAgent.Claude, menu).choices.single()
            assertEquals(rawValue, choice.value)
            assertEquals(128, choice.label.length)
            assertEquals("A" + "a".repeat(127), choice.label)
        }

    @Test
    fun codexConversation_leavesOutClaudesDroppedModels() =
        runTest {
            assertEquals(0, runConfigFor(ConversationAgent.Codex).droppedModels)
        }

    @Test
    fun codexConversation_withNoSavedModel_hasNoInheritedRow_andOffersNoEffort() =
        runTest {
            val config = runConfigFor(ConversationAgent.Codex, savedModel = "")

            assertEquals(UNAVAILABLE_MODEL_LABEL, config.modelLabel)
            assertEquals(null, config.selectedChoice)
            assertTrue(config.effortChoices.isEmpty())
        }

    @Test
    fun codexConversation_savedCodexModel_offersThatRowsLevels() =
        runTest {
            val config = runConfigFor(ConversationAgent.Codex, savedModel = "gpt-6-luna")

            assertEquals("GPT-6 Luna", config.modelLabel)
            assertEquals(listOf("low", "ultra"), config.effortChoices.map { it.value })
        }

    @Test
    fun hiddenChoices_countsTheFilteredList() =
        runTest {
            val manyClaude = (1..40).map { row("claude-$it", ConversationAgent.Claude) }
            val menu = ModelMenu(rows = manyClaude + MERGED.rows.filter { it.agent == ConversationAgent.Codex }, droppedModels = 0)

            val codex = runConfigFor(ConversationAgent.Codex, menu = menu)
            val claude = runConfigFor(ConversationAgent.Claude, menu = menu)

            assertEquals(0, codex.hiddenChoices)
            assertEquals(2, codex.choices.size)
            assertEquals(8, claude.hiddenChoices)
        }

    @Test
    fun untaggedMenu_inAClaudeConversation_isListedAsBefore() =
        runTest {
            val untagged =
                ModelMenu(
                    rows =
                        listOf(
                            ModelMenuRow("r1", "sonnet", "Sonnet", listOf("low"), false, null),
                            ModelMenuRow("r2", "opus", "Opus", listOf("high"), false, null),
                        ),
                    droppedModels = 2,
                )

            val config = runConfigFor(ConversationAgent.Claude, menu = untagged)

            assertEquals(listOf("sonnet", "opus"), config.choices.map { it.value })
            assertEquals(2, config.droppedModels)
        }

    @Test
    fun theConversationsAgentArriving_refiltersTheMenu() =
        runTest {
            val repo = AgentRepo(ConversationAgent.Claude)
            val vm = collectedVm(repo, MERGED, savedModel = "opus")
            assertEquals(
                listOf("sonnet", "opus"),
                vm.state.value.runConfig.choices
                    .map { it.value },
            )

            repo.agent.value = ConversationAgent.Codex
            runCurrent()

            assertEquals(
                listOf("gpt-6-luna", "gpt-6-sol"),
                vm.state.value.runConfig.choices
                    .map { it.value },
            )
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.runConfigFor(
        agent: ConversationAgent,
        menu: ModelMenu = MERGED,
        savedModel: String = "opus",
    ): ThreadRunConfig = collectedVm(AgentRepo(agent), menu, savedModel).state.value.runConfig

    private fun TestScope.collectedVm(
        repo: AgentRepo,
        menu: ModelMenu,
        savedModel: String,
    ): ThreadViewModel {
        repo.backing.setModelMenu(CONV, menu)
        repo.settings.value = reading(savedModel)
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun reading(model: String) =
        SessionSettings(
            sessionId = "sess-a",
            model = model,
            effort = "",
            effectiveEffort = EffectiveEffort.Unavailable,
            permissionMode = "",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
        )

    /** The fake repository, with this conversation run by [agent] and a settings reading on hand. */
    private class AgentRepo(
        agent: ConversationAgent,
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val agent = MutableStateFlow(agent)
        val settings = MutableStateFlow<SessionSettings?>(null)

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = settings

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), agent) { list, agent ->
                list.map { if (it.id == CONV) it.copy(agent = agent) else it }
            }
    }

    private companion object {
        const val CONV = "seed-channel-personal"

        fun row(
            value: String,
            agent: ConversationAgent?,
            displayName: String = value,
            resolvedModel: String = "",
            levels: List<String> = emptyList(),
        ) = ModelMenuRow(
            resolvedModel = resolvedModel,
            value = value,
            displayName = displayName,
            effortLevels = levels,
            supportsAutoMode = false,
            truncatedFields = null,
            agent = agent,
            family = value,
        )

        /** Claude's rows (with its `default`), then Codex's newest per family, then a row naming neither. */
        val MERGED =
            ModelMenu(
                rows =
                    listOf(
                        row("sonnet", ConversationAgent.Claude, levels = listOf("low", "high")),
                        row("opus", ConversationAgent.Claude, levels = listOf("high", "max")),
                        row("default", ConversationAgent.Claude, levels = listOf("medium", "xhigh")),
                        row(
                            "gpt-6-luna",
                            ConversationAgent.Codex,
                            displayName = "GPT-6 Luna",
                            resolvedModel = "gpt-6-luna-2026",
                            levels = listOf("low", "ultra"),
                        ),
                        row("gpt-6-sol", ConversationAgent.Codex, displayName = "GPT-6 Sol"),
                        row("mystery", null),
                    ),
                droppedModels = 5,
            )
    }
}
