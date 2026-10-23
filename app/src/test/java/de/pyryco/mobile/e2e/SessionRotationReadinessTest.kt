package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRotationReadinessTest {
    private val connection = Any()
    private val snapshot =
        AuthoritativeThreadSnapshot(
            epoch = "epoch",
            generation = 2,
            completedVersion = 81,
            checkpoint = 81,
            items = listOf(divider()),
        )

    private fun reading(value: AuthoritativeThreadSnapshot = snapshot) = authoritativeThreadEvidence("host", "chat", connection, value)

    @Test fun currentCertifiedRotationNamesTheNextSession() {
        val evidence = reading()
        assertEquals("session-b", evidence.certifiedNextSession("session-a", reading()))
    }

    @Test fun visibleDividerCannotReplaceItsMissingTerminal() {
        for (checkpoint in listOf(0L, 77L, 80L)) {
            val evidence = reading(snapshot.copy(checkpoint = checkpoint))
            assertNull(evidence.certifiedNextSession("session-a", evidence))
        }
    }

    @Test fun resetRepairAndUnadmittedReadingsCannotCertifyRotation() {
        for (value in listOf(snapshot.copy(repairNeeded = true), snapshot.copy(resetNeeded = true), snapshot.copy(epoch = null))) {
            val evidence = reading(value)
            assertNull(evidence.certifiedNextSession("session-a", evidence))
        }
        val evidence = reading()
        val unadmitted = ThreadEvidence(evidence.owner, evidence.records, admitted = false, sourceSnapshot = snapshot)
        assertNull(unadmitted.certifiedNextSession("session-a", evidence))
        assertNull(evidence.certifiedNextSession("session-a", unadmitted))
    }

    @Test fun aRetainedSnapshotCannotCertifyTheCurrentReading() {
        val stale = reading()
        val current = reading(snapshot.copy(completedVersion = 84, checkpoint = 84))
        assertNull(stale.certifiedNextSession("session-a", current))
        // Even equal facts belong to the current publication, not a retained copy.
        assertNull(stale.certifiedNextSession("session-a", reading(snapshot.copy())))
    }

    @Test fun foreignOwnersCannotCertifyRotation() {
        val evidence = reading()
        val owner = evidence.owner
        for (foreign in listOf(
            owner.copy(host = "other"),
            owner.copy(conversation = "other"),
            owner.copy(connection = Any()),
            owner.copy(epoch = "other"),
            owner.copy(generation = 3),
            owner.copy(kind = ThreadEvidenceKind.Legacy),
        )) {
            val current = ThreadEvidence(foreign, emptyList(), sourceSnapshot = snapshot)
            assertNull(evidence.certifiedNextSession("session-a", current))
            assertNull(current.certifiedNextSession("session-a", evidence))
        }
    }

    @Test fun receiptMustDescribeItsOwnSnapshotEpochAndGeneration() {
        val evidence = reading()
        for (owner in listOf(evidence.owner.copy(epoch = "old"), evidence.owner.copy(generation = 1))) {
            val inconsistent = ThreadEvidence(owner, emptyList(), sourceSnapshot = snapshot)
            assertNull(inconsistent.certifiedNextSession("session-a", inconsistent))
        }
        val missing = ThreadEvidence(evidence.owner, emptyList())
        assertNull(missing.certifiedNextSession("session-a", missing))
    }

    @Test fun unrelatedSelfAndAmbiguousDividersCannotSupplyTheNextSession() {
        for (items in listOf(
            emptyList(),
            listOf(divider(previous = "other")),
            listOf(divider(next = "session-a")),
            listOf(divider(), divider(id = 79, next = "session-c")),
            listOf(divider(next = "session-a"), divider(id = 79)),
        )) {
            val evidence = reading(snapshot.copy(items = items))
            assertNull(evidence.certifiedNextSession("session-a", evidence))
        }
    }

    private fun divider(
        id: Long = 78,
        previous: String = "session-a",
        next: String = "session-b",
    ): ThreadItemDto =
        ThreadItemDto.decode(
            MobileJson
                .parseToJsonElement(
                    """{"id":$id,"kind":"session_divider","order":$id,"rev":$id,"session":"session-a","status":"done","active":false,"shown":true,"summary":"","content":{"cause":"operator_reset","previous_session_id":"$previous","new_session_id":"$next"}}""",
                ).jsonObject,
        )
}
