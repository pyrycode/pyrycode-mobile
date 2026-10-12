package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeSessionLiveTest {
    private val previousSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun replacementRejectsRetiredReadingsAndDuplicateClearsKeepFreshState() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("stall", "old", clear = true))
            pump.push(frame("stall", "old", payload = "{\"conversation_id\":\"c\"}"))
            runCurrent()
            assertTrue(repo.observeStall("c").first())
            pump.push(frame("stall", "new", clear = true))
            runCurrent()
            assertFalse(repo.observeStall("c").first())
            pump.push(frame("stall", "old", payload = "{\"conversation_id\":\"c\"}"))
            runCurrent()
            assertFalse(repo.observeStall("c").first())
            pump.push(frame("stall", "new", payload = "{\"conversation_id\":\"c\"}"))
            pump.push(frame("stall", "new", clear = true))
            runCurrent()
            assertTrue(repo.observeStall("c").first())
        }

    @Test fun malformedConversationIdentityNeverFallsBackToPayload() =
        runTest {
            for (identity in listOf(null, "null", "\"\"", "7", "{}", "\"other\"")) {
                val (pump, repo) = repository()
                pump.push(frame("stall", "s", clear = true))
                pump.push(frame("stall", "s", identity = identity, payload = "{\"conversation_id\":\"c\"}"))
                runCurrent()
                assertFalse("identity=$identity", repo.observeStall("c").first())
            }
        }

    @Test fun missingAndNullProvenanceCannotReplaceTaggedReading() =
        runTest {
            for (tag in listOf(null, "null", "\"\"", "false", "{}")) {
                val (pump, repo) = repository()
                pump.push(frame("stall", "s", clear = true))
                pump.push(frame("stall", "s", payload = "{\"conversation_id\":\"c\"}"))
                pump.push(frame("stall", tag = tag, clear = true))
                runCurrent()
                // Null is authoritative reconciliation; missing and malformed are never a replacement.
                assertEquals(tag != "null", repo.observeStall("c").first())
            }
        }

    @Test fun emptyClearsRouteByConversationEvenWhenSessionsAreEqual() =
        runTest {
            val (pump, repo) = repository()
            for (c in listOf("c", "other")) {
                pump.push(frame("stall", "s", identity = "\"$c\"", clear = true))
                pump.push(frame("stall", "s", identity = "\"$c\"", payload = "{\"conversation_id\":\"$c\"}"))
            }
            pump.push(frame("stall", "next", clear = true))
            runCurrent()
            assertFalse(repo.observeStall("c").first())
            assertTrue(repo.observeStall("other").first())
        }

    @Test fun everyStatusFamilyClearsOnlyItselfAndDuplicateClearKeepsFresh() =
        runTest {
            val families =
                mapOf(
                    "turn_state" to "\"state\":\"thinking\"",
                    "stall" to "",
                    "api_retry" to "\"active\":true,\"current\":1,\"total\":3",
                    "compacting" to "\"active\":true",
                    "thinking_progress" to "\"estimated_tokens\":100,\"estimated_tokens_delta\":10",
                    "resetting" to "\"active\":true,\"phase\":\"wrapping_up\",\"handoff\":\"pending\"",
                    "context_usage" to "\"total_tokens\":100,\"max_tokens\":1000,\"percentage\":10",
                    "model_announced" to "\"model\":\"model\",\"truncated\":false",
                    "session_facts" to "\"claude_code_version\":\"1\",\"permission_mode\":\"default\"",
                    "session_settings" to settings("s"),
                    "mcp_status" to "\"servers\":[],\"dropped_servers\":0",
                    "slash_command_list" to "\"commands\":[],\"dropped_commands\":0",
                    "model_list" to "\"models\":[],\"dropped_models\":0",
                    "tool_progress" to "\"turn_id\":\"turn\",\"tool_use_id\":\"tool\",\"elapsed_seconds\":7",
                    "session_error" to "\"code\":\"failed\",\"message\":\"sensitive\"",
                )
            for ((type, fields) in families) {
                val (pump, repo) = repository()
                val baseline = status(repo, type)
                pump.push(frame(type, "s", clear = true))
                pump.push(frame(type, "s", payload = payload(fields)))
                runCurrent()
                val fresh = status(repo, type)
                assertNotEquals(type, baseline, fresh)
                pump.push(frame(type, "s", clear = true))
                runCurrent()
                assertEquals("duplicate $type", fresh, status(repo, type))
                // A different family's first clear cannot erase this accepted reading.
                pump.push(frame(if (type == "stall") "turn_state" else "stall", "s", clear = true))
                runCurrent()
                assertEquals("other family $type", fresh, status(repo, type))
                pump.push(frame("stall", "next", clear = true))
                runCurrent()
                assertEquals("replacement $type", baseline, status(repo, type))
                pump.push(frame(type, "s", payload = payload(fields)))
                runCurrent()
                assertEquals("retired $type", baseline, status(repo, type))
            }
        }

    @Test fun summariesEstablishIdentityWithoutInventingActivityAndOldTagsCannotReturn() =
        runTest {
            val (pump, repo) = repository()
            pump.push(summary("z-session"))
            runCurrent()
            assertEquals(
                "z-session",
                repo
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single()
                    .currentSessionId,
            )
            assertEquals(de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase.Idle, repo.observeTurnPhase("c").first())
            pump.push(frame("stall", "z-session", payload = payload("")))
            pump.push(summary("a-session"))
            pump.push(frame("stall", "z-session", payload = payload("")))
            runCurrent()
            assertFalse(repo.observeStall("c").first())
            pump.push(frame("stall", "a-session", payload = payload("")))
            runCurrent()
            assertTrue(repo.observeStall("c").first())
        }

    @Test fun nullAndMissingOwnersRemainIsolatedAcrossConversationsAndHosts() =
        runTest {
            for (tag in listOf(null, "null", "\"same\"")) {
                val (pump, repo) = repository()
                val (otherPump, otherHost) = repository("other-host")
                for ((p, c) in listOf(pump to "c", pump to "other", otherPump to "c")) {
                    p.push(frame("stall", tag = tag, identity = "\"$c\"", clear = true))
                    p.push(frame("stall", tag = tag, identity = "\"$c\"", payload = payload("", c)))
                }
                pump.push(frame("stall", "new", clear = true))
                runCurrent()
                assertFalse(repo.observeStall("c").first())
                assertTrue(repo.observeStall("other").first())
                assertTrue(otherHost.observeStall("c").first())
            }
        }

    @Test fun invalidPayloadIdentityAndClearShapeCannotMoveTheSession() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("stall", "s", clear = true))
            pump.push(frame("stall", "s", payload = payload("")))
            runCurrent()
            for (identity in listOf("null", "\"\"", "2", "false", "{}", "\"other\"")) {
                pump.push(frame("stall", "new", clear = true, payload = "{\"conversation_id\":$identity}"))
                pump.push(frame("stall", "s", payload = "{\"conversation_id\":$identity}"))
            }
            pump.push(frame("stall", "new", clear = true).copy(inReplyTo = 4))
            pump.push(frame("stall", "new", clear = true).copy(eventId = 4))
            pump.push(frame("stall", "new", clear = true).copy(historyEntryId = 4u))
            runCurrent()
            assertTrue(repo.observeStall("c").first())
            pump.push(frame("stall", "s", payload = payload("")))
            runCurrent()
            assertTrue(repo.observeStall("c").first())
        }

    @Test fun inactiveRetryAndCompactionRepairLostEdgesWithoutDurableRowsOrReadEvidence() =
        runTest {
            val (pump, repo) = repository()
            val baseline = repo.observeThreadSnapshot("c").first()
            for (type in listOf("api_retry", "compacting")) {
                pump.push(frame(type, "s", clear = true))
                pump.push(frame(type, "s", payload = payload("\"active\":true,\"current\":1,\"total\":3")))
                runCurrent()
                assertNotEquals(if (type == "api_retry") ApiRetryStatus.NotRetrying else false, status(repo, type))
                pump.push(frame(type, "s", payload = payload("\"active\":false,\"current\":1,\"total\":3")))
            }
            runCurrent()
            assertEquals(ApiRetryStatus.NotRetrying, repo.observeApiRetry("c").first())
            assertFalse(repo.observeCompacting("c").first())
            assertEquals(baseline, repo.observeThreadSnapshot("c").first())
        }

    @Test fun replacementPreservesItemsQueuePendingAndAccountRate() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("rate_limited", "s", clear = true))
            val quota = payload("\"status\":\"allowed_warning\",\"limit_type\":\"seven_day\",\"resets_at\":0")
            pump.push(frame("rate_limited", "s", payload = quota))
            val item = AuthoritativeThreadStoreTest.item()
            val update = AuthoritativeThreadStoreTest.added(item)
            pump.push(Envelope(2, update.type, "2026-10-12T00:00:00Z", update.raw))
            val queuedItem = AuthoritativeThreadStoreTest.item(2, order = null, kind = "user_message", status = "queued")
            val pending = AuthoritativeThreadStoreTest.added(queuedItem)
            pump.push(Envelope(3, pending.type, "2026-10-12T00:00:00Z", pending.raw))
            pump.push(
                frame(
                    "queue_state",
                    payload =
                        payload(
                            (
                                "\"queued\":[{\"queued_msg_id\":1,\"message_id\":\"pending\"," +
                                    "\"text\":\"queued\",\"ts\":\"2026-10-12T00:00:00Z\"}]"
                            ),
                        ),
                ),
            )
            runCurrent()
            val queue = repo.observeQueue("c").first()
            val rate = repo.observeUsageLimit("c").first()
            val durable = repo.authoritativeThreads.current("host", "c")
            assertEquals(1, queue.size)
            assertNotNull(rate)
            assertEquals(2, durable.items.size)
            pump.push(frame("stall", "new", clear = true))
            pump.push(frame("rate_limited", "new", clear = true))
            runCurrent()
            assertEquals(queue, repo.observeQueue("c").first())
            assertEquals(rate, repo.observeUsageLimit("c").first())
            assertEquals(durable, repo.authoritativeThreads.current("host", "c"))
        }

    @Test fun taggedSettingsRepliesAndUpdatesCannotSettleRetiredSessionRequests() =
        runTest {
            val (pump, repo) = repository()
            pump.push(summary("old"))
            runCurrent()
            val write = async { repo.setSessionSettings("old", model = "model", effort = null, yolo = null) }
            runCurrent()
            val request = pump.sent.single { it.type == "set_session_settings" }
            pump.push(frame("session_settings", "old", payload = payload(settings("old"))))
            pump.push(frame("session_settings", "new", clear = true))
            pump.push(frame("session_settings_updated", "old", payload = "{\"session_id\":\"old\"}").copy(inReplyTo = request.id))
            pump.push(frame("session_settings", "old", payload = payload(settings("old"))).copy(inReplyTo = request.id))
            runCurrent()
            assertFalse(write.isCompleted)
            assertNull(repo.observeSessionSettings("c").first())
            pump.push(frame("session_settings", "new", payload = payload(settings("new"))))
            pump.push(frame("session_settings_updated", "new", clear = true))
            runCurrent()
            assertEquals("new", repo.observeSessionSettings("c").first()?.sessionId)
            write.cancel()
        }

    @Test fun metadataRoundTripsOmittedNullAndStringWithoutChangingLegacyBytes() {
        for (tag in listOf(null, "null", "\"s\"")) {
            val decoded = frame("stall", tag = tag)
            val encoded = MobileJson.parseToJsonElement(MobileJson.encodeToString(decoded)) as kotlinx.serialization.json.JsonObject
            assertEquals(tag != null, "session_id" in encoded)
            if (tag != null) assertEquals(MobileJson.parseToJsonElement(tag), encoded["session_id"])
            assertEquals(decoded, MobileJson.decodeFromString<Envelope>(encoded.toString()))
        }
        val old = Envelope(1, "ping", "2026-10-12T00:00:00Z")
        val encoded = MobileJson.parseToJsonElement(MobileJson.encodeToString(old)) as kotlinx.serialization.json.JsonObject
        assertFalse("conversation_id" in encoded)
        assertFalse("session_id" in encoded)
        assertFalse("session_state_cleared" in encoded)
    }

    @Test fun permissionClearsRetireOnlyTheOwnerWithoutSendingAnswersOrResurrectingHistory() =
        runTest {
            val (pump, repo) = repository()
            var host =
                de.pyryco.mobile.data.model
                    .HostModalState()
            backgroundScope.launch { repo.authoritativeHostModals.collect { host = it } }
            pump.push(frame("modal_shown", "old", clear = true))
            pump.push(frame("modal_shown", "old", payload = modal("old-request")))
            pump.push(frame("modal_shown", "old", identity = "\"other\"", clear = true))
            pump.push(frame("modal_shown", "old", identity = "\"other\"", payload = modal("other-request", "other")))
            runCurrent()
            assertEquals(2, host.outstanding.size)
            pump.push(frame("modal_shown", "new", clear = true))
            runCurrent()
            assertEquals(listOf("other-request"), host.outstanding.map { it.modalId })
            assertTrue(pump.sent.isEmpty())
            assertTrue(runCatching { repo.answerModal("old-request", "deny") }.isFailure)
            assertTrue(runCatching { repo.cancelModal("old-request") }.isFailure)
            pump.push(frame("modal_shown", "old", payload = modal("old-request")))
            pump.push(frame("modal_shown", "new", payload = modal("new-request")))
            pump.push(frame("modal_shown", "new", clear = true))
            runCurrent()
            assertEquals(setOf("other-request", "new-request"), host.outstanding.map { it.modalId }.toSet())
            val dismissal = "{\"modal_id\":\"new-request\",\"outcome\":\"deny\",\"source\":\"remote\"}"
            pump.push(frame("modal_dismissed", "new", payload = dismissal))
            pump.push(frame("modal_shown", "new", payload = modal("new-request")))
            runCurrent()
            assertEquals(listOf("other-request"), host.outstanding.map { it.modalId })
            assertTrue(pump.sent.isEmpty())
        }

    @Test fun answeredQuestionsStayInertAndRetiredTapsCannotActOnTheReplacement() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("question_shown", "old", clear = true))
            pump.push(frame("question_shown", "old", payload = question("old-batch")))
            runCurrent()
            assertEquals(
                "old-batch",
                repo.questionBatches.value
                    .single()
                    .questionBatchId,
            )
            pump.push(frame("question_shown", "new", clear = true))
            pump.push(frame("question_shown", "new", payload = question("new-batch")))
            runCurrent()
            assertTrue(runCatching { repo.refuseQuestionBatch("old-batch") }.isFailure)
            assertTrue(pump.sent.isEmpty())
            repo.answerQuestionBatch(
                "new-batch",
                listOf(
                    de.pyryco.mobile.data.model
                        .QuestionAnswer(0, listOf("Yes")),
                ),
            )
            assertTrue(repo.questionBatches.value.isEmpty())
            pump.push(frame("question_shown", "new", payload = question("new-batch")))
            runCurrent()
            assertTrue(repo.questionBatches.value.isEmpty())
            assertEquals(listOf("question_answer"), pump.sent.map { it.type })
        }

    @Test fun explicitConversationPreventsForeignQuestionDismissal() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("question_shown", tag = "null", clear = true))
            pump.push(frame("question_shown", tag = "null", payload = question("batch")))
            pump.push(frame("question_shown", tag = "null", identity = "\"other\"", clear = true))
            pump.push(
                frame(
                    "question_dismissed",
                    tag = "null",
                    identity = "\"other\"",
                    payload = (
                        "{\"question_batch_id\":\"batch\",\"outcome\":\"answered\"," +
                            "\"source\":\"remote\"}"
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                "c",
                repo.questionBatches.value
                    .single()
                    .conversationId,
            )
        }

    @Test fun progressAndSuggestionsAreSessionReadingsAndNeverDurableRows() =
        runTest {
            val (pump, repo) = repository()
            pump.push(
                frame(
                    "background_task_started",
                    payload =
                        payload(
                            (
                                "\"task_id\":\"task\",\"tool_call_id\":\"tool\"," +
                                    "\"description\":\"task\",\"task_type\":\"local_agent\""
                            ),
                        ),
                ),
            )
            pump.push(frame("background_task_progress", "s", clear = true))
            val progress =
                payload(
                    (
                        "\"task_id\":\"task\",\"description\":\"working\",\"subagent_type\":\"general\"," +
                            (
                                "\"last_tool_name\":\"Read\",\"total_tokens\":100,\"tool_uses\":1," +
                                    "\"duration_ms\":200,"
                            ) +
                            "\"truncated_fields\":null"
                    ),
                )
            pump.push(frame("background_task_progress", "s", payload = progress))
            runCurrent()
            assertNotNull(
                repo.backgroundTasks.value
                    .getValue("c")
                    .tasks
                    .single()
                    .progress,
            )
            val durable = repo.observeThreadSnapshot("c").first()
            pump.push(frame("background_task_progress", "s", clear = true))
            runCurrent()
            assertNotNull(
                repo.backgroundTasks.value
                    .getValue("c")
                    .tasks
                    .single()
                    .progress,
            )
            pump.push(frame("stall", "new", clear = true))
            pump.push(frame("background_task_progress", "s", payload = progress))
            runCurrent()
            assertNull(
                repo.backgroundTasks.value
                    .getValue("c")
                    .tasks
                    .single()
                    .progress,
            )
            assertEquals(durable, repo.observeThreadSnapshot("c").first())

            val c = "00000000-0000-4000-8000-000000000001"
            val s = "00000000-0000-4000-8000-000000000002"
            val suggestion = payload("\"session_id\":\"$s\",\"revision\":1,\"suggested_reply\":\"Continue\"", c)
            pump.push(frame("reply_suggestion", s, identity = "\"$c\"", clear = true))
            pump.push(frame("reply_suggestion", s, identity = "\"$c\"", payload = suggestion))
            pump.push(frame("reply_suggestion", s, identity = "\"$c\"", clear = true))
            runCurrent()
            assertEquals("Continue", repo.observeReplySuggestion(c, s).first()?.suggestedReply)
            pump.push(frame("reply_suggestion", "new", identity = "\"$c\"", clear = true))
            pump.push(frame("reply_suggestion", s, identity = "\"$c\"", payload = suggestion))
            runCurrent()
            assertNull(repo.observeReplySuggestion(c, s).first())
        }

    @Test fun liveMetadataCannotBecomeReplayOrReadEvidenceAndLogsContainNoContent() =
        runTest {
            val (pump, repo) = repository()
            val before = repo.observeThreadSnapshot("c").first()
            pump.push(frame("session_error", "s", clear = true))
            pump.push(frame("session_error", "s", payload = payload("\"code\":\"secret-code\",\"message\":\"secret-body\"")))
            pump.push(frame("session_error", "s", payload = payload("\"code\":\"new\",\"message\":\"secret-body\"")).copy(eventId = 8))
            runCurrent()
            assertEquals("secret-code", repo.observeSessionError("c").first())
            assertEquals(before, repo.observeThreadSnapshot("c").first())
            assertTrue(logs.none { "secret" in it || "sensitive" in it || "old-request" in it })
        }

    @Test fun reconnectWithoutProvenanceCannotReplaceHostHeldTaggedReadings() =
        runTest {
            val held = HostReadings()
            val (firstPump, first) = repository(readings = held)
            firstPump.push(frame("model_announced", "s", clear = true))
            firstPump.push(frame("model_announced", "s", payload = payload("\"model\":\"tagged\",\"truncated\":false")))
            runCurrent()
            val tagged = first.observeAnnouncedModel("c").first()
            val (pump, reconnect) = repository(readings = held)
            pump.push(frame("model_announced", tag = null, clear = true))
            pump.push(frame("model_announced", tag = null, payload = payload("\"model\":\"untagged\",\"truncated\":false")))
            runCurrent()
            assertEquals(tagged, reconnect.observeAnnouncedModel("c").first())
            pump.push(frame("model_announced", "new", clear = true))
            runCurrent()
            assertNull(reconnect.observeAnnouncedModel("c").first())
            val (thirdPump, third) = repository(readings = held)
            thirdPump.push(frame("model_announced", "s", clear = true))
            thirdPump.push(frame("model_announced", "s", payload = payload("\"model\":\"stale\",\"truncated\":false")))
            runCurrent()
            assertNull(third.observeAnnouncedModel("c").first())
        }

    @Test fun retiredMcpCorrelationCannotRestoreAnUnavailableReading() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("mcp_status", "s", clear = true))
            runCurrent()
            repo.requestMcpStatus("c")
            val request = pump.sent.single { it.type == "mcp_status_request" }
            pump.push(frame("stall", "new", clear = true))
            runCurrent()
            val current = repo.observeMcpStatus("c").first()
            pump.push(
                frame(
                    "error",
                    payload = (
                        "{\"code\":\"mcp_status.unavailable\",\"message\":\"unavailable\"," +
                            "\"retryable\":false}"
                    ),
                ).copy(inReplyTo = request.id),
            )
            runCurrent()
            assertEquals(current, repo.observeMcpStatus("c").first())
        }

    @Test fun malformedClearFlagsCannotEraseAReading() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame("stall", "s", clear = true))
            pump.push(frame("stall", "s", payload = payload("")))
            for (flag in listOf("null", "0", "\"true\"", "{}", "[]")) {
                pump.push(frame("stall", "new", clear = true).copy(sessionStateCleared = MobileJson.parseToJsonElement(flag)))
            }
            runCurrent()
            assertTrue(repo.observeStall("c").first())
        }

    private fun modal(
        id: String,
        c: String = "c",
    ) = payload(
        (
            "\"modal_id\":\"$id\",\"class\":\"permission\",\"title\":\"Permission\"," +
                (
                    "\"prompt\":\"May I proceed?\",\"options\":[{\"id\":\"deny\"," +
                        "\"label\":\"Deny\"}],"
                ) +
                "\"default_option_id\":\"deny\""
        ),
        c,
    )

    private fun question(id: String) =
        payload(
            (
                (
                    "\"question_batch_id\":\"$id\"," +
                        "\"questions\":[{\"question\":\"Proceed?\",\"header\":\"Choice\","
                ) +
                    (
                        "\"options\":[{\"label\":\"Yes\",\"description\":\"Proceed\"}]," +
                            "\"multi_select\":false}]"
                    )
            ),
        )

    private suspend fun status(
        repo: RemoteConversationRepository,
        type: String,
    ): Any? =
        when (type) {
            "turn_state" -> repo.observeTurnPhase("c").first()
            "stall" -> repo.observeStall("c").first()
            "api_retry" -> repo.observeApiRetry("c").first()
            "compacting" -> repo.observeCompacting("c").first()
            "thinking_progress" -> repo.observeThinkingProgress("c").first()
            "resetting" -> repo.observeResetting("c").first()
            "context_usage" -> repo.observeContextUsage("c").first()
            "model_announced" -> repo.observeAnnouncedModel("c").first()
            "session_facts" -> repo.observeSessionFacts("c").first()
            "session_settings" -> repo.observeSessionSettings("c").first()
            "mcp_status" -> repo.observeMcpStatus("c").first()
            "slash_command_list" -> repo.observeSlashCommandMenu("c").first()
            "model_list" -> repo.observeModelMenu("c").first()
            "tool_progress" -> repo.observeAuthoritativeToolProgress("c").first()
            "session_error" -> repo.observeSessionError("c").first()
            else -> error("uncovered family")
        }

    private fun payload(
        fields: String,
        conversation: String = "c",
    ) = "{\"conversation_id\":\"$conversation\"${if (fields.isEmpty()) "" else ",$fields"}}"

    private fun settings(session: String) =
        (
            (
                "\"session_id\":\"$session\",\"model\":\"model\"," +
                    "\"effort\":\"high\",\"yolo\":false,"
            ) +
                "\"permission_mode\":\"default\",\"used_tokens\":0,\"window_tokens\":0"
        )

    private fun summary(session: String) =
        frame(
            "conversations",
            payload =
                """{"conversations":[{"id":"c","current_session_id":"$session","name":null,"cwd":"/tmp","is_promoted":true,
        "last_message_ts":"2026-10-12T00:00:00Z","last_used_at":"2026-10-12T00:00:00Z"}]}""",
        )

    private fun TestScope.repository(
        host: String = "host",
        readings: HostReadings = HostReadings(),
    ): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf("thread", "interactive") },
                authoritativeHostId = host,
                hostReadings = readings,
            )
    }

    private fun frame(
        type: String,
        session: String? = null,
        identity: String? = "\"c\"",
        tag: String? = session?.let { "\"$it\"" },
        clear: Boolean = false,
        payload: String = "{}",
    ): Envelope {
        val metadata =
            listOfNotNull(
                identity?.let { "\"conversation_id\":$it" },
                tag?.let { "\"session_id\":$it" },
                if (clear) "\"session_state_cleared\":true" else null,
            ).joinToString(",")
        return MobileJson.decodeFromString(
            """{"id":1,"type":"$type","ts":"2026-10-12T00:00:00Z",
            "payload":$payload${if (metadata.isEmpty()) "" else ",$metadata"}}""",
        )
    }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }
    }
}
