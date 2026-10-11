package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class ThreadViewModelAgentSwitchReplacementTest {
    private val previousSink = RelayLog.sink

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = previousSink
    }

    @Test fun supersededClaudeToCodexSuccessSettles() = supersededSuccess(ConversationAgent.Claude)

    @Test fun supersededCodexToClaudeSuccessSettles() = supersededSuccess(ConversationAgent.Codex)

    private fun supersededSuccess(source: ConversationAgent) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val backing = Repo(source)
            val authoritative = MutableStateFlow(backing.observeConversations(ConversationFilter.All).first())
            val repo =
                object : ConversationRepository by backing {
                    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = authoritative
                }
            val (vm, _) = collect(repo)
            vm.onModelSelected(backing.target)
            runCurrent()
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            runCurrent()
            backing.settings.value = null
            authoritative.value = authoritative.value.map { it.copy(agent = backing.other, currentSessionId = "s2") }
            backing.result.complete(Result.success(Unit))
            // A newer authoritative switch-back is folded before the downstream observer resumes.
            authoritative.value = authoritative.value.map { it.copy(agent = source, currentSessionId = "s3") }
            runCurrent()
            assertEquals(source, vm.state.value.runConfig.agent)
            assertEquals(1, backing.switches.size)
            assertFalse("Repository succeeded, but conflation left switching pending", vm.state.value.runConfig.pending)
            assertNull(vm.state.value.runConfig.agentSwitch)
        }

    @Test fun realSupersededClaudeToCodexSuccessSettles() = realSupersededSuccess(ConversationAgent.Claude)

    @Test fun realSupersededCodexToClaudeSuccessSettles() = realSupersededSuccess(ConversationAgent.Codex)

    private fun realSupersededSuccess(source: ConversationAgent) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val backing = Repo(source)
            val pump = ProjectionPump().apply { settings = reading("s1", backing.original, "high") }
            val remote =
                RemoteConversationRepository(
                    pump,
                    CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler)),
                    negotiatedCapabilities = { setOf("interactive", "multi_agent") },
                )
            var completed = false
            val repo =
                object : ConversationRepository by remote {
                    override fun observeModelMenu(conversationId: String) = backing.observeModelMenu(conversationId)

                    override suspend fun switchAgent(
                        conversationId: String,
                        agent: ConversationAgent,
                        model: String,
                        effort: String?,
                    ): Result<Unit> {
                        val result = remote.switchAgent(conversationId, agent, model, effort)
                        completed = result.isSuccess
                        return result
                    }
                }
            pump.push("conversation_updated", pump.row(source))
            val (vm, _) = collect(repo)
            vm.onModelSelected(backing.target)
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            runCurrent()
            // Both authenticated transitions are processed without a downstream consumer yield.
            pump.transition("s1", "s2", backing.other)
            pump.transition("s2", "s3", source)
            runCurrent()
            assertTrue("Remote command must have succeeded", completed)
            assertEquals(source, vm.state.value.runConfig.agent)
            assertEquals(1, pump.sent.count { it.type == "switch_agent" })
            assertFalse("Confirmed remote switch was superseded before observation", vm.state.value.runConfig.pending)
            assertNull(vm.state.value.runConfig.agentSwitch)
        }

    @Test fun replacementClaudeToCodexSuccessSettles() = replacementSuccess(ConversationAgent.Claude)

    @Test fun replacementCodexToClaudeSuccessSettles() = replacementSuccess(ConversationAgent.Codex)

    private fun replacementSuccess(source: ConversationAgent) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val old = Repo(source)
            val authoritative = MutableStateFlow(old.observeConversations(ConversationFilter.All).first())
            var succeeded = false
            val oldRepo =
                object : ConversationRepository by old {
                    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = authoritative

                    override suspend fun switchAgent(
                        conversationId: String,
                        agent: ConversationAgent,
                        model: String,
                        effort: String?,
                    ): Result<Unit> = old.switchAgent(conversationId, agent, model, effort).also { succeeded = it.isSuccess }
                }
            // Authoritative StateFlow models the sessionless snapshot after connection replacement.
            val (vm, _) = collect(oldRepo)
            vm.onModelSelected(old.target)
            runCurrent()
            vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
            runCurrent()
            authoritative.value = authoritative.value.map { it.copy(agent = old.other, currentSessionId = "s2") }
            old.result.complete(Result.success(Unit))
            // Target and disconnected-gap observations are skipped; the new source summary omits session.
            authoritative.value = authoritative.value.map { it.copy(agent = source, currentSessionId = "") }
            old.settings.value = reading("s3", old.original, "low")
            runCurrent()
            assertTrue("Initiating repository must have returned success", succeeded)
            assertEquals(source, vm.state.value.runConfig.agent)
            assertEquals("s3", vm.state.value.runConfig.sessionId)
            assertTrue("Fresh replacement settings must have arrived", vm.state.value.runConfig.settingsAvailable)
            assertEquals(1, old.switches.size)
            assertFalse("Successful switch must settle across a sessionless replacement summary", vm.state.value.runConfig.pending)
            val picked = if (source == ConversationAgent.Claude) "haiku" else "gpt-6-sol"
            vm.onModelSelected(picked)
            runCurrent()
            old.settings.value = reading("s3", picked, "low")
            runCurrent()
            vm.onEffortSelected("high")
            runCurrent()
            old.settings.value = reading("s3", picked, "high")
            runCurrent()
            vm.onPermissionModeSelected("plan")
            runCurrent()
            assertNull(vm.state.value.runConfig.agentSwitch)
            assertEquals(
                listOf(
                    listOf("s3", if (source == ConversationAgent.Claude) "haiku" else "gpt-6-sol", null, null),
                    listOf("s3", null, "high", null),
                    listOf("s3", null, null, "plan"),
                ),
                old.settingsWrites,
            )
        }

    @Test fun realReplacementClaudeToCodexSuccessSettles() = realReplacementSuccess(ConversationAgent.Claude)

    @Test fun realReplacementCodexToClaudeSuccessSettles() = realReplacementSuccess(ConversationAgent.Codex)

    private fun realReplacementSuccess(
        source: ConversationAgent,
        delayed: Boolean = false,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = Repo(source)
        val oldPump = ProjectionPump().apply { settings = reading("s1", models.original, "high") }
        val newPump =
            ProjectionPump().apply {
                settings = reading("s3", models.original, "low")
                answerSettings = !delayed
            }

        fun remote(pump: ProjectionPump) =
            RemoteConversationRepository(
                pump,
                CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler)),
                negotiatedCapabilities = { setOf("interactive", "multi_agent") },
            )
        val oldRemote = remote(oldPump)
        val newRemote = remote(newPump)
        var succeeded = false
        val oldRepo =
            object : ConversationRepository by oldRemote {
                override fun observeModelMenu(conversationId: String) = models.observeModelMenu(conversationId)

                override suspend fun switchAgent(
                    conversationId: String,
                    agent: ConversationAgent,
                    model: String,
                    effort: String?,
                ): Result<Unit> = oldRemote.switchAgent(conversationId, agent, model, effort).also { succeeded = it.isSuccess }
            }
        val newRepo =
            object : ConversationRepository by newRemote {
                override fun observeModelMenu(conversationId: String) = models.observeModelMenu(conversationId)
            }
        val current = MutableStateFlow<ConversationRepository?>(oldRepo)
        val stable = StableConversationRepository(current, HostReadings())
        val available = MutableStateFlow(true)
        oldPump.push("conversation_updated", oldPump.row(source))
        val (vm, _) = collect(stable, available)
        vm.onModelSelected(models.target)
        vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
        runCurrent()
        // The old authenticated command confirms; replacement metadata has no session field.
        oldPump.transition("s1", "s2", models.other)
        oldPump.transition("s2", "s3", source)
        newPump.push("conversation_updated", newPump.row(source))
        available.value = false
        current.value = null
        current.value = newRepo
        available.value = true
        runCurrent()
        assertTrue("Old RemoteConversationRepository command must have returned success", succeeded)
        assertEquals(source, vm.state.value.runConfig.agent)
        assertFalse(vm.state.value.runConfig.pending)
        assertNull(vm.state.value.runConfig.agentSwitch)
        if (delayed) {
            assertFalse(vm.state.value.runConfig.settingsAvailable)
            assertFalse(vm.state.value.runConfig.writable)
            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, vm.state.value.runConfig.modelLabel)
            // Detached replies cannot establish authority on the replacement, even with equal agents.
            oldPump.settings = reading("s1", models.original, "high")
            oldRemote.refreshSessionSettings(CONV)
            runCurrent()
            assertFalse(vm.state.value.runConfig.settingsAvailable)
            vm.onModelSelected(if (source == ConversationAgent.Claude) "haiku" else "gpt-6-sol")
            vm.onEffortSelected("high")
            vm.onPermissionModeSelected("plan")
            runCurrent()
            assertTrue(newPump.sent.none { it.type == "set_session_settings" })
            newPump.answerSettings = true
            newPump.replySettings(newPump.sent.last { it.type == "request_session_settings" })
            runCurrent()
        }
        assertEquals("s3", vm.state.value.runConfig.sessionId)
        assertTrue("Fresh conversation-correlated replacement reply must have arrived", vm.state.value.runConfig.settingsAvailable)
        assertEquals(1, oldPump.sent.count { it.type == "switch_agent" })
        assertEquals(0, newPump.sent.count { it.type == "switch_agent" })
        assertFalse("Confirmed remote switch must settle across a sessionless replacement summary", vm.state.value.runConfig.pending)
        vm.onModelSelected(if (source == ConversationAgent.Claude) "haiku" else "gpt-6-sol")
        vm.onEffortSelected("high")
        vm.onPermissionModeSelected("plan")
        runCurrent()
        assertNull(vm.state.value.runConfig.agentSwitch)
        val writes = newPump.sent.filter { it.type == "set_session_settings" }.map { it.payload.jsonObject }
        assertEquals(3, writes.size)
        assertTrue(writes.all { it["session_id"]?.jsonPrimitive?.content == "s3" })
        assertEquals(if (source == ConversationAgent.Claude) "haiku" else "gpt-6-sol", writes[0]["model"]?.jsonPrimitive?.content)
        assertEquals("high", writes[1]["effort"]?.jsonPrimitive?.content)
        assertEquals("plan", writes[2]["permission_mode"]?.jsonPrimitive?.content)
    }

    @Test fun delayedReplacementClaudeToCodexSettingsRestoreWrites() = realReplacementSuccess(ConversationAgent.Claude, delayed = true)

    @Test fun delayedReplacementCodexToClaudeSettingsRestoreWrites() = realReplacementSuccess(ConversationAgent.Codex, delayed = true)

    @Test fun successWithoutAnySuccessorObservationSettlesAndRefreshesOnce() =
        runTest {
            for (source in ConversationAgent.entries) {
                val repo = Repo(source)
                val (vm, _) = collect(repo)
                vm.onModelSelected(repo.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                runCurrent()
                val before = repo.refreshes
                repo.result.complete(Result.success(Unit))
                runCurrent()
                assertNull(vm.state.value.runConfig.agentSwitch)
                assertFalse(vm.state.value.runConfig.pending)
                assertFalse(vm.state.value.runConfig.settingsAvailable)
                assertFalse(vm.state.value.runConfig.writable)
                assertEquals(UNKNOWN_RUN_CONFIG_LABEL, vm.state.value.runConfig.modelLabel)
                assertEquals(before + 1, repo.refreshes)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                runCurrent()
                assertEquals(1, repo.switches.size)
                assertEquals(before + 1, repo.refreshes)
            }
        }

    @Test fun replacementWithoutGapRetiresBoundDisplayEvenWithSameAgentAndSession() =
        runTest {
            for (source in ConversationAgent.entries) {
                val old = Repo(source)
                val current = MutableStateFlow<ConversationRepository?>(old)
                val (vm, _) = collect(StableConversationRepository(current))
                vm.onModelSelected(old.target)
                vm.onOverflowEvent(ThreadEvent.AgentSwitchConfirm)
                runCurrent()
                old.settings.value = null
                old.binding.value = old.other to "s2"
                old.result.complete(Result.success(Unit))
                runCurrent()
                assertEquals(old.targetLabel, vm.state.value.runConfig.modelLabel)
                val replacement =
                    Repo(old.other).apply {
                        binding.value = old.other to "s2"
                        settings.value = reading("s2", target, "high").copy(held = true)
                    }
                current.value = replacement
                runCurrent()
                assertEquals(old.other, vm.state.value.runConfig.agent)
                assertFalse(vm.state.value.runConfig.settingsAvailable)
                assertEquals(UNKNOWN_RUN_CONFIG_LABEL, vm.state.value.runConfig.modelLabel)
                replacement.settings.value = null
                runCurrent()
                current.value = old
                runCurrent()
                assertEquals(UNKNOWN_RUN_CONFIG_LABEL, vm.state.value.runConfig.modelLabel)
                assertNull(vm.state.value.runConfig.confirmedSwitchChoice)
                assertEquals(1, old.switches.size)
                assertTrue(replacement.switches.isEmpty())
            }
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
        val settingsWrites = mutableListOf<List<String?>>()
        var refreshes = 0

        override fun refreshSessionSettings(conversationId: String) {
            refreshes++
        }

        var throwOnSwitch = false
        var clearSettingsBeforeReturn = false
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
                listOf(sessionId, model, effort, permissionMode)
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
                val outcome = result.await()
                if (clearSettingsBeforeReturn) settings.value = null
                if (throwOnSwitch) throw IllegalStateException("private daemon details")
                return outcome
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
