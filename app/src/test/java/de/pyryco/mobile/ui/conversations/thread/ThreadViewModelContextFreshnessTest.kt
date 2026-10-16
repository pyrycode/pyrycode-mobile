package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.HostReadings
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Exercises invalidation through the real inbound fold, never by clearing a fake reading. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelContextFreshnessTest {
    private val previousSink = RelayLog.sink

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun tearDown() {
        RelayLog.sink = previousSink
        Dispatchers.resetMain()
    }

    @Test
    fun resetReplacement_hidesOldSources_thenAcceptsContextBeforeSettings() =
        runTest {
            val rig = rig()
            rig.settings("s1", used = 100_000)
            rig.context("c1", used = 150_000)
            runCurrent()
            assertEquals(75, rig.vm.state.value.runConfig.contextPercent)
            val asks = rig.pump.sent.count { it.type == "request_context_usage" }

            rig.resetting(true)
            runCurrent()
            rig.transition("c1", "s1", "s2")
            runCurrent()
            assertNull(rig.repo.observeContextUsage("c1").first())
            assertEquals(
                "s2",
                rig.repo
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single()
                    .currentSessionId,
            )
            assertNull("old settings must not replace the cleared context", rig.vm.state.value.runConfig.contextPercent)

            rig.resetting(false)
            runCurrent()
            assertEquals(asks + 1, rig.pump.sent.count { it.type == "request_context_usage" })
            assertNull(rig.vm.state.value.runConfig.contextPercent)
            rig.context("c1", used = 2_000)
            runCurrent()
            assertEquals("fresh context must not wait for settings", 1, rig.vm.state.value.runConfig.contextPercent)
            rig.settings("s2", used = 80_000)
            runCurrent()
            assertEquals("context still wins over matching settings", 1, rig.vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun rememberedOldReply_afterReplacement_neverRestoresOldContext() =
        runTest {
            val rig = rig()
            rig.settings("s1", used = 100_000)
            rig.context("c1", used = 150_000)
            rig.context("c2", used = 80_000, asOf = TS)
            runCurrent()
            assertEquals(75, rig.vm.state.value.runConfig.contextPercent)

            rig.resetting(true)
            rig.transition("c1", "s1", "s2", at = LATER)
            rig.resetting(false)
            runCurrent()
            rig.context("c1", used = 150_000, asOf = TS)
            runCurrent()
            assertNull("a remembered reply must not restore the replaced context", rig.repo.observeContextUsage("c1").first())
            assertNull("old settings cannot fill in for the rejected reply", rig.vm.state.value.runConfig.contextPercent)
            assertEquals(
                80_000L,
                rig.repo
                    .observeContextUsage("c2")
                    .first()
                    ?.totalTokens,
            )

            rig.settings("s2", used = 0)
            runCurrent()
            rig.context("c1", used = 150_000, asOf = TS)
            rig.context("c2", used = 60_000, asOf = TS)
            runCurrent()
            assertNull(rig.repo.observeContextUsage("c1").first())
            assertEquals("current settings remain the fallback", 0, rig.vm.state.value.runConfig.contextPercent)
            assertEquals(
                "the cutoff belongs only to c1",
                60_000L,
                rig.repo
                    .observeContextUsage("c2")
                    .first()
                    ?.totalTokens,
            )

            rig.context("c1", used = 2_000)
            runCurrent()
            rig.context("c1", used = 150_000, asOf = TS)
            runCurrent()
            assertEquals(
                "late old replies cannot overwrite fresh context",
                2_000L,
                rig.repo
                    .observeContextUsage("c1")
                    .first()
                    ?.totalTokens,
            )
            assertEquals(1, rig.vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun rememberedReplies_useTheLatestConversationBoundary() =
        runTest {
            val rig = rig()
            rig.transition("c1", "s1", "s2", at = TS)
            rig.transition("c1", "s2", "s3", at = LATER)
            // A replayed older boundary must not reopen admission for old readings.
            rig.transition("c1", "s1", "s2", at = TS)
            runCurrent()
            rig.context("c1", used = 150_000, asOf = TS)
            runCurrent()
            assertNull(rig.repo.observeContextUsage("c1").first())
            assertNull(rig.vm.state.value.runConfig.contextPercent)

            rig.context("c1", used = 2_000, asOf = LATER)
            runCurrent()
            assertEquals("a reading at the boundary is current", 1, rig.vm.state.value.runConfig.contextPercent)
            rig.context("c1", used = 4_000, asOf = "2026-10-12T00:02:00Z")
            runCurrent()
            assertEquals("a newer remembered reading is current", 2, rig.vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun reconnect_keepsTheReplacementBoundary() =
        runTest {
            val readings = HostReadings()
            val rig = rig(readings)
            rig.transition("c1", "s1", "s2", at = LATER)
            runCurrent()
            val reconnectedPump = Pump()
            val reconnectedRepo =
                RemoteConversationRepository(
                    reconnectedPump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD) },
                    hostReadings = readings,
                )
            reconnectedPump.push(
                "context_usage",
                """{"conversation_id":"c1","total_tokens":150000,"max_tokens":200000,"percentage":75,"as_of":"$TS"}""",
            )
            runCurrent()
            assertNull("reconnect must not forget the source boundary", reconnectedRepo.observeContextUsage("c1").first())
            reconnectedPump.push(
                "context_usage",
                """{"conversation_id":"c1","total_tokens":2000,"max_tokens":200000,"percentage":1}""",
            )
            runCurrent()
            assertEquals(2_000L, reconnectedRepo.observeContextUsage("c1").first()?.totalTokens)
        }

    @Test
    fun currentSessionSettings_areTheOnlyFallback() =
        runTest {
            val rig = rig()
            rig.settings("s1", used = 100_000)
            runCurrent()
            assertEquals(50, rig.vm.state.value.runConfig.contextPercent)

            rig.transition("c1", "s1", "s2")
            runCurrent()
            assertNull(rig.vm.state.value.runConfig.contextPercent)
            rig.settings("s1", used = 100_000)
            runCurrent()
            assertNull("a delayed stale settings reply is not current", rig.vm.state.value.runConfig.contextPercent)
            rig.repo.refreshSessionSettings("c1")
            runCurrent()
            rig.settings("s2", used = 40_000)
            runCurrent()
            assertEquals(20, rig.vm.state.value.runConfig.contextPercent)

            rig.transition("c1", "s2", "s3")
            runCurrent()
            assertNull("a second replacement must also invalidate fallback", rig.vm.state.value.runConfig.contextPercent)
            rig.settings("", used = 40_000)
            runCurrent()
            assertNull("unresolved settings are not a current session", rig.vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun unresolvedSession_doesNotAllowSettingsFallback_butAcceptsContext() =
        runTest {
            val rig = rig()
            rig.pump.push(
                "conversations",
                """{"conversations":[{"id":"c1","name":"Context test","is_promoted":true,"cwd":"/p","current_session_id":"","last_used_at":"$TS","last_message_ts":"$TS"}]}""",
            )
            rig.settings("", used = 100_000)
            runCurrent()
            assertNull(rig.vm.state.value.runConfig.contextPercent)
            rig.context("c1", used = 2_000)
            runCurrent()
            assertEquals(1, rig.vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun anotherConversationReplacement_keepsTheReading() =
        runTest {
            val rig = rig()
            rig.settings("s1", used = 100_000)
            rig.context("c1", used = 150_000)
            rig.context("c2", used = 80_000)
            runCurrent()
            rig.transition("c2", "other-old", "other-new")
            runCurrent()
            assertEquals(75, rig.vm.state.value.runConfig.contextPercent)
            assertNull(rig.repo.observeContextUsage("c2").first())
            rig.transition("c1", "s1", "s2")
            runCurrent()
            rig.context("c2", used = 60_000)
            runCurrent()
            assertNull(rig.vm.state.value.runConfig.contextPercent)
            assertEquals(
                60_000L,
                rig.repo
                    .observeContextUsage("c2")
                    .first()
                    ?.totalTokens,
            )
        }

    private fun TestScope.rig(hostReadings: HostReadings = HostReadings()): Rig {
        val pump = Pump()
        val repo =
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD) },
                hostReadings = hostReadings,
            )
        pump.push(
            "conversations",
            """{"conversations":[{"id":"c1","name":"Context test","is_promoted":true,"cwd":"/p","current_session_id":"s1","last_used_at":"$TS","last_message_ts":"$TS"}]}""",
        )
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to "c1")),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                liveSessionEvents = repo.liveSessionEvents,
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return Rig(pump, repo, vm)
    }

    private class Rig(
        val pump: Pump,
        val repo: RemoteConversationRepository,
        val vm: ThreadViewModel,
    ) {
        fun settings(
            session: String,
            used: Long,
        ) {
            val ask = pump.sent.last { it.type == "request_session_settings" }
            pump.push(
                "session_settings",
                """{"session_id":"$session","model":"sonnet","effort":"high","permission_mode":"default","yolo":false,"used_tokens":$used,"window_tokens":200000}""",
                ask.id,
            )
        }

        fun context(
            conversation: String,
            used: Long,
            asOf: String? = null,
        ) = pump.push(
            "context_usage",
            """{"conversation_id":"$conversation","total_tokens":$used,"max_tokens":200000,"percentage":99,"categories":[],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],"dropped_memory_files":0${asOf?.let {
                ""","as_of":"$it""""
            } ?: ""}}""",
        )

        fun transition(
            conversation: String,
            old: String,
            new: String,
            at: String = TS,
        ) = pump.push(
            "session_transition",
            """{"conversation_id":"$conversation","previous_session_id":"$old","new_session_id":"$new","reason":"clear","occurred_at":"$at","workspace_cwd":null}""",
        )

        fun resetting(active: Boolean) =
            pump.push("resetting", """{"conversation_id":"c1","active":$active,"phase":"wrapping_up","handoff":"pending"}""")
    }

    private class Pump : SessionPump {
        private val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = frames.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        private var id = 1L

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(
            type: String,
            payload: String,
            replyTo: Long? = null,
        ) {
            frames.trySend(Envelope(id++, type, TS, MobileJson.parseToJsonElement(payload), inReplyTo = replyTo))
        }
    }

    private companion object {
        // The recorded defect crossed a boundary within one second; retain subsecond precision.
        const val TS = "2026-10-12T00:00:00.712427Z"
        const val LATER = "2026-10-12T00:00:00.717282Z"
    }
}
