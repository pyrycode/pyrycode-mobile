package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredAgentFamilyTest {
    private val connection = Any()

    private fun snapshot(finished: Boolean): AuthoritativeThreadSnapshot {
        val facts = if (finished) finishedAgentScenarioItems() else runningAgentScenarioItems()
        val identified =
            facts.map { fact ->
                if (fact.kind !in setOf("agent", "tool_call")) return@map fact
                val content =
                    JsonObject(
                        (fact.content as JsonObject) +
                            mapOf(
                                "tool_use_id" to JsonPrimitive("tool-${fact.id}"),
                                "input" to JsonObject(mapOf("description" to JsonPrimitive("input-${fact.id}"))),
                            ),
                    )
                ThreadItemDto.decode(JsonObject(fact.raw + ("content" to content)))
            }
        return scenarioSnapshot(identified).copy(
            checkpoint = 100,
            completedVersion = 100,
            coverage = AuthoritativeThreadCoverage(1uL, 12uL, 0uL, false),
        )
    }

    private fun evidence(
        snapshot: AuthoritativeThreadSnapshot,
        host: String = "host",
        conversation: String = "chat",
        source: Any = connection,
    ) = authoritativeThreadEvidence(host, conversation, source, snapshot)

    private fun AuthoritativeThreadSnapshot.change(
        id: Long,
        field: String,
        value: kotlinx.serialization.json.JsonElement,
    ) = copy(items = items.map { if (it.id == id) ThreadItemDto.decode(JsonObject(it.raw + (field to value))) else it })

    @Test fun certifiedResetRebindsOnlyTheSameCapturedFamily() {
        val launched = evidence(snapshot(false))
        val captured = requireNotNull(launched.agentFamily(launched.owner))
        for (generation in listOf(launched.owner.generation!!, launched.owner.generation!! + 1)) {
            val current = evidence(snapshot(true).copy(generation = generation))
            val settled = current.settledAgentAfterRecovery(launched, captured, "after1783")
            assertNotNull("the admitted same family must survive a generation reset", settled)
            assertTrue(current.settledAgentPrecedes(current.owner, requireNotNull(settled), "after1783"))
            if (generation != launched.owner.generation) {
                assertNotEquals(captured.agentId, settled.agentId)
                assertFalse(current.settledAgentPrecedes(launched.owner, captured, "after1783"))
            }
        }
    }

    @Test fun foreignOrUncertifiedRecoveryCannotReuseCapturedFactIds() {
        val launched = evidence(snapshot(false))
        val captured = requireNotNull(launched.agentFamily(launched.owner))
        val current = snapshot(true).copy(generation = launched.owner.generation!! + 1)
        val child = requireNotNull(current.items.first { it.id == 6L }.content as? JsonObject)
        val changedInput = JsonObject(child + ("input" to JsonObject(mapOf("description" to JsonPrimitive("different")))))
        val prose = current.items.first { it.id == 3L }.content as JsonObject
        val changedProse = JsonObject(prose + ("text" to JsonPrimitive("different")))
        val bad =
            listOf(
                evidence(current, host = "other"),
                evidence(current, conversation = "other"),
                evidence(current, source = Any()),
                evidence(current.copy(epoch = "other")),
                evidence(current.copy(generation = launched.owner.generation!! - 1)),
                evidence(current.copy(repairNeeded = true)),
                evidence(current.copy(resetNeeded = true)),
                evidence(current.copy(coverage = null)),
                evidence(current.copy(checkpoint = 1)),
                evidence(current.copy(coverage = AuthoritativeThreadCoverage(4uL, 12uL, 0uL, false))),
                evidence(current.change(2, "session", JsonPrimitive("other"))),
                evidence(current.change(2, "turn", JsonPrimitive("other"))),
                evidence(current.change(6, "parent", JsonPrimitive(99))),
                evidence(current.change(6, "order", JsonPrimitive(13))),
                evidence(current.change(6, "rev", JsonPrimitive(1))),
                evidence(current.change(6, "content", changedInput)),
                evidence(current.change(3, "content", changedProse)),
                evidence(current.copy(items = current.items.filter { it.id != 7L })),
                evidence(current.copy(items = current.items.filter { it.id != 10L })),
                evidence(snapshot(false)),
            )
        bad.forEachIndexed { index, reading ->
            assertNull("foreign or damaged candidate $index", reading.settledAgentAfterRecovery(launched, captured, "after1783"))
        }
    }
}
