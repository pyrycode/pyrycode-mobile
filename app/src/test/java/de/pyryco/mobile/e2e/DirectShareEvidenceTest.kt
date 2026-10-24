package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShareEvidenceTest {
    private val original = Any()
    private val replacement = Any()
    private val empty = AuthoritativeThreadSnapshot(epoch = "epoch", generation = 1, incarnation = "document")

    private fun read(
        snapshot: AuthoritativeThreadSnapshot = empty,
        connection: Any = original,
        host: String = "host",
        conversation: String = "chat",
    ) = authoritativeThreadEvidence(host, conversation, connection, snapshot)

    private fun ThreadEvidence.current(
        connection: Any? = original,
        supported: Any? = connection,
        snapshot: AuthoritativeThreadSnapshot = empty,
    ) = isCurrentDirectShareEvidence("host", "chat", connection, supported, snapshot)

    @Test fun replacementUsesItsOwnAdmittedOwnerInsteadOfThePreSendConnection() {
        val before = read()
        assertTrue(before.current())
        assertTrue(before.holdsNoUserMessage())
        val delivered = empty.copy(items = listOf(user()), checkpoint = 1)
        val after = read(delivered, replacement)
        assertFalse(before.current(replacement, snapshot = delivered))
        assertTrue(after.current(replacement, snapshot = delivered))
        assertNull(after.ownedUserEcho(before.owner, PROMPT))
        assertNotNull(after.ownedUserEcho(after.owner, PROMPT))
    }

    @Test fun retiredConnectionOrUnsupportedRouteCannotSupplyEvenAnEmptyThread() {
        val held = read()
        assertFalse(held.current(connection = null))
        assertFalse(held.current(replacement))
        assertFalse(held.current(supported = null))
        assertFalse(held.current(supported = replacement))
    }

    @Test fun unadmittedWrongTargetAndStaleSnapshotsCannotPass() {
        for (snapshot in listOf(empty.copy(epoch = null), empty.copy(repairNeeded = true), empty.copy(resetNeeded = true))) {
            val evidence = read(snapshot)
            assertFalse(evidence.current(snapshot = snapshot))
            assertFalse(evidence.holdsNoUserMessage())
        }
        assertFalse(read(host = "other").current())
        assertFalse(read(conversation = "other").current())
        // Equal contents still cannot certify an observation from an older store publication.
        assertFalse(read().current(snapshot = empty.copy()))
        assertFalse(read().current(snapshot = empty.copy(generation = 2, incarnation = "replacement")))
    }

    @Test fun currentSourceStillRequiresExactlyOneDeliveredExactUserEcho() {
        for (items in listOf(
            emptyList(),
            listOf(user(text = "other")),
            listOf(user(status = "queued")),
            listOf(user(), user(id = 2)),
        )) {
            val snapshot = empty.copy(items = items, checkpoint = 2)
            val evidence = read(snapshot, replacement)
            assertTrue(evidence.current(replacement, snapshot = snapshot))
            assertNull(evidence.ownedUserEcho(evidence.owner, PROMPT))
        }
    }

    private fun user(
        id: Long = 1,
        text: String = PROMPT,
        status: String = "delivered",
    ) = ThreadItemDto.decode(
        buildJsonObject {
            put("id", id)
            put("rev", id)
            put("kind", "user_message")
            put("order", id)
            put("session", "session")
            put("turn", "turn")
            put("status", status)
            put("active", false)
            put("shown", true)
            put("summary", "fixture")
            put("content", buildJsonObject { put("text", text) })
        },
    )

    private companion object {
        const val PROMPT = "Shared fixture prompt"
    }
}
