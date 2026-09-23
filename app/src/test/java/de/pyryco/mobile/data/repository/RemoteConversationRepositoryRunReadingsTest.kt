package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two per-turn readings drawn from claude's `system/init` line (#890): `model_announced` and
 * `session_facts`, decoded in [RemoteConversationRepository.onInbound] behind the `interactive` gate and
 * held per conversation, latest wins. Wire SSOT: pyrycode `docs/protocol-mobile.md` § `model_announced`
 * and § `session_facts`.
 */
class RemoteConversationRepositoryRunReadingsTest {
    // ---- model_announced ---------------------------------------------------------------------------

    @Test
    fun announcedModel_nullUntilAFrame_thenVerbatimWithItsTruncatedFlag() =
        runTest {
            val (pump, repo) = repo()
            val readings = collectAnnouncedModel(repo, "c1")
            runCurrent()
            assertEquals(listOf<AnnouncedModel?>(null), readings)

            pump.push(modelAnnounced("c1", " Claude-Haiku-4-5 ", truncated = false, id = 1L))
            runCurrent()
            pump.push(modelAnnounced("c1", "claude-sonnet-5…", truncated = true, id = 2L))
            runCurrent()

            assertEquals(
                listOf(
                    null,
                    AnnouncedModel(" Claude-Haiku-4-5 ", truncated = false),
                    AnnouncedModel("claude-sonnet-5…", truncated = true),
                ),
                readings,
            )
        }

    // A `/model` turn reports the OLD model on its own init line: latching the first frame shows a stale
    // value, so each frame replaces the reading, including a return to an earlier value.
    @Test
    fun announcedModel_latestFrameWins() =
        runTest {
            val (pump, repo) = repo()
            val readings = collectAnnouncedModel(repo, "c1")
            runCurrent()

            pump.push(modelAnnounced("c1", "claude-opus-5-5", id = 1L))
            pump.push(modelAnnounced("c1", "claude-haiku-4-5-20251001", id = 2L))
            runCurrent()
            assertEquals(AnnouncedModel("claude-haiku-4-5-20251001", truncated = false), readings.last())

            pump.push(modelAnnounced("c1", "claude-opus-5-5", id = 3L))
            runCurrent()
            assertEquals(AnnouncedModel("claude-opus-5-5", truncated = false), readings.last())
        }

    @Test
    fun announcedModel_malformedOrEmptyModel_droppedCollectorSurvives() =
        runTest {
            val (pump, repo) = repo()
            val readings = collectAnnouncedModel(repo, "c1")
            runCurrent()
            pump.push(modelAnnounced("c1", "claude-opus-5-5", id = 1L))
            runCurrent()

            // Missing each field in turn.
            pump.push(probe("model_announced", """{"model":"x","truncated":false}""", id = 2L))
            pump.push(probe("model_announced", """{"conversation_id":"c1","truncated":false}""", id = 3L))
            pump.push(probe("model_announced", """{"conversation_id":"c1","model":"x"}""", id = 4L))
            // Wrong types. A quoted primitive would not do: kotlinx's tree decoder accepts `"truncated":"true"`.
            pump.push(probe("model_announced", """{"conversation_id":1,"model":"x","truncated":false}""", id = 5L))
            pump.push(probe("model_announced", """{"conversation_id":"c1","model":7,"truncated":false}""", id = 6L))
            pump.push(probe("model_announced", """{"conversation_id":"c1","model":null,"truncated":false}""", id = 7L))
            pump.push(probe("model_announced", """{"conversation_id":"c1","model":"x","truncated":{}}""", id = 8L))
            // Well-formed, but the contract says `model` is never empty.
            pump.push(modelAnnounced("c1", "", id = 9L))
            runCurrent()
            assertEquals(listOf(null, AnnouncedModel("claude-opus-5-5", truncated = false)), readings)

            pump.push(modelAnnounced("c1", "claude-sonnet-5", id = 10L))
            runCurrent()
            assertEquals(AnnouncedModel("claude-sonnet-5", truncated = false), readings.last())
        }

    // ---- session_facts -----------------------------------------------------------------------------

    @Test
    fun sessionFacts_nullUntilAFrame_thenEveryFieldVerbatim() =
        runTest {
            val (pump, repo) = repo()
            val readings = collectSessionFacts(repo, "c1")
            runCurrent()
            assertEquals(listOf<SessionFacts?>(null), readings)

            pump.push(sessionFacts("c1", "2.1.259", "default", truncatedFields = null, id = 1L))
            runCurrent()
            // An empty string is a valid "not reported"; an unrecognised posture passes through unfiltered;
            // a version that would not parse is carried as given.
            pump.push(sessionFacts("c1", "", "someFuturePosture", truncatedFields = null, id = 2L))
            runCurrent()
            pump.push(sessionFacts("c1", " 3.0.0-beta+nightly ", "", listOf("claude_code_version", "permission_mode"), id = 3L))
            runCurrent()

            assertEquals(
                listOf(
                    null,
                    SessionFacts("2.1.259", "default", null),
                    SessionFacts("", "someFuturePosture", null),
                    SessionFacts(" 3.0.0-beta+nightly ", "", listOf("claude_code_version", "permission_mode")),
                ),
                readings,
            )
        }

    @Test
    fun sessionFacts_latestFrameWins() =
        runTest {
            val (pump, repo) = repo()
            val readings = collectSessionFacts(repo, "c1")
            runCurrent()

            pump.push(sessionFacts("c1", "2.1.220", "plan", null, id = 1L))
            pump.push(sessionFacts("c1", "2.1.259", "bypassPermissions", null, id = 2L))
            runCurrent()
            assertEquals(SessionFacts("2.1.259", "bypassPermissions", null), readings.last())
        }

    @Test
    fun sessionFacts_malformed_droppedCollectorSurvives() =
        runTest {
            val (pump, repo) = repo()
            val readings = collectSessionFacts(repo, "c1")
            runCurrent()
            pump.push(sessionFacts("c1", "2.1.259", "default", null, id = 1L))
            runCurrent()

            pump.push(probe("session_facts", """{"claude_code_version":"1","permission_mode":"p","truncated_fields":null}""", id = 2L))
            pump.push(probe("session_facts", """{"conversation_id":"c1","permission_mode":"p","truncated_fields":null}""", id = 3L))
            pump.push(probe("session_facts", """{"conversation_id":"c1","claude_code_version":"1","truncated_fields":null}""", id = 4L))
            pump.push(
                probe(
                    "session_facts",
                    """{"conversation_id":1,"claude_code_version":"1","permission_mode":"p","truncated_fields":null}""",
                    id = 5L,
                ),
            )
            pump.push(
                probe(
                    "session_facts",
                    """{"conversation_id":"c1","claude_code_version":[],"permission_mode":"p","truncated_fields":null}""",
                    id = 6L,
                ),
            )
            pump.push(
                probe(
                    "session_facts",
                    """{"conversation_id":"c1","claude_code_version":"1","permission_mode":{},"truncated_fields":null}""",
                    id = 7L,
                ),
            )
            pump.push(
                probe(
                    "session_facts",
                    """{"conversation_id":"c1","claude_code_version":"1","permission_mode":"p","truncated_fields":"permission_mode"}""",
                    id = 8L,
                ),
            )
            pump.push(
                probe(
                    "session_facts",
                    """{"conversation_id":"c1","claude_code_version":"1","permission_mode":"p","truncated_fields":[{}]}""",
                    id = 9L,
                ),
            )
            runCurrent()
            assertEquals(listOf(null, SessionFacts("2.1.259", "default", null)), readings)

            pump.push(sessionFacts("c1", "2.1.260", "plan", null, id = 10L))
            runCurrent()
            assertEquals(SessionFacts("2.1.260", "plan", null), readings.last())
        }

    // ---- both readings: isolation, clearing, the gate, and what they must not touch ----------------

    @Test
    fun aFrameForAnotherConversation_neverChangesThisOne() =
        runTest {
            val (pump, repo) = repo()
            val c1Model = collectAnnouncedModel(repo, "c1")
            val c1Facts = collectSessionFacts(repo, "c1")
            runCurrent()

            pump.push(modelAnnounced("c2", "claude-opus-5-5", id = 1L))
            pump.push(sessionFacts("c2", "2.1.259", "plan", null, id = 2L))
            runCurrent()

            assertEquals(listOf<AnnouncedModel?>(null), c1Model)
            assertEquals(listOf<SessionFacts?>(null), c1Facts)
        }

    @Test
    fun sessionTransition_clearsOnlyItsConversationsReadings() =
        runTest {
            val (pump, repo) = repo()
            val c1Model = collectAnnouncedModel(repo, "c1")
            val c1Facts = collectSessionFacts(repo, "c1")
            val c2Model = collectAnnouncedModel(repo, "c2")
            val c2Facts = collectSessionFacts(repo, "c2")
            runCurrent()

            pump.push(modelAnnounced("c1", "claude-opus-5-5", id = 1L))
            pump.push(sessionFacts("c1", "2.1.259", "default", null, id = 2L))
            pump.push(modelAnnounced("c2", "claude-sonnet-5", id = 3L))
            pump.push(sessionFacts("c2", "2.1.220", "plan", null, id = 4L))
            runCurrent()
            pump.push(sessionTransition("c1", id = 5L))
            runCurrent()

            assertEquals(listOf(null, AnnouncedModel("claude-opus-5-5", truncated = false), null), c1Model)
            assertEquals(listOf(null, SessionFacts("2.1.259", "default", null), null), c1Facts)
            assertEquals(listOf(null, AnnouncedModel("claude-sonnet-5", truncated = false)), c2Model)
            assertEquals(listOf(null, SessionFacts("2.1.220", "plan", null)), c2Facts)
        }

    @Test
    fun capabilityGateClosed_decodesNeitherFrame() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf("something_else"))) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
                val model = collectAnnouncedModel(repo, "c1")
                val facts = collectSessionFacts(repo, "c1")
                runCurrent()

                pump.push(modelAnnounced("c1", "claude-opus-5-5", id = 1L))
                pump.push(sessionFacts("c1", "2.1.259", "default", null, id = 2L))
                runCurrent()
                assertEquals("gate closed for $capabilities", listOf<AnnouncedModel?>(null), model)
                assertEquals("gate closed for $capabilities", listOf<SessionFacts?>(null), facts)
            }
        }

    // Neither frame opens or closes a turn, folds a thread row, raises or clears a stall, or reaches the
    // saved settings: `session_facts.permission_mode` is claude's claim and never the permission reading.
    @Test
    fun neitherFrame_touchesStallThreadOrSessionSettings() =
        runTest {
            val (pump, repo) = repo()
            val stall = collect(repo.observeStall("c1"))
            val thread = collect(repo.observeMessages("c1"))
            val settings = collect(repo.observeSessionSettings("c1"))
            runCurrent()
            pump.push(stall("c1", id = 1L))
            pump.push(settingsReply(inReplyTo = pump.sent.single { it.type == "request_session_settings" }.id, id = 2L))
            runCurrent()
            val sentBefore = pump.sent.size
            val settingsBefore = settings.toList()

            pump.push(modelAnnounced("c1", "claude-haiku-4-5", id = 3L))
            pump.push(sessionFacts("c1", "2.1.259", "bypassPermissions", null, id = 4L))
            runCurrent()

            assertEquals("the stall stands", listOf(false, true), stall)
            assertEquals("no thread row folded", listOf(emptyList<ThreadItem>()), thread)
            assertEquals("the settings reading is untouched", settingsBefore, settings)
            assertEquals("the saved override is not the announced model", "opus", settings.last()?.model)
            assertEquals("the confirmed permission mode is not claude's claim", "default", settings.last()?.permissionMode)
            assertEquals("no frame was sent", sentBefore, pump.sent.size)
        }

    // ---- Fixtures ---------------------------------------------------------------------------------

    private fun TestScope.repo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> {
        val emissions = mutableListOf<T>()
        backgroundScope.launch { flow.collect { emissions += it } }
        return emissions
    }

    private fun TestScope.collectAnnouncedModel(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<AnnouncedModel?> = collect(repo.observeAnnouncedModel(conversationId))

    private fun TestScope.collectSessionFacts(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<SessionFacts?> = collect(repo.observeSessionFacts(conversationId))

    /** A `model_announced` envelope `{conversation_id, model, truncated}`; every key is always on the wire. */
    private fun modelAnnounced(
        conversationId: String,
        model: String,
        truncated: Boolean = false,
        id: Long,
    ): Envelope = probe("model_announced", """{"conversation_id":"$conversationId","model":"$model","truncated":$truncated}""", id)

    /**
     * A `session_facts` envelope `{conversation_id, claude_code_version, permission_mode, truncated_fields}`.
     * The daemon writes `"truncated_fields":null` when nothing was cut, so the helper always emits the key.
     */
    private fun sessionFacts(
        conversationId: String,
        claudeCodeVersion: String,
        permissionMode: String,
        truncatedFields: List<String>?,
        id: Long,
    ): Envelope {
        val fields = truncatedFields?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"
        return probe(
            "session_facts",
            """{"conversation_id":"$conversationId","claude_code_version":"$claudeCodeVersion",""" +
                """"permission_mode":"$permissionMode","truncated_fields":$fields}""",
            id,
        )
    }

    private fun sessionTransition(
        conversationId: String,
        id: Long,
    ): Envelope =
        probe(
            "session_transition",
            """{"conversation_id":"$conversationId","previous_session_id":"s1","new_session_id":"s2",""" +
                """"reason":"clear","occurred_at":"$TS","workspace_cwd":null}""",
            id,
        )

    private fun stall(
        conversationId: String,
        id: Long,
    ): Envelope = probe("stall", """{"conversation_id":"$conversationId"}""", id)

    private fun settingsReply(
        inReplyTo: Long,
        id: Long,
    ): Envelope =
        Envelope(
            id = id,
            type = "session_settings",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"session_id":"sess-a","model":"opus","effort":"high","effective_effort":"medium",""" +
                        """"yolo":false,"permission_mode":"default","used_tokens":12480,"window_tokens":200000}""",
                ),
            inReplyTo = inReplyTo,
        )

    private fun probe(
        type: String,
        payload: String,
        id: Long,
    ): Envelope = Envelope(id = id, type = type, ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-23T10:00:00Z"
    }
}
