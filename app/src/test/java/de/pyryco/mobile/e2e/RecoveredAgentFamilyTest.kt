package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSupport
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
            val settled = current.settledAgentAfterRecovery(launched, captured, "after1783", ThreadSupport.Supported(connection))
            assertNotNull("the admitted same family must survive a generation reset", settled)
            assertTrue(current.settledAgentPrecedes(current.owner, requireNotNull(settled), "after1783"))
            if (generation != launched.owner.generation) {
                assertNotEquals(captured.agentId, settled.agentId)
                assertFalse(current.settledAgentPrecedes(launched.owner, captured, "after1783"))
            }
        }
    }

    @Test fun certifiedCurrentConnectionReconcilesTheCapturedFamilyAfterProducerGap() {
        val launched = evidence(snapshot(false))
        val captured = requireNotNull(launched.agentFamily(launched.owner))
        val replacement = Any()
        val current = evidence(snapshot(true).copy(generation = launched.owner.generation!! + 1), source = replacement)
        assertFalse("ordinary owner matching must still reject the retired connection", current.owner.matches(launched.owner))
        assertFalse(current.settledAgentPrecedes(launched.owner, captured, "after1783"))
        assertNotNull(
            "certified current facts must reconcile the durable family after connection replacement",
            current.settledAgentAfterRecovery(launched, captured, "after1783", ThreadSupport.Supported(replacement)),
        )
    }

    @Test fun recoveryRequiresExplicitCurrentSupportAndRejectsRetiredOrForeignEvidence() {
        val launched = evidence(snapshot(false))
        val captured = requireNotNull(launched.agentFamily(launched.owner))
        val replacement = Any()
        val supported = ThreadSupport.Supported(replacement)
        val finished = snapshot(true).copy(generation = launched.owner.generation!! + 1)
        val current = evidence(finished, source = replacement)
        for (support in listOf(ThreadSupport.Unverified, ThreadSupport.Missing(replacement), ThreadSupport.Supported(connection))) {
            assertNull(current.settledAgentAfterRecovery(launched, captured, "after1783", support))
        }
        val foreign =
            listOf(
                evidence(finished),
                evidence(finished, source = Any()),
                evidence(finished, host = "foreign", source = replacement),
                evidence(finished, conversation = "foreign", source = replacement),
            )
        foreign.forEach { assertNull(it.settledAgentAfterRecovery(launched, captured, "after1783", supported)) }
        assertNotNull(current.settledAgentAfterRecovery(launched, captured, "after1783", supported))
        val foreignLaunch = evidence(snapshot(false), host = "foreign")
        assertNull(current.settledAgentAfterRecovery(foreignLaunch, captured, "after1783", supported))
        assertNull(
            current.settledAgentAfterRecovery(
                launched,
                requireNotNull(foreignLaunch.agentFamily(foreignLaunch.owner)),
                "after1783",
                supported,
            ),
        )
    }

    @Test fun recoveryRequiresBoundSnapshotOwnershipAndGenerationForDocumentReplacement() {
        val launched = evidence(snapshot(false).copy(incarnation = "launch-document"))
        val captured = requireNotNull(launched.agentFamily(launched.owner))
        val sameGeneration = snapshot(true).copy(incarnation = "replacement-document")
        val advanced = sameGeneration.copy(generation = launched.owner.generation!! + 1)
        val support = ThreadSupport.Supported(connection)
        assertNull(evidence(sameGeneration).settledAgentAfterRecovery(launched, captured, "after1783", support))
        assertNotNull(evidence(advanced).settledAgentAfterRecovery(launched, captured, "after1783", support))
        val current = evidence(advanced)
        for (owner in listOf(current.owner.copy(epoch = "foreign"), current.owner.copy(generation = advanced.generation - 1))) {
            val unbound = ThreadEvidence(owner, current.records, sourceSnapshot = advanced)
            assertNull(unbound.settledAgentAfterRecovery(launched, captured, "after1783", support))
        }
        val unboundLaunch =
            ThreadEvidence(launched.owner.copy(epoch = "foreign"), launched.records, sourceSnapshot = launched.sourceSnapshot)
        assertNull(current.settledAgentAfterRecovery(unboundLaunch, captured, "after1783", support))
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
            assertNull(
                "foreign or damaged candidate $index",
                reading.settledAgentAfterRecovery(launched, captured, "after1783", ThreadSupport.Supported(connection)),
            )
        }
    }

    private fun streamedSnapshot(
        finished: Boolean,
        text: String = if (finished) "child1827-beforechild1827-after" else "child1827-before",
    ): AuthoritativeThreadSnapshot {
        val base = snapshot(finished)
        val fields = base.items.first { it.id == 3L }.content as JsonObject
        val content = JsonObject(fields + ("text" to JsonPrimitive(text)))
        val changed =
            base
                .change(3, "content", content)
                .change(3, "active", JsonPrimitive(!finished))
                .change(3, "status", JsonPrimitive(if (finished) "interrupted" else "running"))
                .change(3, "rev", JsonPrimitive(if (finished) 20 else 3))
        return changed.copy(
            generation = if (finished) base.generation + 1 else base.generation,
            items = changed.items.filter { it.id != 9L },
        )
    }

    @Test fun recoveryRetainsThePrefixOfItsActuallyStreamingOwnedParagraph() {
        val launched = evidence(streamedSnapshot(false))
        val captured = requireNotNull(launched.agentFamily(launched.owner))
        val current = evidence(streamedSnapshot(true))
        assertNotNull(
            "a newer terminal revision may append to the captured active paragraph",
            current.settledAgentAfterRecovery(launched, captured, "after1783", ThreadSupport.Supported(connection)),
        )
        for (bad in listOf(
            evidence(streamedSnapshot(true, "different child1827-after")),
            evidence(streamedSnapshot(true).change(3, "rev", JsonPrimitive(3))),
            evidence(streamedSnapshot(true).change(3, "active", JsonPrimitive(true))),
        )) {
            assertNull(bad.settledAgentAfterRecovery(launched, captured, "after1783", ThreadSupport.Supported(connection)))
        }
        val completedBefore = evidence(snapshot(false))
        val completedFamily = requireNotNull(completedBefore.agentFamily(completedBefore.owner))
        assertNull(current.settledAgentAfterRecovery(completedBefore, completedFamily, "after1783", ThreadSupport.Supported(connection)))
    }

    @Test fun anOwnedFragmentCanShareAParagraphButCannotBeMissingOrDuplicated() {
        val current = evidence(streamedSnapshot(true))
        val family = requireNotNull(current.agentFamily(current.owner))
        assertTrue("the owned terminal fragment may append to earlier owned prose", family.ownsSingleProseFragment("child1827-after"))
        assertFalse(family.ownsSingleProse("child1827-after"))
        for (text in listOf("child1827-before", "child1827-beforechild1827-afterchild1827-after")) {
            val bad = evidence(streamedSnapshot(true, text))
            assertFalse(requireNotNull(bad.agentFamily(bad.owner)).ownsSingleProseFragment("child1827-after"))
        }
        assertFalse(family.ownsSingleProseFragment(""))
    }
}
