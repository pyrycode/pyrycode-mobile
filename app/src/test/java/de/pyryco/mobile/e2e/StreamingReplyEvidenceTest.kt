package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
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

/** The scripted streaming, reopen and Direct Share assertions (#2171), driven with daemon-built facts. */
class StreamingReplyEvidenceTest {
    private val connection = Any()

    private fun item(
        id: Long = 2,
        rev: Long = 1,
        kind: String = "assistant_message",
        text: String = PREFIX,
        active: Boolean = true,
        status: String = "running",
        ordered: Boolean = true,
    ) = ThreadItemDto.decode(
        buildJsonObject {
            put("id", id)
            put("rev", rev)
            put("kind", kind)
            if (ordered) put("order", id)
            put("session", "session")
            put("turn", "turn")
            put("agent", "claude")
            put("status", status)
            put("active", active)
            put("shown", true)
            put("summary", "fallback")
            put("content", buildJsonObject { put("text", text) })
        },
    )

    private fun user(
        text: String = PROMPT,
        status: String = "delivered",
        id: Long = 1,
    ) = item(id, kind = "user_message", text = text, active = false, status = status)

    private fun done(
        text: String = REPLY,
        rev: Long = 3,
    ) = item(rev = rev, text = text, active = false, status = "done")

    private fun read(
        vararg items: ThreadItemDto,
        host: String = "host",
        token: Any = connection,
        generation: Long = 3,
    ) = authoritativeThreadEvidence(host, "chat", token, AuthoritativeThreadSnapshot("epoch", items.toList(), generation = generation))

    private fun legacy(vararg messages: Message) =
        legacyThreadEvidence(null, "chat", connection, messages.map { ThreadItem.MessageItem(it) })

    private fun legacyReply(
        text: String,
        streaming: Boolean,
        vararg lengths: Int,
    ) = Message(
        "turn",
        "",
        Role.Assistant,
        text,
        Instant.fromEpochMilliseconds(0),
        streaming,
        segment = AssistantSegment("turn", lengths.mapIndexed { seq, length -> SegmentDelta(seq, length) }),
    )

    @Test fun authoritativeReplyAdvancesByRevisionAndSettlesOnItsOwnFacts() {
        val first = read(user(), item())
        val held = requireNotNull(first.openReply(first.owner))
        assertEquals(PREFIX, held.fullText)
        assertNull(held.message.segment)
        val grown = read(user(), item(rev = 2, text = REPLY))
        val open = requireNotNull(grown.openReply(first.owner))
        assertTrue(open.advancesFrom(held))
        assertNull(grown.settledFormOf(first.owner, held))
        val settled = requireNotNull(read(user(), done()).settledFormOf(first.owner, held))
        assertEquals(REPLY, settled.fullText)
        assertFalse(settled.isOpen)
        assertTrue(settled.advancesFrom(held))
        assertNull(settled.message.segment)
        // Growth needs a later admitted revision of the same item; equal text or a rewrite is not an advance.
        assertFalse(read(item(rev = 1, text = REPLY)).records.single().advancesFrom(held))
        assertFalse(read(item(rev = 2)).records.single().advancesFrom(held))
        assertFalse(read(item(rev = 2, text = "Rewritten reply")).records.single().advancesFrom(held))
        assertFalse(read(item(id = 3, rev = 2, text = REPLY)).records.single().advancesFrom(held))
    }

    @Test fun collidingHostIdentityCannotAdvanceOrSettleTheOwnedReply() {
        val owned = read(item())
        val held = requireNotNull(owned.openReply(owned.owner))
        val other = read(item(rev = 2, text = REPLY), host = "other")
        assertEquals(
            held.fact?.id,
            other.records
                .single()
                .fact
                ?.id,
        )
        assertNull(other.openReply(owned.owner))
        assertFalse(other.records.single().advancesFrom(held))
        val otherDone = read(user(), done(), host = "other")
        assertNull(otherDone.settledFormOf(owned.owner, held))
        assertNull(otherDone.ownedUserEcho(owned.owner, PROMPT))
        assertNotNull(otherDone.ownedUserEcho(otherDone.owner, PROMPT))
    }

    @Test fun retiredConnectionEvidenceCannotSatisfyOpenEchoOrSettlement() {
        val current = read(user(), item())
        val held = requireNotNull(current.openReply(current.owner))
        for (retired in listOf(read(user(), done(), token = Any()), read(user(), done(), generation = 4))) {
            assertNull(retired.openReply(current.owner))
            assertNull(retired.ownedUserEcho(current.owner, PROMPT))
            assertNull(retired.settledFormOf(current.owner, held))
            assertNotNull(retired.settledFormOf(retired.owner, retired.records.last()))
        }
    }

    @Test fun incompleteOrActiveRepliesNeverSettle() {
        val held = requireNotNull(read(item()).let { it.openReply(it.owner) })
        val owner = read(item()).owner
        for (incomplete in listOf(
            item(rev = 3, text = REPLY),
            item(rev = 3, text = REPLY, active = false),
            item(rev = 3, text = REPLY, status = "done"),
            item(rev = 3, text = REPLY, active = false, status = "running"),
            item(rev = 3, text = " ", active = false, status = "done"),
        )) {
            assertNull(read(incomplete).settledFormOf(owner, held))
        }
        val unadmitted = authoritativeThreadEvidence("host", "chat", connection, AuthoritativeThreadSnapshot(null, listOf(done())))
        assertNull(unadmitted.settledFormOf(unadmitted.owner, held))
        assertNull(unadmitted.openReply(unadmitted.owner))
        assertTrue(unadmitted.assistantRecords().isEmpty())
        val repair =
            authoritativeThreadEvidence(
                "host",
                "chat",
                connection,
                AuthoritativeThreadSnapshot("epoch", listOf(done()), repairNeeded = true),
            )
        assertNull(repair.settledFormOf(repair.owner, held))
        // A completed item is not the open reply the display checkpoints require.
        assertNull(read(done()).let { it.openReply(it.owner) })
    }

    @Test fun reopenedPrefixStaysTheOneOpenReply() {
        val first = read(user(), item())
        val arrived = requireNotNull(first.openReply(first.owner))
        val reopened = read(user(), item())
        assertSame(reopened.records.last(), reopened.openReply(first.owner))
        assertEquals(arrived.message.id, reopened.openReply(first.owner)?.message?.id)
        assertNull(read(user(), item(), item(id = 3)).openReply(first.owner))
        assertNull(read(user(), item(text = " ")).openReply(first.owner))
        assertEquals(1, read(user(), done()).assistantRecords().count { it.fullText == REPLY && !it.isOpen })
    }

    @Test fun directShareRequiresNoUserBeforeAndExactlyOneOwnedEchoAfter() {
        val before = read()
        assertTrue(before.holdsNoUserMessage())
        assertFalse(read(user(status = "failed")).holdsNoUserMessage())
        assertFalse(authoritativeThreadEvidence("host", "chat", connection, AuthoritativeThreadSnapshot()).holdsNoUserMessage())
        val after = read(user(), item())
        assertEquals(PROMPT, after.ownedUserEcho(before.owner, PROMPT)?.fullText)
        assertNull(read(user(status = "failed")).ownedUserEcho(before.owner, PROMPT))
        assertNull(read(user(), user(id = 3)).ownedUserEcho(before.owner, PROMPT))
        assertNull(read(user(), user("other", id = 3)).ownedUserEcho(before.owner, PROMPT))
        assertNull(read(user("hello again")).ownedUserEcho(before.owner, PROMPT))
        val legacyBefore = legacy()
        assertTrue(legacyBefore.holdsNoUserMessage())
        val echo = legacyReply(PROMPT, false).copy(id = "user", role = Role.User, segment = null)
        assertSame(echo, legacy(echo).ownedUserEcho(legacyBefore.owner, PROMPT)?.message)
        assertNull(legacy(echo, echo.copy(id = "again")).ownedUserEcho(legacyBefore.owner, PROMPT))
    }

    @Test fun legacySettlementKeepsDeltaSequenceChecks() {
        val first = legacy(legacyReply(PREFIX, true, 7, 9))
        val held = requireNotNull(first.openReply(first.owner))
        assertNull(legacy(legacyReply(REPLY, true, 7, 9, 5)).settledFormOf(first.owner, held))
        val settled = requireNotNull(legacy(legacyReply(REPLY, false, 7, 9, 5)).settledFormOf(first.owner, held))
        assertTrue(settled.advancesFrom(held))
        for (broken in listOf(
            legacyReply(REPLY, false, 7, 9),
            legacyReply(REPLY, false).copy(segment = AssistantSegment("turn", listOf(SegmentDelta(0, 7), SegmentDelta(2, 14)))),
            legacyReply(REPLY, false, 7, 9, 5).copy(segment = null),
        )) {
            assertNull(legacy(broken).settledFormOf(first.owner, held))
        }
        assertFalse(legacy(legacyReply(PREFIX, true, 7, 9).copy(id = "other")).records.single().advancesFrom(held))
    }

    private companion object {
        const val PROMPT = "hello"
        const val PREFIX = "Hello, streamed "
        const val REPLY = "Hello, streamed world"
    }
}
