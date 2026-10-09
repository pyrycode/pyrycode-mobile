package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.ConversationsPayload
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationReadMarksTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.enabled = oldEnabled
        RelayLog.sink = oldSink
    }

    @Test fun absenceIsNotZeroAndUnsignedIdsNeverOverflow() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            assertNull(repo.observeReadMarks("a").first())
            pump.push(snapshot(row("a")))
            runCurrent()
            assertNull(repo.observeReadMarks("a").first())
            for (number in listOf("0", "9223372036854775808", "18446744073709551615")) {
                pump.push(snapshot(row("a", number, number)))
                runCurrent()
                assertEquals(ConversationReadMarks(number.toULong(), number.toULong()), repo.observeReadMarks("a").first())
                val listDto = MobileJson.decodeFromJsonElement<ConversationsPayload>(snapshot(row("a", number, number)).payload)
                val updateDto = MobileJson.decodeFromJsonElement<ConversationResponseDto>(update("a", number).payload)
                assertEquals(number.toULong(), listDto.conversations.single().readUpTo)
                assertEquals(number.toULong(), listDto.conversations.single().latestEntryId)
                assertEquals(number.toULong(), updateDto.readUpTo)
            }
        }

    @Test fun malformedFieldsNeverCreateCheckpointAndCollectorKeepsOtherMetadata() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            val invalid = listOf("00", "01", "-1", "1.5", "1e2", "true", "\"2\"", "18446744073709551616", "[]", "{}")
            for (value in invalid) {
                pump.push(snapshot(row("a", value, "9")))
                pump.push(update("a", value))
                pump.push(snapshot(row("a", "0", value)))
                runCurrent()
                assertNull(repo.observeReadMarks("a").first())
            }
            pump.push(snapshot(row("a", "0", "9")))
            runCurrent()
            val before = repo.observeConversations(ConversationFilter.All).first().single()
            assertEquals("Name", before.name)
            assertTrue(before.muted)
            assertEquals("Project", before.workspaceLabel)
            for (value in invalid) pump.push(update("a", value))
            runCurrent()
            assertEquals(before, repo.observeConversations(ConversationFilter.All).first().single())
            pump.push(update("a", "5"))
            runCurrent()
            assertEquals(ConversationReadMarks(5uL, 9uL), repo.observeReadMarks("a").first())
        }

    @Test fun explicitNullIsUnavailableAndPartialFactsStayDistinct() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            pump.push(snapshot(row("a", "null", "null"), row("b", null, "0")))
            runCurrent()
            assertNull(repo.observeReadMarks("a").first())
            assertEquals(ConversationReadMarks(null, 0uL), repo.observeReadMarks("b").first())
            pump.push(update("a", "0"))
            runCurrent()
            assertEquals(ConversationReadMarks(0uL, null), repo.observeReadMarks("a").first())
        }

    @Test fun invariantMonotonicFactsSurviveEverySnapshotPushOrderAndReplay() =
        runTest {
            val events = listOf(snapshot(row("a", "3", "12")), update("a", "8"), snapshot(row("a", "5", "10")))
            for (order in permutations(events)) {
                val pump = Pump()
                val repo = repo(pump)
                var read = 0uL
                var latest: ULong? = null
                for (event in order + order.reversed()) {
                    pump.push(event)
                    runCurrent()
                    val facts = requireNotNull(repo.observeReadMarks("a").first())
                    assertTrue(requireNotNull(facts.readUpTo) >= read)
                    latest?.let { assertTrue(requireNotNull(facts.latestEntryId) >= it) }
                    read = requireNotNull(facts.readUpTo)
                    latest = facts.latestEntryId
                }
                assertEquals(ConversationReadMarks(8uL, 12uL), repo.observeReadMarks("a").first())
                pump.push(update("a", null))
                runCurrent()
                assertEquals(ConversationReadMarks(8uL, 12uL), repo.observeReadMarks("a").first())
            }
        }

    @Test fun invariantEmptyAndOverlappingListsCannotEraseKnownFactsOrMixIdentities() =
        runTest {
            for (targetIndex in 0..2) {
                val pump = Pump()
                val repo = repo(pump)
                pump.push(update("a", "7"))
                runCurrent()
                val rows = mutableListOf(row("b", "2", "3"), row("c", "4", "6"))
                rows.add(targetIndex, row("a", "1", "11"))
                pump.push(snapshot(*rows.toTypedArray()))
                runCurrent()
                assertEquals(ConversationReadMarks(7uL, 11uL), repo.observeReadMarks("a").first())
                pump.push(snapshot())
                runCurrent()
                assertTrue(repo.observeConversations(ConversationFilter.All).first().isEmpty())
                pump.push(snapshot(row("b", "0", "0")))
                pump.push(snapshot(row("a", "0", "1")))
                runCurrent()
                assertEquals(ConversationReadMarks(7uL, 11uL), repo.observeReadMarks("a").first())
                assertEquals(ConversationReadMarks(2uL, 3uL), repo.observeReadMarks("b").first())
                assertEquals(ConversationReadMarks(4uL, 6uL), repo.observeReadMarks("c").first())
            }
        }

    @Test fun confirmedWriteSendsExactUnsignedIntegerAndWaitsForItsClampedReply() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            pump.push(snapshot(row("a", "0", "10")))
            runCurrent()
            val write = backgroundScope.async { repo.markConversationRead("a", ULong.MAX_VALUE) }
            runCurrent()
            val request = pump.mark()
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"a","up_to":18446744073709551615}"""), request.payload)
            assertNull(request.inReplyTo)
            assertNull(request.eventId)
            assertFalse(write.isCompleted)
            pump.push(update("a", "5"))
            pump.push(update("a", "6", request.id + 1))
            runCurrent()
            assertFalse(write.isCompleted)
            pump.push(update("a", "10", request.id))
            runCurrent()
            assertEquals(10uL, write.await().getOrThrow())
            assertEquals(ConversationReadMarks(10uL, 10uL), repo.observeReadMarks("a").first())
            pump.push(update("a", "1", request.id))
            pump.push(update("a", "10"))
            runCurrent()
            assertEquals(ConversationReadMarks(10uL, 10uL), repo.observeReadMarks("a").first())
            assertEquals(1, repo.observeConversations(ConversationFilter.All).first().size)
            val noop = backgroundScope.async { repo.markConversationRead("a", 0uL) }
            runCurrent()
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"a","up_to":0}"""), pump.mark().payload)
            pump.push(update("a", "10", pump.mark().id))
            runCurrent()
            assertEquals(10uL, noop.await().getOrThrow())
        }

    @Ignore("blocked on pyrycode/pyrycode#3029: legacy visibility clamp leaves mobile unread above confirmed mark")
    @Test
    fun runtimeReceiptHistoryRequiresCorrelatedDaemonReadConfirmation() =
        runTest {
            val pump = Pump()
            val repository = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val otherHost = RemoteConversationRepository(Pump(), backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            pump.push(snapshot(row("a", "0", "4"), row("b", "0", "4")))
            runCurrent()
            val request = backgroundScope.async { repository.requestHistory("a", "", 100) }
            runCurrent()
            val bytes =
                requireNotNull(javaClass.getResource("/daemon-contract/runtime-read-enabled.json"))
                    .readText()
                    .replace("11111111-1111-4111-8111-111111111111", "a")
            pump.push(
                Envelope(
                    99,
                    "history_page",
                    TS,
                    MobileJson.parseToJsonElement(bytes),
                    inReplyTo = pump.sent.last { it.type == "request_history" }.id,
                ),
            )
            runCurrent()
            assertEquals(6, request.await().entries.size)
            val held = repository.observeThreadSnapshot("a").first()
            val presented = held.rows.filterIsInstance<ThreadItem.MessageItem>().last()
            assertEquals(2, held.rows.count { it.isReadContent() })
            assertFalse(presented.message.isStreaming)
            // The shipped page raises latest to its turn_end ID, unlike the daemon's clamp.
            assertEquals(ConversationReadMarks(0u, 5u), repository.observeReadMarks("a").first())
            assertTrue(pump.sent.none { it.type == "mark_conversation_read" })
            val checkpoint = requireNotNull(held.readEvidence.checkpoint(presented, 0u))
            assertEquals(6uL, checkpoint)
            val write = backgroundScope.async { repository.markConversationRead("a", checkpoint) }
            runCurrent()
            assertEquals(
                "6",
                pump
                    .mark()
                    .payload.jsonObject
                    .getValue("up_to")
                    .jsonPrimitive.content,
            )
            assertFalse(write.isCompleted)
            assertEquals(0uL, repository.observeReadMarks("a").first()?.readUpTo)
            pump.push(update("b", "4"))
            pump.push(update("a", "0", pump.mark().id + 1))
            runCurrent()
            assertFalse(write.isCompleted)
            assertEquals(0uL, repository.observeReadMarks("a").first()?.readUpTo)
            pump.push(update("a", "4", pump.mark().id))
            runCurrent()
            assertEquals(4uL, write.await().getOrThrow())
            assertEquals(ConversationReadMarks(4u, 4u), repository.observeReadMarks("a").first())
            assertNull(otherHost.observeReadMarks("a").first())
            assertTrue(
                otherHost
                    .observeThreadSnapshot("a")
                    .first()
                    .readEvidence.versions
                    .isEmpty(),
            )
            assertNull(held.readEvidence.checkpoint(presented.copy(message = presented.message.copy(content = "unseen")), 0u))
        }

    @Test fun malformedWrongTypeOrWrongTargetRepliesNeverSucceedOrMutateFacts() =
        runTest {
            val bad =
                listOf<(Long) -> Envelope>(
                    { update("a", null, it) },
                    { update("a", "null", it) },
                    { update("a", "\"99\"", it) },
                    { update("a", "-1", it) },
                    { update("b", "99", it) },
                    { update("a", "99", it).copy(type = "ack") },
                    { update("a", "99", it).copy(type = "conversation_created") },
                    { update("a", "99", it).copy(payload = MobileJson.parseToJsonElement("{}")) },
                )
            for (reply in bad) {
                val pump = Pump()
                val repo = repo(pump)
                pump.push(snapshot(row("a", "2", "5")))
                runCurrent()
                val write = backgroundScope.async { repo.markConversationRead("a", 5uL) }
                runCurrent()
                pump.push(reply(pump.mark().id))
                runCurrent()
                val failure = write.await().exceptionOrNull()
                assertTrue(failure is RelayErrorException)
                assertEquals("protocol.malformed_reply", (failure as RelayErrorException).code)
                assertEquals(ConversationReadMarks(2uL, 5uL), repo.observeReadMarks("a").first())
                assertNull(repo.observeReadMarks("b").first())
                pump.push(update("a", "4"))
                runCurrent()
                assertEquals(ConversationReadMarks(4uL, 5uL), repo.observeReadMarks("a").first())
            }
        }

    @Test fun serverRefusalsAreSanitizedAndNeverAdvance() =
        runTest {
            for (code in listOf("read_mark.unavailable", "history.unavailable", "conversation.not_found", "untrusted-secret")) {
                val pump = Pump()
                val repo = repo(pump)
                pump.push(update("a", "0"))
                runCurrent()
                val write = backgroundScope.async { repo.markConversationRead("a", 3uL) }
                runCurrent()
                pump.push(
                    Envelope(
                        99,
                        "error",
                        TS,
                        MobileJson.parseToJsonElement("""{"code":"$code","message":"untrusted-secret","retryable":true}"""),
                        pump.mark().id,
                    ),
                )
                runCurrent()
                val failure = requireNotNull(write.await().exceptionOrNull())
                assertFalse(failure.toString().contains("untrusted-secret"))
                assertEquals(ConversationReadMarks(0uL, null), repo.observeReadMarks("a").first())
                if (code != "conversation.not_found") {
                    assertTrue(failure is RelayErrorException)
                    assertEquals(if (code == "untrusted-secret") "read_mark.failed" else code, (failure as RelayErrorException).code)
                }
            }
            assertTrue(logs.isNotEmpty())
            assertFalse(logs.any { it.contains("untrusted-secret") || it.contains("Name") || it.contains("Project") })
        }

    @Test fun disconnectedSendAndMidAwaitTeardownFailWithoutAdvance() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            pump.connected = false
            assertTrue(repo.markConversationRead("a", 1uL).isFailure)
            pump.connected = true
            val write = backgroundScope.async { repo.markConversationRead("a", 1uL) }
            runCurrent()
            pump.close()
            runCurrent()
            assertTrue(write.await().isFailure)
            assertNull(repo.observeReadMarks("a").first())
        }

    @Test fun callerCancellationPropagatesAndDoesNotRetry() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            val write = backgroundScope.async { repo.markConversationRead("a", 1uL) }
            runCurrent()
            write.cancel()
            runCurrent()
            assertTrue(write.isCancelled)
            assertEquals(1, pump.sent.count { it.type == "mark_conversation_read" })
        }

    @Test fun invariantTwoHostsAndFreshGenerationsNeverShareReadFacts() =
        runTest {
            val a = Pump()
            val b = Pump()
            val repoA = repo(a)
            val repoB = repo(b)
            a.push(snapshot(row("same", "8", "10")))
            b.push(snapshot(row("same", "1", "2")))
            runCurrent()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val stable = StableConversationRepository(current)
            val readings = mutableListOf<ConversationReadMarks?>()
            backgroundScope.launch { stable.observeReadMarks("same").collect { readings += it } }
            runCurrent()
            assertEquals(ConversationReadMarks(8uL, 10uL), readings.last())
            current.value = null
            runCurrent()
            assertNull(readings.last())
            current.value = repoB
            runCurrent()
            a.push(update("same", "9"))
            runCurrent()
            assertEquals(ConversationReadMarks(1uL, 2uL), readings.last())
            val fresh = Pump()
            current.value = repo(fresh)
            runCurrent()
            assertNull(readings.last())
            fresh.push(snapshot(row("same")))
            runCurrent()
            assertNull(readings.last())
            fresh.push(snapshot(row("same", "0", "0")))
            runCurrent()
            assertEquals(ConversationReadMarks(0uL, 0uL), readings.last())
        }

    @Test fun invariantReconnectBetweenEachArrivalAllowsOnlyNewGenerationFacts() =
        runTest {
            val events = listOf(snapshot(row("a", "8", "12")), update("a", "9"), snapshot(row("a", "2", "4")))
            for (split in 1..2) {
                val first = Pump()
                val second = Pump()
                val old = repo(first)
                val current = MutableStateFlow<ConversationRepository?>(old)
                val stable = StableConversationRepository(current)
                for (event in events.take(split)) first.push(event)
                runCurrent()
                assertTrue(requireNotNull(stable.observeReadMarks("a").first()).readUpTo != null)
                first.close()
                current.value = null
                runCurrent()
                assertNull(stable.observeReadMarks("a").first())
                current.value = repo(second)
                runCurrent()
                assertNull(stable.observeReadMarks("a").first())
                for (event in events.drop(split)) second.push(event)
                runCurrent()
                assertEquals(ConversationReadMarks(if (split == 1) 9uL else 2uL, 4uL), stable.observeReadMarks("a").first())
            }
        }

    @Test fun ordinaryCorrelatedUpdatesExposeMarksWithoutClearingLatest() =
        runTest {
            val pump = Pump()
            val repo = repo(pump)
            pump.push(snapshot(row("a", "0", "10")))
            runCurrent()
            val mute = backgroundScope.async { repo.setMuted("a", true) }
            runCurrent()
            pump.push(update("a", "5", pump.sent.last { it.type == "set_conversation_muted" }.id))
            runCurrent()
            mute.await()
            assertEquals(ConversationReadMarks(5uL, 10uL), repo.observeReadMarks("a").first())
            val write = backgroundScope.async { repo.markConversationRead("a", 6uL) }
            runCurrent()
            pump.push(update("a", "8"))
            pump.push(update("a", "6", pump.mark().id))
            runCurrent()
            assertEquals(8uL, write.await().getOrThrow())
            assertEquals(ConversationReadMarks(8uL, 10uL), repo.observeReadMarks("a").first())
        }

    @Test fun stableWriteKeepsOriginalDelegateAndDisconnectedDoesNotSend() =
        runTest {
            val a = Pump()
            val b = Pump()
            val repoA = repo(a)
            val repoB = repo(b)
            val current = MutableStateFlow<ConversationRepository?>(null)
            val stable = StableConversationRepository(current)
            assertTrue(stable.markConversationRead("a", 3uL).isFailure)
            current.value = repoA
            val write = backgroundScope.async { stable.markConversationRead("a", 3uL) }
            runCurrent()
            current.value = repoB
            runCurrent()
            a.push(update("a", "2", a.mark().id))
            runCurrent()
            assertEquals(2uL, write.await().getOrThrow())
            assertTrue(b.sent.none { it.type == "mark_conversation_read" })
            assertNull(stable.observeReadMarks("a").first())
        }

    @Test fun fakeClampsInItsDurableHistorySpaceAndKeepsMarksMonotonic() =
        runTest {
            val fake = FakeConversationRepository()
            val id = "seed-channel-personal"
            val latest =
                fake
                    .requestHistory(id, "", 100)
                    .entries
                    .maxOf { requireNotNull(it.id) }
                    .toULong()
            assertEquals(ConversationReadMarks(0uL, latest), fake.observeReadMarks(id).first())
            assertEquals(1uL, fake.markConversationRead(id, 1uL).getOrThrow())
            assertEquals(latest, fake.markConversationRead(id, ULong.MAX_VALUE).getOrThrow())
            assertEquals(latest, fake.markConversationRead(id, 0uL).getOrThrow())
            assertEquals(ConversationReadMarks(latest, latest), fake.observeReadMarks(id).first())
            assertTrue(fake.markConversationRead("missing", 1uL).isFailure)
            assertNull(fake.observeReadMarks("missing").first())
            val empty = fake.createDiscussion(null)
            assertEquals(0uL, fake.markConversationRead(empty.id, ULong.MAX_VALUE).getOrThrow())
            assertEquals(ConversationReadMarks(0uL, 0uL), fake.observeReadMarks(empty.id).first())
        }

    @Test fun invariantFakeDurableIdsAndReadMarksSurviveStartNewSession() =
        runTest {
            assertFakeDurableHistorySurvivesSessionChange { fake, id -> fake.startNewSession(id) }
        }

    @Test fun invariantFakeDurableIdsAndReadMarksSurviveChangeWorkspace() =
        runTest {
            assertFakeDurableHistorySurvivesSessionChange { fake, id -> fake.changeWorkspace(id, "/new") }
        }

    private suspend fun assertFakeDurableHistorySurvivesSessionChange(
        changeSession: suspend (FakeConversationRepository, String) -> Session,
    ) {
        val fake = FakeConversationRepository()
        val conversation = fake.createDiscussion("/old")
        val id = conversation.id
        val first = fake.sendMessage(id, "first")
        val second = fake.sendMessage(id, "second")
        val originalHistory = fake.requestHistory(id, "", 100).entries
        assertEquals(listOf(2L, 1L), originalHistory.map { it.id })
        assertEquals(
            listOf(second.id, first.id),
            originalHistory.map {
                it.payload.jsonObject["message_id"]
                    ?.jsonPrimitive
                    ?.content
            },
        )
        assertEquals(2uL, fake.markConversationRead(id, 2uL).getOrThrow())

        for (latest in 2uL..3uL) {
            val historyBefore = fake.requestHistory(id, "", 100).entries
            val session = changeSession(fake, id)
            assertEquals(historyBefore, fake.requestHistory(id, "", 100).entries)
            assertEquals(ConversationReadMarks(latest, latest), fake.observeReadMarks(id).first())

            val next = fake.sendMessage(id, "next")
            assertEquals(session.id, next.sessionId)
            val historyAfter = fake.requestHistory(id, "", 100).entries
            val newEntry = historyAfter.first()
            assertEquals((latest + 1uL).toLong(), newEntry.id)
            assertEquals(
                next.id,
                newEntry.payload.jsonObject["message_id"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(historyBefore, historyAfter.drop(1))
            assertEquals(ConversationReadMarks(latest, latest + 1uL), fake.observeReadMarks(id).first())
            assertEquals(latest, fake.markConversationRead(id, 0uL).getOrThrow())
            assertEquals(latest + 1uL, fake.markConversationRead(id, requireNotNull(newEntry.id).toULong()).getOrThrow())
            assertEquals(ConversationReadMarks(latest + 1uL, latest + 1uL), fake.observeReadMarks(id).first())
        }
    }

    @Test fun liveAndReplayDurableReceiptRaisesLatestIndependentlyOfViewport() =
        runTest {
            val pump = Pump()
            val repository = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            pump.push(snapshot(row("a", "0", "0")))
            val direct =
                Envelope(
                    901,
                    "message",
                    TS,
                    MobileJson.parseToJsonElement("""{"conversation_id":"a","message_id":"m","role":"user","text":"received"}"""),
                    eventId = 5001,
                    historyEntryId = 41u,
                )
            pump.push(direct)
            runCurrent()
            val held = repository.observeThreadSnapshot("a").first()
            assertEquals(ConversationReadMarks(0u, 41u), repository.observeReadMarks("a").first())
            assertEquals(41uL, held.readEvidence.checkpoint(held.rows.single(), 0u))
            assertTrue(pump.sent.none { it.type == "mark_conversation_read" || it.type == "request_history" })
            pump.push(direct.copy(id = 902, eventId = 7001))
            runCurrent()
            val replay = repository.observeThreadSnapshot("a").first()
            assertEquals(held.rows, replay.rows)
            assertEquals(41uL, replay.readEvidence.checkpoint(replay.rows.single(), 0u))
            pump.push(
                direct.copy(
                    id = 903,
                    eventId = 7002,
                    historyEntryId = null,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"a","message_id":"n","role":"user","text":"unidentified"}""",
                        ),
                ),
            )
            runCurrent()
            val missing = repository.observeThreadSnapshot("a").first()
            assertNull(missing.readEvidence.checkpoint(missing.rows.last(), 0u))
            assertEquals(41uL, repository.observeReadMarks("a").first()?.latestEntryId)
            assertTrue(pump.sent.none { it.type == "mark_conversation_read" || it.type == "request_history" })
        }

    @Test
    fun nonvisualBannerTailRetainsPresentedStreamingContentClaims() =
        runTest {
            val pump = Pump()
            val repository = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            pump.push(snapshot(row("a", "0", "0")))
            pump.push(
                Envelope(
                    501,
                    "assistant_delta",
                    TS,
                    MobileJson.parseToJsonElement("""{"conversation_id":"a","turn_id":"t","seq":0,"text":"seen"}"""),
                    historyEntryId = 71u,
                ),
            )
            pump.push(
                Envelope(
                    502,
                    "banner",
                    TS,
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"a","level":"info","text":"inert","truncated":false,"stops_turn":false}""",
                    ),
                    historyEntryId = 72u,
                ),
            )
            runCurrent()
            val held = repository.observeThreadSnapshot("a").first()
            val presented = held.rows.filterIsInstance<ThreadItem.MessageItem>().single()
            assertFalse(presented.message.isStreaming)
            assertEquals(72uL, held.readEvidence.checkpoint(presented, 0u))
        }

    @Test
    fun slashCommandMetadataWithoutHistoryIdentityDoesNotBlockReceivedReply() =
        runTest {
            val pump = Pump()
            val repository = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            pump.push(snapshot(row("a", "0", "0")))
            pump.push(
                Envelope(
                    601,
                    "slash_command_list",
                    TS,
                    MobileJson.parseToJsonElement("""{"conversation_id":"a","commands":[],"dropped_commands":0}"""),
                ),
            )
            pump.push(
                Envelope(
                    602,
                    "message",
                    TS,
                    MobileJson.parseToJsonElement("""{"conversation_id":"a","message_id":"m","role":"user","text":"seen"}"""),
                    historyEntryId = 81u,
                ),
            )
            runCurrent()
            val held = repository.observeThreadSnapshot("a").first()
            assertEquals(81uL, held.readEvidence.checkpoint(held.rows.single(), 0u))
        }

    @Test fun liveCompactionEdgesAndBoundaryFillRequireTheirPresentedVersions() =
        runTest {
            val pump = Pump()
            val repository = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            fun push(
                id: ULong,
                type: String,
                payload: String,
            ) {
                pump.push(
                    Envelope(
                        900 + id.toLong(),
                        type,
                        TS,
                        MobileJson.parseToJsonElement(payload),
                        eventId = 7000 + id.toLong(),
                        historyEntryId = id,
                    ),
                )
            }
            push(1u, "message", """{"conversation_id":"a","message_id":"m","role":"user","text":"seen"}""")
            push(2u, "compacting", """{"conversation_id":"a","active":true}""")
            runCurrent()
            val rising = repository.observeThreadSnapshot("a").first()
            assertEquals(2uL, rising.readEvidence.checkpoint(rising.rows.single(), 0u))
            push(3u, "compacting", """{"conversation_id":"a","active":false}""")
            runCurrent()
            val falling = repository.observeThreadSnapshot("a").first()
            assertTrue(falling.rows.last() is ThreadItem.CompactionBoundary)
            assertEquals(2uL, falling.readEvidence.checkpoint(falling.rows.first(), 0u))
            assertEquals(3uL, falling.readEvidence.checkpoint(falling.rows.last(), 0u))
            push(4u, "compaction_boundary", """{"conversation_id":"a","trigger":"auto","pre_tokens":20}""")
            runCurrent()
            val filled = repository.observeThreadSnapshot("a").first()
            assertEquals(4uL, filled.readEvidence.checkpoint(filled.rows.last(), 0u))
            assertNull(filled.readEvidence.checkpoint(falling.rows.last(), 0u))
        }

    @Test fun unsuccessfulLiveTurnEndBindsActualSummaryWithoutGrantingChangedReport() =
        runTest {
            for (fields in listOf("\"is_error\":true", "\"outcome\":\"cancelled\"")) {
                val pump = Pump()
                val repository = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
                pump.push(
                    Envelope(
                        901,
                        "turn_end",
                        TS,
                        MobileJson.parseToJsonElement("""{"conversation_id":"a","turn_id":"t","stop_reason":"end_turn",$fields}"""),
                        historyEntryId = 1u,
                    ),
                )
                runCurrent()
                val snapshot = repository.observeThreadSnapshot("a").first()
                val summary = snapshot.rows.single() as ThreadItem.StoppedTurn
                assertEquals(1uL, snapshot.readEvidence.checkpoint(summary, 0u))
                assertNull(snapshot.readEvidence.checkpoint(summary.copy(reason = "unseen"), 0u))
            }
        }

    @Test fun invariantEachExcludedLiveAndReplayTypeKeepsReceiptWithoutRaisingUnread() =
        runTest {
            for (type in excludedTypes) {
                val pump = Pump()
                val repository = interactiveRepo(pump)
                pump.push(snapshot(row("a", "0", "0")))
                pump.push(content(41u))
                val tail = status(42u, type)
                for (receipt in listOf(tail, tail.copy(id = 999, eventId = 9001))) {
                    pump.push(receipt)
                    runCurrent()
                    assertEquals(type, ConversationReadMarks(0u, 41u), repository.observeReadMarks("a").first())
                    val held = repository.observeThreadSnapshot("a").first()
                    assertTrue("$type retains its durable receipt", 42uL in held.readEvidence.facts)
                    assertTrue(
                        "$type retains the content identity",
                        held.readEvidence.versions.values
                            .any { 41uL in it },
                    )
                    if (type == "session_transition") assertTrue(held.rows.any { it is ThreadItem.SessionBoundary })
                    assertTrue(pump.sent.none { it.type == "mark_conversation_read" })
                }
                pump.push(content(43u))
                pump.push(tail)
                runCurrent()
                assertEquals(type, ConversationReadMarks(0u, 43u), repository.observeReadMarks("a").first())
            }
        }

    @Test fun invariantEachExcludedHistoryTypeAndStatusOnlyPagePreserveOriginalEntries() =
        runTest {
            for (type in excludedTypes) {
                val pump = Pump()
                val repository = interactiveRepo(pump)
                assertTrue(history(repository, pump, emptyList()).entries.isEmpty())
                assertNull(repository.observeReadMarks("a").first())
                val tail = status(42u, type)
                val statusOnly = history(repository, pump, listOf(tail))
                assertEquals(listOf(42uL), statusOnly.entries.map { it.unsignedId })
                assertEquals(type, statusOnly.entries.single().type)
                assertNull("$type cannot invent a latest watermark", repository.observeReadMarks("a").first())
                pump.push(snapshot(row("a", "41", "41")))
                runCurrent()
                repeat(2) {
                    val received = history(repository, pump, listOf(tail, content(41u)))
                    assertEquals(listOf(42uL, 41uL), received.entries.map { it.unsignedId })
                    assertEquals(listOf(tail.payload, content(41u).payload), received.entries.map { it.payload })
                    assertEquals(type, ConversationReadMarks(41u, 41u), repository.observeReadMarks("a").first())
                    assertTrue(
                        42uL in
                            repository
                                .observeThreadSnapshot("a")
                                .first()
                                .readEvidence.facts,
                    )
                }
                history(repository, pump, listOf(content(43u), tail))
                assertEquals(type, ConversationReadMarks(41u, 43u), repository.observeReadMarks("a").first())
            }
        }

    @Test fun invariantStatusTailCannotReturnThroughHistoryAfterReconnectAtAnyReceiptStep() =
        runTest {
            val arrivals = listOf(content(41u), status(42u, "turn_state"), status(43u, "api_retry"))
            for (split in 0..arrivals.size) {
                val oldPump = Pump()
                val old = interactiveRepo(oldPump)
                val current = MutableStateFlow<ConversationRepository?>(old)
                val stable = StableConversationRepository(current)
                oldPump.push(snapshot(row("a", "41", "41")))
                arrivals.take(split).forEach(oldPump::push)
                runCurrent()
                assertEquals(ConversationReadMarks(41u, 41u), stable.observeReadMarks("a").first())
                oldPump.close()
                current.value = null
                runCurrent()
                assertNull(stable.observeReadMarks("a").first())
                val freshPump = Pump()
                val fresh = interactiveRepo(freshPump)
                current.value = fresh
                freshPump.push(snapshot(row("a", "41", "41")))
                arrivals.drop(split).forEach(freshPump::push)
                runCurrent()
                history(fresh, freshPump, arrivals.reversed())
                assertEquals(ConversationReadMarks(41u, 41u), stable.observeReadMarks("a").first())
                history(fresh, freshPump, listOf(content(44u), arrivals.last()))
                assertEquals(ConversationReadMarks(41u, 44u), stable.observeReadMarks("a").first())
                assertTrue(oldPump.sent.none { it.type == "mark_conversation_read" })
            }
        }

    @Test fun invariantReorderedAndOverlappingHistoryCannotRegressReadFactsOrMixIdentities() =
        runTest {
            for (position in 0..2) {
                val pump = Pump()
                val otherHost = Pump()
                val repository = interactiveRepo(pump)
                val other = interactiveRepo(otherHost)
                pump.push(snapshot(row("a", "43", "43"), row("b", "1", "1")))
                otherHost.push(snapshot(row("a", "1", "1")))
                val overlap = mutableListOf(content(41u), status(44u, "turn_state"))
                overlap.add(position, content(43u))
                for (receipt in overlap.reversed() + overlap) pump.push(receipt)
                runCurrent()
                repeat(2) { history(repository, pump, overlap) }
                history(repository, pump, listOf(status(ULong.MAX_VALUE, "turn_state")))
                assertEquals(ConversationReadMarks(43u, 43u), repository.observeReadMarks("a").first())
                assertEquals(ConversationReadMarks(1u, 1u), repository.observeReadMarks("b").first())
                assertEquals(ConversationReadMarks(1u, 1u), other.observeReadMarks("a").first())
            }
        }

    @Test fun invariantEveryOtherDurableTypeIncludingUnknownStillRaisesBothReceiptWatermarks() =
        runTest {
            val types =
                listOf(
                    "message",
                    "assistant_delta",
                    "turn_end",
                    "thinking_progress",
                    "resetting",
                    "banner",
                    "context_usage",
                    "rate_limited",
                    "model_list",
                    "slash_command_list",
                    "mcp_status",
                    "compaction_boundary",
                    "future_type",
                    "Turn_state",
                )
            for (type in types) {
                for (fromHistory in listOf(false, true)) {
                    val pump = Pump()
                    val repository = interactiveRepo(pump)
                    pump.push(snapshot(row("a", "0", "0")))
                    val receipt = content(41u).copy(type = type)
                    if (fromHistory) history(repository, pump, listOf(receipt)) else pump.push(receipt)
                    runCurrent()
                    assertEquals("$type history=$fromHistory", ConversationReadMarks(0u, 41u), repository.observeReadMarks("a").first())
                }
            }
        }

    @Test fun invariantFilteredUnreadDoesNotBypassUnknownMalformedMissingOrGapEvidence() =
        runTest {
            for (fromHistory in listOf(false, true)) {
                for (type in listOf("future_type", "rate_limited", "turn_state", "api_retry", "compacting", "session_transition")) {
                    val pump = Pump()
                    val repository = interactiveRepo(pump)
                    pump.push(snapshot(row("a", "0", "0")))
                    val barrier = content(42u).copy(type = type, payload = MobileJson.parseToJsonElement("""{"conversation_id":"a"}"""))
                    val receipts = listOf(content(41u), barrier, content(43u))
                    if (fromHistory) history(repository, pump, receipts.reversed()) else receipts.forEach(pump::push)
                    runCurrent()
                    val held = repository.observeThreadSnapshot("a").first()
                    assertEquals(ConversationReadMarks(0u, 43u), repository.observeReadMarks("a").first())
                    assertTrue(42uL in held.readEvidence.facts)
                    assertNull(
                        "$type history=$fromHistory",
                        held.readEvidence.checkpoint(held.rows.filterIsInstance<ThreadItem.MessageItem>().last(), 0u),
                    )
                }
                val pump = Pump()
                val repository = interactiveRepo(pump)
                val gapped = listOf(content(41u), content(43u))
                if (fromHistory) history(repository, pump, gapped.reversed()) else gapped.forEach(pump::push)
                runCurrent()
                val gap = repository.observeThreadSnapshot("a").first()
                assertNull(gap.readEvidence.checkpoint(gap.rows.last(), 0u))
                pump.push(content(44u).copy(historyEntryId = null))
                pump.push(status(45u, "turn_state"))
                runCurrent()
                val missing = repository.observeThreadSnapshot("a").first()
                assertNull(missing.readEvidence.checkpoint(missing.rows.last(), 0u))
                assertEquals(43uL, repository.observeReadMarks("a").first()?.latestEntryId)
            }
        }

    private fun TestScope.interactiveRepo(pump: Pump) =
        RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

    private fun content(id: ULong) =
        Envelope(
            901,
            "message",
            TS,
            MobileJson.parseToJsonElement("""{"conversation_id":"a","message_id":"m$id","role":"user","text":"received"}"""),
            eventId = 5001,
            historyEntryId = id,
        )

    private fun status(
        id: ULong,
        type: String,
    ): Envelope {
        val fields =
            when (type) {
                "turn_state" -> "\"state\":\"idle\""
                "api_retry" -> "\"active\":false,\"current\":2,\"total\":10"
                "compacting" -> "\"active\":true"
                "session_transition" ->
                    "\"previous_session_id\":\"s1\",\"new_session_id\":\"s2\",\"reason\":\"clear\"," +
                        "\"occurred_at\":\"$TS\",\"workspace_cwd\":null"
                else -> "\"unused\":false"
            }
        return content(id).copy(type = type, payload = MobileJson.parseToJsonElement("""{"conversation_id":"a",$fields}"""))
    }

    private suspend fun TestScope.history(
        repository: ConversationRepository,
        pump: Pump,
        entries: List<Envelope>,
    ): HistoryPage {
        val request = backgroundScope.async { repository.requestHistory("a", "", 100) }
        runCurrent()
        val rows = entries.joinToString { """{"id":${it.historyEntryId},"type":"${it.type}","ts":"${it.ts}","payload":${it.payload}}""" }
        pump.push(
            Envelope(
                99,
                "history_page",
                TS,
                MobileJson.parseToJsonElement("""{"entries":[$rows],"cursor":"","at_start":true}"""),
                inReplyTo = pump.sent.last { it.type == "request_history" }.id,
            ),
        )
        runCurrent()
        return request.await()
    }

    private val excludedTypes = listOf("turn_state", "stall", "api_retry", "compacting", "session_transition")

    private fun TestScope.repo(pump: Pump) = RemoteConversationRepository(pump, backgroundScope)

    private fun row(
        id: String,
        read: String? = null,
        latest: String? = null,
    ): String =
        """{"id":"$id","name":"Name","is_promoted":true,"cwd":"/p","last_used_at":"$TS",""" +
            """"last_message_ts":"$TS","is_muted":true,"workspace_label":"Project"""" +
            (read?.let { ",\"read_up_to\":$it" } ?: "") + (latest?.let { ",\"latest_entry_id\":$it" } ?: "") + "}"

    private fun snapshot(vararg rows: String) =
        Envelope(98, "conversations", TS, MobileJson.parseToJsonElement("{\"conversations\":[${rows.joinToString()}]}"))

    private fun update(
        id: String,
        read: String?,
        reply: Long? = null,
    ) = Envelope(97, "conversation_updated", TS, MobileJson.parseToJsonElement(row(id, read)), reply)

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.isEmpty()) {
            listOf(emptyList())
        } else {
            items.flatMap { head ->
                permutations(
                    items - head,
                ).map { listOf(head) + it }
            }
        }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var connected = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return connected
        }

        fun push(envelope: Envelope) {
            check(channel.trySend(envelope).isSuccess)
        }

        fun close() {
            channel.close()
        }

        fun mark() = sent.last { it.type == "mark_conversation_read" }
    }

    private companion object {
        const val TS = "2026-10-07T00:00:00Z"
    }
}
