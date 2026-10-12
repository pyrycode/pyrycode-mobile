package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterReplyDiagnosisTest {
    private val prompt = "private prompt"
    private val answer = "private expected answer"

    private fun frame(
        type: String,
        turn: String = "turn",
        text: String = "",
        parent: String = "",
        seq: Int = 0,
    ) = Envelope(
        id = seq.toLong() + 1,
        type = type,
        ts = "2026-10-12T04:00:00Z",
        payload =
            buildJsonObject {
                put("turn_id", turn)
                put("text", text)
                put("parent_tool_use_id", parent)
                put("seq", seq)
                put("role", "user")
                put("message_id", "private-user-id")
            },
    )

    private fun row(
        text: String = answer,
        id: String = "private-row-id",
        parent: String = "",
        streaming: Boolean = false,
    ) = ThreadItem.MessageItem(
        Message(id, "session", Role.Assistant, text, Instant.parse("2026-10-12T04:00:00Z"), streaming, parentToolUseId = parent),
    )

    private fun probe(
        frames: List<Envelope> = emptyList(),
        rows: List<ThreadItem>? = emptyList(),
    ) = LaterReplyProbe(prompt, answer, frames, rows)

    private fun timeoutSnapshot(
        probe: LaterReplyProbe,
        frames: List<Envelope>,
        rows: List<ThreadItem>?,
    ): LaterReplyObservation {
        val timeout = AssertionError("original timeout")
        val readings = mutableListOf<LaterReplyObservation>()
        val failure =
            runCatching {
                observeLaterReply({}, { throw timeout }) { stage ->
                    recordLaterReply(
                        stage,
                        { probe.peer(frames) },
                        { probe.repository(rows) },
                        { LaterReplyComposition(0, 0, 0, null) },
                        readings::add,
                    )
                }
            }.exceptionOrNull()
        assertSame(timeout, failure)
        assertEquals("timeout", readings.last().stage)
        return readings.last()
    }

    @Test fun completedDifferentAnswerIsNotExact() {
        val reading =
            requireNotNull(
                timeoutSnapshot(
                    probe(),
                    listOf(frame("assistant_delta", text = "different"), frame("turn_end")),
                    emptyList(),
                ).peer,
            )
        assertEquals(0, reading.exactTurns.size)
        assertEquals(1, reading.completedDifferentTurns.size)
        assertFalse(reading.toString().contains("different"))
    }

    @Test fun baselineAndChildRepliesDoNotCount() {
        val old = listOf(frame("assistant_delta", "old", answer), frame("turn_end", "old"))
        val snapshot =
            timeoutSnapshot(
                probe(old, listOf(row())),
                old + frame("assistant_delta", "old", answer, seq = 1) +
                    frame("assistant_delta", "child", answer, "agent") + frame("turn_end", "child"),
                listOf(row(), row(parent = "agent", id = "child")),
            )
        assertTrue(requireNotNull(snapshot.peer).exactTurns.isEmpty())
        assertTrue(requireNotNull(snapshot.repository).exactRows.orEmpty().isEmpty())
    }

    @Test fun peerAndRepositoryRemainIndependent() {
        val snapshot =
            timeoutSnapshot(
                probe(),
                listOf(frame("message", text = prompt), frame("assistant_delta", text = answer), frame("turn_end")),
                emptyList(),
            )
        val peer = requireNotNull(snapshot.peer)
        val repo = requireNotNull(snapshot.repository)
        assertEquals(1, peer.exactTurns.size)
        assertEquals(1, peer.deliveredMessages.size)
        assertTrue(repo.exactRows.orEmpty().isEmpty())
        assertFalse(peer.toString().contains(answer))
        assertFalse(peer.toString().contains("private-user-id"))
    }

    @Test fun compositionAbsenceAndOffscreenStayDistinct() {
        val p = probe()
        val repo = p.repository(listOf(row()))
        listOf(
            LaterReplyComposition(0, 0, 0, mapOf(repo.exactRows.orEmpty().single() to 3)),
            LaterReplyComposition(1, 1, 0, mapOf(repo.exactRows.orEmpty().single() to 3)),
        ).forEach { composition ->
            val timeout = AssertionError("original timeout")
            val readings = mutableListOf<LaterReplyObservation>()
            val failure =
                runCatching {
                    observeLaterReply({}, { throw timeout }) { stage ->
                        recordLaterReply(
                            stage,
                            { p.peer(listOf(frame("assistant_delta", text = answer))) },
                            { repo },
                            { composition },
                            readings::add,
                        )
                    }
                }.exceptionOrNull()
            assertSame(timeout, failure)
            assertEquals("timeout", readings.last().stage)
            assertEquals(
                1,
                readings
                    .last()
                    .repository
                    ?.exactRows
                    ?.size,
            )
            assertEquals(composition.exactNodes, readings.last().composition?.exactNodes)
            assertEquals(0, readings.last().composition?.displayedNodes)
            assertEquals(
                3,
                readings
                    .last()
                    .composition
                    ?.rowIndices
                    ?.values
                    ?.single(),
            )
        }
    }

    @Test fun unavailableReadingsStayUnknown() {
        val retained = mutableListOf<LaterReplyObservation>()
        recordLaterReply(
            "timeout",
            { error("private capture error") },
            { null },
            { LaterReplyComposition(null, null, null, null) },
            retained::add,
        )
        assertNull(retained.single().peer)
        assertNull(retained.single().repository)
        assertNull(retained.single().composition?.exactNodes)
        assertFalse(retained.toString().contains("private capture error"))
    }

    @Test fun failureSurvivesCaptureAndCleanupErrors() {
        val original = AssertionError("original timeout")
        val retained = mutableListOf<String>()
        var caught: Throwable? = null
        try {
            observeLaterReply({ retained += "sent" }, { throw original }) { stage ->
                retained += stage
                error("private capture error")
            }
        } catch (failure: Throwable) {
            caught = failure
        }
        val cleaned = mutableListOf<Int>()
        cleanupLaterReply(
            caught,
            listOf({
                cleaned += 1
                error("cleanup")
            }, { cleaned += 2 }),
        )
        assertSame(original, caught)
        assertEquals(listOf("baseline", "sent", "after_send", "timeout"), retained)
        assertEquals(listOf(1, 2), cleaned)
    }

    @Test fun successfulWaitIsNotChangedByDiagnostics() {
        var sends = 0
        var waits = 0
        val retained = mutableListOf<String>()
        observeLaterReply({ sends++ }, { waits++ }) {
            retained += it
            error("capture")
        }
        assertEquals(1, sends)
        assertEquals(1, waits)
        assertEquals(listOf("baseline", "after_send", "success"), retained)
    }

    @Test fun splitDeltasAreExactOnlyWithinOneNewMainTurn() {
        val p = probe()
        val frames =
            listOf(
                frame("assistant_delta", text = answer.take(8)),
                frame("assistant_delta", text = answer.drop(8), seq = 1),
                frame("turn_end"),
            )
        assertEquals(1, p.peer(frames + frames).exactTurns.size)
        assertTrue(p.peer(frames + frame("assistant_delta", text = "extra", seq = 2)).exactTurns.isEmpty())
    }

    @Test fun repositoryCompletionAndMissingBaselineAreExplicit() {
        assertEquals(
            0,
            probe()
                .repository(listOf(row(streaming = true)))
                .completedRows
                .orEmpty()
                .size,
        )
        assertNull(probe(rows = null).repository(listOf(row())).exactRows)
    }

    @Test fun cleanupFailureOnSuccessfulScenarioStillFailsAfterAllCleanup() {
        var closed = false
        val failure = runCatching { cleanupLaterReply(null, listOf({ error("cleanup") }, { closed = true })) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(closed)
    }

    @Test fun exactEvidenceDoesNotTurnTimeoutIntoSuccessAndPartialCaptureSurvives() {
        val cause = IllegalStateException("original cause")
        val timeout = AssertionError("original timeout", cause)
        val readings = mutableListOf<LaterReplyObservation>()
        val p = probe()
        val failure =
            runCatching {
                observeLaterReply({}, { throw timeout }) { stage ->
                    recordLaterReply(
                        stage,
                        { p.peer(listOf(frame("assistant_delta", text = answer), frame("turn_end"))) },
                        { error("private repository error") },
                        { LaterReplyComposition(1, 1, 0, null) },
                        readings::add,
                    )
                }
            }.exceptionOrNull()
        cleanupLaterReply(failure, listOf({ error("private cleanup error") }))
        assertSame(timeout, failure)
        assertSame(cause, failure?.cause)
        assertEquals(listOf("baseline", "after_send", "timeout"), readings.map { it.stage })
        assertEquals(
            1,
            readings
                .last()
                .peer
                ?.exactTurns
                ?.size,
        )
        assertNull(readings.last().repository)
        assertEquals(0, readings.last().composition?.displayedNodes)
        assertFalse(readings.toString().contains(answer))
    }

    @Test fun malformedReadAndMissingSequencesCannotClaimExactAnswer() {
        val p = probe()
        val missing = p.peer(listOf(frame("assistant_delta", text = answer, seq = 1), frame("turn_end")))
        assertTrue(missing.exactTurns.isEmpty())
        assertTrue(missing.completedDifferentTurns.isEmpty())
        val bad = frame("assistant_delta", text = answer).copy(ts = "private host text")
        assertFalse(p.peer(listOf(bad)).toString().contains("private host text"))
        assertTrue(
            p.peer(listOf(frame("assistant_delta", text = answer), frame("assistant_delta", text = "conflict"))).exactTurns.isEmpty(),
        )
    }

    @Test fun malformedParentCannotBeAssumedToBeMainLane() {
        val delta =
            frame("assistant_delta", text = answer).copy(
                payload =
                    buildJsonObject {
                        put("turn_id", "turn")
                        put("seq", 0)
                        put("text", answer)
                        put("parent_tool_use_id", buildJsonObject { put("private", "untrusted") })
                    },
            )
        val retained = mutableListOf<LaterReplyObservation>()
        recordLaterReply("timeout", { probe().peer(listOf(delta)) }, { null }, { null }, retained::add)
        assertNull(retained.single().peer)
    }

    @Test fun unreadableRowsDoNotEraseObservableQueueAndPhase() {
        val retained = mutableListOf<LaterReplyObservation>()
        val reading = probe().repository(null).copy(queuedMessages = listOf(laterReplyHash("queued-id")), phase = "Thinking")
        recordLaterReply("timeout", { null }, { reading }, { null }, retained::add)
        assertNull(retained.single().repository?.rows)
        assertNull(retained.single().repository?.exactRows)
        assertEquals(
            1,
            retained
                .single()
                .repository
                ?.queuedMessages
                ?.size,
        )
        assertEquals("Thinking", retained.single().repository?.phase)
    }
}
