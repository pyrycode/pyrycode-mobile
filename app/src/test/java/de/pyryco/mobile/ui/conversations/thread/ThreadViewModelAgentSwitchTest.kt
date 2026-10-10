package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SessionSettingsPayloadDto
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HostReadings
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.data.repository.SwitchAgentCall
import de.pyryco.mobile.data.repository.SwitchAgentFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelAgentSwitchTest {
    private val previousSink = RelayLog.sink

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = previousSink
    }

    @Test fun ownAgentPickUsesSettings() =
        runTest {
            for (agent in ConversationAgent.entries) {
                val repo = Repo(agent)
                val (vm, _) = collect(repo)
                val model = if (agent == ConversationAgent.Claude) "haiku" else "gpt-6-sol"
                vm.onModelSelected(model)
                runCurrent()
                assertEquals(listOf(Triple("s1", model, null)), repo.settingsWrites)
                assertTrue(repo.switches.isEmpty())
                assertNull(vm.state.value.runConfig.agentSwitch)
            }
        }

    @Test fun confirmationDismissSendsNothing() =
        runTest {
            for (agent in ConversationAgent.entries) {
                val repo = Repo(agent)
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                runCurrent()
                val confirmation = vm.state.value.runConfig.agentSwitch
                assertEquals(agent, confirmation?.source)
                assertEquals(repo.target, confirmation?.choice?.value)
                assertFalse(confirmation?.sending ?: true)
                assertEquals(repo.original, vm.state.value.runConfig.selectedModel)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchDismiss)
                runCurrent()
                assertNull(vm.state.value.runConfig.agentSwitch)
                assertTrue(repo.switches.isEmpty())
                assertTrue(repo.settingsWrites.isEmpty())
            }
        }

    @Test fun supportedEffortAndRepeatedConfirmSendOnce() =
        runTest {
            for (agent in ConversationAgent.entries) {
                val repo = Repo(agent, effort = "high")
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                vm.onModelSelected("haiku")
                vm.onEffortSelected("low")
                vm.onPermissionModeSelected("plan")
                vm.onOverflowEvent(ThreadEvent.AgentSwitchDismiss)
                runCurrent()
                assertEquals(listOf(SwitchAgentCall(CONV, repo.other, repo.target, "high")), repo.switches)
                assertTrue(repo.settingsWrites.isEmpty())
                assertTrue(vm.state.value.runConfig.pending)
                assertEquals(repo.targetLabel, vm.state.value.runConfig.modelLabel)
                assertTrue(
                    vm.state.value.runConfig.agentSwitch
                        ?.sending == true,
                )
            }
        }

    @Test fun unsupportedAndEmptyEffortAreOmitted() =
        runTest {
            for ((agent, effort) in ConversationAgent.entries.flatMap { agent -> listOf("ultra", "").map { agent to it } }) {
                val repo = Repo(agent, effort = effort)
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                runCurrent()
                assertNull(repo.switches.single().effort)
            }
        }

    @Test fun progressAndStaleSettingsDoNotSettleSwitch() =
        runTest {
            val repo = Repo()
            val (vm, _) = collect(repo)
            vm.onModelSelected(repo.target)
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            for (progress in listOf(
                ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending),
                ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written),
                null,
            )) {
                repo.reset.value = progress
                repo.settings.value = null
                runCurrent()
                repo.settings.value = reading("s1", repo.original, "high")
                runCurrent()
                assertTrue(vm.state.value.runConfig.pending)
                assertEquals(repo.targetLabel, vm.state.value.runConfig.modelLabel)
            }
            // Even the conversation changing cannot settle an operation whose repository result is outstanding.
            repo.binding.value = repo.other to "s2"
            runCurrent()
            assertTrue(vm.state.value.runConfig.pending)
            assertTrue(repo.switches.size == 1)
        }

    @Test fun failuresRestoreConfirmedChoice() =
        runTest {
            for (category in SwitchAgentFailure.Category.entries) {
                val repo = Repo()
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                repo.result.complete(Result.failure(SwitchAgentFailure(category, retryable = true)))
                runCurrent()
                val config = vm.state.value.runConfig
                assertNull(config.agentSwitch)
                assertFalse(config.pending)
                assertEquals(repo.original, config.selectedModel)
                assertTrue(config.agentSwitchFailed)
                assertEquals(1, repo.switches.size)
            }
        }

    @Test fun failureAfterLostSettingsRestoresConfirmedLabel() =
        runTest {
            val repo = Repo()
            val (vm, _) = collect(repo)
            vm.onModelSelected(repo.target)
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            repo.settings.value = null
            runCurrent()
            repo.result.complete(Result.failure(SwitchAgentFailure(SwitchAgentFailure.Category.Failed)))
            runCurrent()
            assertEquals("Claude Opus", vm.state.value.runConfig.modelLabel)
            assertEquals(
                "opus",
                vm.state.value.runConfig.confirmedSwitchChoice
                    ?.value,
            )
            assertTrue(
                vm.state.value.runConfig.effortChoices
                    .isEmpty(),
            )
            assertTrue(vm.state.value.runConfig.agentSwitchFailed)
        }

    @Test fun localExceptionIsSanitizedAndNeverRetried() =
        runTest {
            val repo = Repo()
            repo.throwOnSwitch = true
            val (vm, _) = collect(repo)
            vm.onModelSelected(repo.target)
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            runCurrent()
            assertTrue(vm.state.value.runConfig.agentSwitchFailed)
            assertNull(vm.state.value.runConfig.agentSwitch)
            assertEquals(repo.original, vm.state.value.runConfig.selectedModel)
            assertEquals(1, repo.switches.size)
        }

    @Test fun successUsesFreshSuccessorSettings() =
        runTest {
            for (agent in ConversationAgent.entries) {
                val repo = Repo(agent)
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                repo.binding.value = repo.other to "s2"
                repo.result.complete(Result.success(Unit))
                runCurrent()
                val stale = vm.state.value.runConfig
                assertNull(stale.agentSwitch)
                assertEquals(repo.other, stale.agent)
                assertNull(stale.selectedChoice)
                assertEquals("", stale.selectedEffort)
                assertEquals("", stale.permissionMode)
                assertTrue(stale.effortChoices.isEmpty())
                repo.settings.value = reading("s2", repo.target, "low")
                runCurrent()
                val fresh = vm.state.value.runConfig
                assertEquals(repo.targetLabel, fresh.modelLabel)
                assertEquals(repo.other, fresh.selectedMetadata?.agent)
                assertEquals(listOf("low", "high"), fresh.effortChoices.map { it.value })
                assertEquals("low", fresh.selectedEffort)
                assertEquals("default", fresh.permissionMode)
            }
        }

    @Test fun externalAgentChangeRejectsOldSessionReadings() =
        runTest {
            val repo = Repo()
            val (vm, _) = collect(repo)
            repo.binding.value = ConversationAgent.Codex to "s2"
            runCurrent()
            assertNull(vm.state.value.runConfig.selectedChoice)
            assertEquals("", vm.state.value.runConfig.selectedEffort)
            repo.settings.value = reading("s2", repo.target, "high")
            runCurrent()
            assertEquals(
                repo.target,
                vm.state.value.runConfig.selectedChoice
                    ?.value,
            )
            repo.binding.value = ConversationAgent.Claude to "s3"
            repo.settings.value = reading("s1", "opus", "max")
            runCurrent()
            assertNull(vm.state.value.runConfig.selectedChoice)
            assertEquals("", vm.state.value.runConfig.selectedEffort)
        }

    @Test fun realProjectionKeepsSuccessorSettingsWritableThroughUpdatesAndSnapshotsInBothDirections() =
        runTest {
            val pump = ProjectionPump()
            val remote =
                RemoteConversationRepository(
                    pump,
                    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                    negotiatedCapabilities = { setOf("interactive", "multi_agent") },
                )
            val menu = Repo().backing
            val repo =
                object : ConversationRepository by remote {
                    override fun observeModelMenu(conversationId: String) = menu.observeModelMenu(conversationId)
                }
            pump.push("conversation_updated", pump.row(ConversationAgent.Claude))
            val (vm, _) = collect(repo)
            assertEquals("opus", vm.state.value.runConfig.selectedModel)
            var previous = "s1"
            for ((target, successor) in listOf(ConversationAgent.Codex to "s2", ConversationAgent.Claude to "s3")) {
                val model = if (target == ConversationAgent.Claude) "opus" else "gpt-6-luna"
                vm.onModelSelected(model)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                runCurrent()
                assertTrue(vm.state.value.runConfig.pending)
                // Daemon order, with no consumer yield between transition and confirming update.
                pump.push(
                    "session_transition",
                    """{"conversation_id":"$CONV","previous_session_id":"$previous","new_session_id":"$successor","reason":"clear","occurred_at":"2026-10-10T10:00:00Z"}""",
                )
                pump.push("conversation_updated", pump.row(target))
                runCurrent()
                assertFalse(vm.state.value.runConfig.pending)
                assertFalse(vm.state.value.runConfig.settingsAvailable)
                assertTrue(
                    vm.state.value.runConfig.effortChoices
                        .isEmpty(),
                )
                pump.settings = reading(successor, model, "high")
                remote.refreshSessionSettings(CONV)
                runCurrent()
                for (update in listOf("conversation_updated", "conversations")) {
                    val row = pump.row(target, "renamed")
                    pump.push(update, if (update == "conversations") """{"conversations":[$row]}""" else row)
                    runCurrent()
                    val config = vm.state.value.runConfig
                    assertEquals(successor, config.sessionId)
                    assertEquals(target, config.selectedMetadata?.agent)
                    assertEquals(listOf("low", "high"), config.effortChoices.map { it.value })
                    assertEquals("default", config.permissionMode)
                    assertTrue(config.writable)
                }
                vm.onEffortSelected("low")
                runCurrent()
                val write =
                    pump.sent
                        .last { it.type == "set_session_settings" }
                        .payload.jsonObject
                assertEquals(successor, write["session_id"]?.jsonPrimitive?.content)
                assertEquals("low", write["effort"]?.jsonPrimitive?.content)
                previous = successor
            }
            assertEquals(2, pump.sent.count { it.type == "switch_agent" })
        }

    @Test fun authoritativeReadingsRetireSuccessAndFailureFallbacks() =
        runTest {
            for (success in listOf(true, false)) {
                val repo = Repo()
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                repo.settings.value = null
                if (success) repo.binding.value = repo.other to "s2"
                repo.result.complete(
                    if (success) Result.success(Unit) else Result.failure(SwitchAgentFailure(SwitchAgentFailure.Category.Failed)),
                )
                runCurrent()
                assertEquals(if (success) repo.targetLabel else "Claude Opus", vm.state.value.runConfig.modelLabel)
                val session = if (success) "s2" else "s1"
                repo.settings.value = reading(session, if (success) repo.target else repo.original, "high")
                runCurrent()
                val newer = if (success) "gpt-6-sol" else "haiku"
                repo.settings.value = reading(session, newer, "low")
                runCurrent()
                assertEquals(
                    newer,
                    vm.state.value.runConfig.selectedChoice
                        ?.value,
                )
                repo.settings.value = null
                runCurrent()
                assertNull(vm.state.value.runConfig.confirmedSwitchChoice)
                assertNull(vm.state.value.runConfig.selectedChoice)
                assertEquals(UNKNOWN_RUN_CONFIG_LABEL, vm.state.value.runConfig.modelLabel)
            }
        }

    @Test fun switchedAgentReconnectAcceptsOnlyFreshReplacementSettings() =
        runTest {
            val held = HostReadings()
            val menu = Repo().backing

            fun remote(pump: ProjectionPump): ConversationRepository {
                val live =
                    RemoteConversationRepository(
                        pump,
                        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                        negotiatedCapabilities = { setOf("interactive", "multi_agent") },
                        hostReadings = held,
                    )
                return object : ConversationRepository by live {
                    override fun observeModelMenu(conversationId: String) = menu.observeModelMenu(conversationId)
                }
            }
            val oldPump = ProjectionPump()
            val current = MutableStateFlow<ConversationRepository?>(remote(oldPump))
            val stable = StableConversationRepository(current, held)
            oldPump.push("conversation_updated", oldPump.row(ConversationAgent.Claude))
            val (vm, _) = collect(stable, current.map { it != null })
            vm.onModelSelected("gpt-6-luna")
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            oldPump.settings = reading("s2", "gpt-6-luna", "high")
            oldPump.transition("s1", "s2", ConversationAgent.Codex)
            runCurrent()
            assertEquals("s2", vm.state.value.runConfig.sessionId)
            current.value = null
            runCurrent()
            assertFalse(vm.state.value.runConfig.writable)
            val newPump =
                ProjectionPump().apply {
                    settings = reading("s3", "gpt-6-sol", "low")
                    answerSettings = false
                }
            current.value = remote(newPump)
            newPump.push("conversation_updated", newPump.row(ConversationAgent.Codex))
            runCurrent()
            assertFalse(vm.state.value.runConfig.settingsAvailable)
            assertTrue(
                vm.state.value.runConfig.effortChoices
                    .isEmpty(),
            )
            oldPump.settings = reading("s1", "opus", "high")
            oldPump.push("conversation_updated", oldPump.row(ConversationAgent.Claude))
            runCurrent()
            assertEquals(ConversationAgent.Codex, vm.state.value.runConfig.agent)
            newPump.replySettings(newPump.sent.last { it.type == "request_session_settings" })
            runCurrent()
            val config = vm.state.value.runConfig
            assertEquals("s3", config.sessionId)
            assertEquals("GPT-6 Sol", config.modelLabel)
            assertEquals(listOf("low", "high"), config.effortChoices.map { it.value })
            assertEquals("low", config.selectedEffort)
            assertEquals("default", config.permissionMode)
            assertTrue(config.writable)
            assertFalse(config.pending)
            newPump.answerSettings = true
            vm.onEffortSelected("high")
            runCurrent()
            val write =
                newPump.sent
                    .last { it.type == "set_session_settings" }
                    .payload.jsonObject
            assertEquals("s3", write["session_id"]?.jsonPrimitive?.content)
            assertEquals("high", write["effort"]?.jsonPrimitive?.content)
        }

    @Test fun acknowledgedModelWriteIsInvalidatedByExternalAgentReplacement() =
        runTest {
            val pump = ProjectionPump()
            val remote =
                RemoteConversationRepository(
                    pump,
                    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                    negotiatedCapabilities = { setOf("interactive", "multi_agent") },
                )
            val menu = Repo().backing
            val repo =
                object : ConversationRepository by remote {
                    override fun observeModelMenu(conversationId: String) = menu.observeModelMenu(conversationId)
                }
            pump.push("conversation_updated", pump.row(ConversationAgent.Claude))
            val (vm, _) = collect(repo)
            pump.answerSettings = false
            vm.onModelSelected("haiku")
            runCurrent()
            assertEquals("haiku", vm.state.value.runConfig.pendingModel)
            assertEquals(1, pump.sent.count { it.type == "set_session_settings" })
            pump.transition("s1", "s2", ConversationAgent.Codex)
            runCurrent()
            assertNull(vm.state.value.runConfig.pendingModel)
            assertFalse(vm.state.value.runConfig.settingsAvailable)
            pump.settings = reading("s2", "gpt-6-sol", "low")
            pump.replySettings(pump.sent.last { it.type == "request_session_settings" })
            runCurrent()
            val config = vm.state.value.runConfig
            assertEquals("gpt-6-sol", config.selectedModel)
            assertEquals(ConversationAgent.Codex, config.selectedChoice?.agent)
            assertFalse(config.pending)
            assertTrue(config.writable)
            assertEquals("low", config.selectedEffort)
            assertEquals("default", config.permissionMode)
            assertEquals(0, pump.sent.count { it.type == "switch_agent" })
        }

    @Test fun dropdownLabelsUseBoundedInertDisplayNamesForBothAgents() {
        for (agent in ConversationAgent.entries) {
            val row = row("opus", "Claude\nOpus" + "x".repeat(200), agent)
            assertEquals("ClaudeOpus" + "x".repeat(118), row.dropdownLabel(agent))
        }
    }

    @Test fun recollectionDoesNotResend() =
        runTest {
            val repo = Repo()
            val (vm, collector) = collect(repo)
            vm.onModelSelected(repo.target)
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            collector.cancel()
            runCurrent()
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(vm.state.value.runConfig.pending)
            assertEquals(1, repo.switches.size)
        }

    @Test fun viewModelClearCancelsSwitch() =
        runTest {
            val repo = Repo()
            val (vm, _) = collect(repo)
            val store = ViewModelStore()
            store.put("thread", vm)
            vm.onModelSelected(repo.target)
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            store.clear()
            runCurrent()
            assertTrue(repo.cancelled)
            assertEquals(1, repo.switches.size)
        }

    @Test fun unknownOrAmbiguousRowsCannotSwitch() =
        runTest {
            val repo = Repo()
            repo.backing.setModelMenu(
                CONV,
                ModelMenu(
                    listOf(
                        row("mystery", "Unknown", null),
                        row("same", "Claude", ConversationAgent.Claude),
                        row("same", "Codex", ConversationAgent.Codex),
                    ),
                    0,
                ),
            )
            val (vm, _) = collect(repo)
            for (value in listOf("mystery", "same")) {
                vm.onModelSelected(value)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            }
            runCurrent()
            assertTrue(repo.switches.isEmpty())
            assertTrue(repo.settingsWrites.isEmpty())
        }

    private fun TestScope.collect(
        repo: ConversationRepository,
        available: Flow<Boolean> = flowOf(true),
    ): Pair<ThreadViewModel, Job> {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                repositoryAvailable = available,
            )
        val job = backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to job
    }

    private class ProjectionPump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var settings = reading("s1", "opus", "high")
        var answerSettings = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            if (envelope.type == "request_session_settings" && answerSettings) {
                replySettings(envelope)
            } else if (envelope.type == "set_session_settings") {
                push("session_settings_updated", """{"session_id":"${settings.sessionId}"}""", envelope.id)
            }
            return true
        }

        fun replySettings(request: Envelope) {
            val s = settings
            val payload =
                MobileJson.encodeToJsonElement(
                    SessionSettingsPayloadDto(s.sessionId, s.model, s.effort, s.yolo, s.permissionMode, 0, 0),
                )
            check(channel.trySend(Envelope(900, "session_settings", "2026-10-10T10:00:00Z", payload, inReplyTo = request.id)).isSuccess)
        }

        fun transition(
            previous: String,
            successor: String,
            agent: ConversationAgent,
        ) {
            push(
                "session_transition",
                """{"conversation_id":"$CONV","previous_session_id":"$previous","new_session_id":"$successor","reason":"clear","occurred_at":"2026-10-10T10:00:00Z"}""",
            )
            push("conversation_updated", row(agent))
        }

        fun push(
            type: String,
            payload: String,
            replyTo: Long? = null,
        ) {
            check(
                channel
                    .trySend(
                        Envelope(900, type, "2026-10-10T10:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = replyTo),
                    ).isSuccess,
            )
        }

        fun row(
            agent: ConversationAgent,
            name: String = "channel",
        ) =
            """{"id":"$CONV","name":"$name","is_promoted":true,"is_archived":false,"cwd":"/p","last_message_ts":"2026-10-10T10:00:00Z","last_used_at":"2026-10-10T10:00:00Z","agent":"${agent.name.lowercase()}"}"""
    }

    private class Repo(
        agent: ConversationAgent = ConversationAgent.Claude,
        effort: String = "high",
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val other = if (agent == ConversationAgent.Claude) ConversationAgent.Codex else ConversationAgent.Claude
        val original = if (agent == ConversationAgent.Claude) "opus" else "gpt-6-luna"
        val target = if (other == ConversationAgent.Claude) "opus" else "gpt-6-luna"
        val targetLabel = if (other == ConversationAgent.Claude) "Claude Opus" else "GPT-6 Luna"
        val binding = MutableStateFlow(agent to "s1")
        val settings = MutableStateFlow<SessionSettings?>(reading("s1", original, effort))
        val reset = MutableStateFlow<ResetStatus?>(null)
        val result = CompletableDeferred<Result<Unit>>()
        val switches = mutableListOf<SwitchAgentCall>()
        val settingsWrites = mutableListOf<Triple<String, String?, String?>>()
        var throwOnSwitch = false
        var cancelled = false

        init {
            backing.setModelMenu(
                CONV,
                ModelMenu(
                    listOf(
                        row("opus", "Claude Opus", ConversationAgent.Claude),
                        row("gpt-6-luna", "GPT-6 Luna", ConversationAgent.Codex),
                        row("haiku", "Claude Haiku", ConversationAgent.Claude),
                        row("gpt-6-sol", "GPT-6 Sol", ConversationAgent.Codex),
                    ),
                    0,
                ),
            )
        }

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), binding) { list, binding ->
                list.map {
                    if (it.id ==
                        CONV
                    ) {
                        it.copy(agent = binding.first, currentSessionId = binding.second)
                    } else {
                        it
                    }
                }
            }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = settings

        override fun observeResetting(conversationId: String): Flow<ResetStatus?> = reset

        override suspend fun setSessionSettings(
            sessionId: String,
            model: String?,
            effort: String?,
            yolo: Boolean?,
            permissionMode: String?,
        ) {
            settingsWrites +=
                Triple(sessionId, model, effort)
        }

        override suspend fun switchAgent(
            conversationId: String,
            agent: ConversationAgent,
            model: String,
            effort: String?,
        ): Result<Unit> {
            switches += SwitchAgentCall(conversationId, agent, model, effort)
            if (throwOnSwitch) throw IllegalStateException("private daemon details")
            try {
                return result.await()
            } finally {
                cancelled = !result.isCompleted
            }
        }
    }

    private companion object {
        const val CONV = "seed-channel-personal"

        fun row(
            value: String,
            label: String,
            agent: ConversationAgent?,
        ) = ModelMenuRow(value, value, label, listOf("low", "high"), true, null, agent)

        fun reading(
            session: String,
            model: String,
            effort: String,
        ) = SessionSettings(
            sessionId = session,
            model = model,
            effort = effort,
            permissionMode = "default",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
            effectiveEffort = EffectiveEffort.Applied(effort),
        )
    }
}
