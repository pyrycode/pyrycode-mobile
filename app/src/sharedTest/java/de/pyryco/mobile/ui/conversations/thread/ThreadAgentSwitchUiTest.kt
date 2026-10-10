package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.SwitchAgentCall
import de.pyryco.mobile.data.repository.SwitchAgentFailure
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The operator path, from Tune through the real ViewModel to a controllable repository. */
@RunWith(AndroidJUnit4::class)
class ThreadAgentSwitchUiTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var vm: ThreadViewModel
    private lateinit var repo: Repo

    @After fun clear() {
        rule.runOnIdle { store.clear() }
    }

    @Test fun claudeToCodexPendingLabelAndStatusOutrankReset() {
        show(ConversationAgent.Claude)
        pickTarget()
        rule.onNodeWithText("Switch to Codex?").assertIsDisplayed()
        rule.onNodeWithText("This channel moves from Claude to GPT-6 Luna on Codex.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("slower and costs more", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Full-bypass mode turns off.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Switch").performClick()
        rule.runOnIdle {
            assertEquals(listOf(SwitchAgentCall(CONV, ConversationAgent.Codex, "gpt-6-luna", "high")), repo.switches)
            repo.reset.value = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
        }
        rule.onNodeWithText("Switching to Codex: Claude is writing a hand-over note…").assertIsDisplayed()
        openConfig()
        model("GPT-6 Luna").assertIsSelected().assertIsNotEnabled()
        model("Claude Opus").assertIsNotEnabled()
        rule.onNodeWithText("Model · applying…").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle { repo.reset.value = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written) }
        rule.onNodeWithText("Switching to Codex…").assertIsDisplayed()
        rule.onNodeWithText("Restarting · handoff note saved").assertDoesNotExist()
        rule.runOnIdle {
            repo.reset.value = null
            repo.settings.value = null
        }
        rule.onNodeWithText("Switching to Codex…").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, repo.switches.size)
            assertTrue(repo.writes.isEmpty())
        }
    }

    @Test fun codexToClaudeUsesSupportedEffortAndFreshSuccessorReadings() {
        show(ConversationAgent.Codex)
        pickTarget()
        rule.onNodeWithText("Switch to Claude?").assertIsDisplayed()
        rule.onNodeWithText("This channel moves from Codex to Claude Opus on Claude.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Switch").performClick()
        rule.runOnIdle {
            assertEquals(listOf(SwitchAgentCall(CONV, ConversationAgent.Claude, "opus", "high")), repo.switches)
            repo.binding.value = ConversationAgent.Claude to "successor"
            repo.result.complete(Result.success(Unit))
        }
        openConfig()
        model("GPT-6 Luna").assertIsNotSelected()
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle { repo.settings.value = reading("successor", "opus", "max") }
        openConfig()
        model("Claude Opus").assertIsSelected()
        rule.onNode(hasText("Max") and isSelectable()).assertIsSelected()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Switching to Claude…").assertDoesNotExist()
    }

    @Test fun claudeToCodexSuccessReplacesEffortAndPermissionMetadata() {
        show(ConversationAgent.Claude)
        pickTarget()
        rule.onNodeWithText("Switch").performClick()
        rule.runOnIdle {
            repo.binding.value = ConversationAgent.Codex to "successor"
            repo.result.complete(Result.success(Unit))
            repo.settings.value = reading("successor", "gpt-6-luna", "low")
        }
        openConfig()
        model("GPT-6 Luna").assertIsSelected()
        rule.onNode(hasText("Low") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("Max") and isSelectable()).assertDoesNotExist()
        rule.onNodeWithText("Manual approval").performScrollTo().assertIsSelected()
    }

    @Test fun cancelCloseAndBackPreserveModelAndSendNothing() {
        show(ConversationAgent.Claude)
        repeat(3) { route ->
            pickTarget()
            rule.onNodeWithText("Switch to Codex?").assertIsDisplayed()
            when (route) {
                0 -> rule.onNodeWithText("Cancel").performClick()
                1 -> rule.onNodeWithContentDescription("Close").performClick()
                else -> {
                    rule.waitForIdle()
                    Espresso.pressBack()
                }
            }
            rule.onNodeWithText("Switch to Codex?").assertDoesNotExist()
            rule.runOnIdle {
                assertTrue(repo.switches.isEmpty())
                assertTrue(repo.writes.isEmpty())
                assertEquals("opus", vm.state.value.runConfig.selectedModel)
                assertEquals(ConversationAgent.Claude, vm.state.value.agent)
            }
        }
    }

    @Test fun ordinaryResetAndExternalAgentChangeKeepResetWording() {
        show(ConversationAgent.Claude)
        rule.runOnIdle { repo.reset.value = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending) }
        rule.onNodeWithText("Claude is writing a handoff note for the next session").assertIsDisplayed()
        rule.runOnIdle { repo.binding.value = ConversationAgent.Codex to "successor" }
        rule.onNodeWithText("Codex is writing a handoff note for the next session").assertIsDisplayed()
        rule.onNodeWithText("Switching to Codex…").assertDoesNotExist()
        rule.runOnIdle { assertTrue(repo.switches.isEmpty()) }
    }

    @Test fun confirmedRadioMarkIsScopedToAgentEvenWhenValuesCoincide() {
        show(ConversationAgent.Claude)
        rule.runOnIdle {
            repo.backing.setModelMenu(
                CONV,
                ModelMenu(
                    listOf(
                        row("opus", "Claude Opus", ConversationAgent.Claude, listOf("high")),
                        row("opus", "Codex shared value", ConversationAgent.Codex, listOf("low")),
                    ),
                    0,
                ),
            )
        }
        openConfig()
        model("Claude Opus").assertIsSelected()
        model("Codex shared value").assertIsNotSelected()
    }

    @Test fun ownAgentPickUsesSettingsWithoutConfirmation() {
        show(ConversationAgent.Claude)
        openConfig()
        model("Claude Haiku").performClick()
        rule.runOnIdle {
            assertEquals(listOf("haiku"), repo.writes)
            assertTrue(repo.switches.isEmpty())
        }
        rule.onNodeWithText("Switch", substring = false).assertDoesNotExist()
    }

    @Test fun refusalClearsPendingAndShowsStaticErrorWithoutRollbackClaim() {
        show(ConversationAgent.Claude)
        pickTarget()
        rule.onNodeWithText("Switch").performClick()
        rule.runOnIdle { repo.result.complete(Result.failure(SwitchAgentFailure(SwitchAgentFailure.Category.Failed))) }
        rule.onNodeWithText("Couldn’t switch agents. Hand-over or queued-message changes may already have occurred.").assertIsDisplayed()
        rule.onNodeWithText("Switching to Codex…").assertDoesNotExist()
        openConfig()
        model("Claude Opus").assertIsSelected()
        rule.runOnIdle {
            assertEquals(1, repo.switches.size)
            assertTrue(repo.writes.isEmpty())
        }
    }

    private fun openConfig() {
        rule.onNodeWithContentDescription("Expand status details").performClick()
    }

    private fun pickTarget() {
        openConfig()
        model(repo.targetLabel).performClick()
    }

    private fun model(label: String) = rule.onNode(hasText(label) and isSelectable())

    private fun show(agent: ConversationAgent) {
        repo = Repo(agent)
        rule.runOnIdle {
            vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to CONV)),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            store.put("thread", vm)
        }
        rule.setContent {
            val state by vm.state.collectAsState()
            val reset by repo.reset.collectAsState()
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onModelSelected = vm::onModelSelected,
                    onEffortSelected = vm::onEffortSelected,
                    onPermissionModeSelected = vm::onPermissionModeSelected,
                    onOverflowEvent = vm::onOverflowEvent,
                    resetting = reset,
                )
            }
        }
        rule.waitUntil { vm.state.value.runConfig.settingsAvailable }
    }

    private class Repo(
        agent: ConversationAgent,
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val targetLabel = if (agent == ConversationAgent.Claude) "GPT-6 Luna" else "Claude Opus"
        val binding = MutableStateFlow(agent to "original")
        val settings =
            MutableStateFlow<SessionSettings?>(
                reading(
                    "original",
                    if (agent ==
                        ConversationAgent.Claude
                    ) {
                        "opus"
                    } else {
                        "gpt-6-luna"
                    },
                    "high",
                ),
            )
        val reset = MutableStateFlow<ResetStatus?>(null)
        val result = CompletableDeferred<Result<Unit>>()
        val switches = mutableListOf<SwitchAgentCall>()
        val writes = mutableListOf<String?>()

        init {
            backing.setModelMenu(
                CONV,
                ModelMenu(
                    listOf(
                        row("opus", "Claude Opus", ConversationAgent.Claude, listOf("low", "high", "max")),
                        row("gpt-6-luna", "GPT-6 Luna", ConversationAgent.Codex, listOf("low", "high")),
                        row("haiku", "Claude Haiku", ConversationAgent.Claude, emptyList()),
                    ),
                    0,
                ),
            )
        }

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), binding) { list, binding ->
                list.map { if (it.id == CONV) it.copy(agent = binding.first, currentSessionId = binding.second) else it }
            }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = settings

        override suspend fun switchAgent(
            conversationId: String,
            agent: ConversationAgent,
            model: String,
            effort: String?,
        ): Result<Unit> {
            switches += SwitchAgentCall(conversationId, agent, model, effort)
            return result.await()
        }

        override suspend fun setSessionSettings(
            sessionId: String,
            model: String?,
            effort: String?,
            yolo: Boolean?,
            permissionMode: String?,
        ) {
            writes +=
                model
        }
    }

    private companion object {
        const val CONV = "seed-channel-personal"

        fun row(
            value: String,
            label: String,
            agent: ConversationAgent,
            efforts: List<String>,
        ) = ModelMenuRow(value, value, label, efforts, false, null, agent)

        fun reading(
            session: String,
            model: String,
            effort: String,
        ) = SessionSettings(
            sessionId = session,
            model = model,
            effort = effort,
            effectiveEffort = EffectiveEffort.Applied(effort),
            permissionMode = "default",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
        )
    }
}
