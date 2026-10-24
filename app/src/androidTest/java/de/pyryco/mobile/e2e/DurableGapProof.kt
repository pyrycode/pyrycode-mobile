package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.rules.ActivityScenarioRule
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.recording.RecordedSuiteNative
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.ownedReplyMatcher
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext

/** Shared rung-3/4 proof; all rows and requests come from the app's existing repository tap. */
internal class DurableGapProof(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    private val serverId: String,
    private val conversationId: String,
) {
    private val cache get() = GlobalContext.get().get<ConversationCache>()
    private val fault = DaemonFaultControl()
    private var authoritativeBaseline: AuthoritativeThreadSnapshot? = null
    private var finalAuthoritative: ThreadEvidence? = null
    val prefix = "e2e1833-" + RecordedSuiteNative.uniqueStamp()
    val olderPost = "$prefix-a-000"
    private val batchSize = 120
    val lastOlderPost = "$prefix-a-${(batchSize - 1).toString().padStart(3, '0')}"
    val newestPost = "$prefix-b-${(batchSize - 1).toString().padStart(3, '0')}"

    fun cacheBaseline() {
        val bundle = checkNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(serverId))
        val repository = runBlocking { withTimeout(30_000) { bundle.coordinator.currentRepository.first { it != null } } }
        check(harnessThreadEvidence(checkNotNull(repository)).kind == ThreadEvidenceKind.Authoritative)
        run {
            runBlocking {
                withTimeout(30_000) {
                    while (true) {
                        val saved = cache.readAuthoritativeThread(serverId, conversationId)?.toSnapshot()
                        if (saved != null &&
                            saved.checkpoint > 0 &&
                            saved.checkpoint >= saved.completedVersion &&
                            saved.coverage != null &&
                            !saved.repairNeeded &&
                            !saved.resetNeeded &&
                            saved.items.any { it.kind == "assistant_message" && !it.active && it.status == "done" }
                        ) {
                            check(bundle.coordinator.currentRepository.value === repository) { "durable baseline source changed" }
                            authoritativeBaseline = saved
                            return@withTimeout
                        }
                        delay(50)
                    }
                }
            }
            return
        }
    }

    fun missPages(
        name: String,
        completedReply: () -> Unit,
    ) {
        fault.posts(name, "$prefix-a", batchSize)
        completedReply()
        fault.posts(name, "$prefix-b", batchSize)
        // The same durable home survives, but a fresh process cannot replay the missing ring.
        fault.stop()
        fault.start()
        AuthoritativeHistoryProbe.begin(serverId, conversationId, checkNotNull(authoritativeBaseline))
    }

    fun catchUp(
        reconnect: () -> Unit,
        promptText: String,
        reply: ProducedReplyEvidence,
        cachedRows: List<SemanticsMatcher>,
    ) {
        checkNotNull(authoritativeBaseline)
        catchUpAuthoritative(reconnect, promptText, reply)
    }

    private fun catchUpAuthoritative(
        reconnect: () -> Unit,
        promptText: String,
        reply: ProducedReplyEvidence,
    ) {
        val baseline = checkNotNull(authoritativeBaseline)
        val expected =
            (0 until batchSize).map { "$prefix-a-${it.toString().padStart(3, '0')}" } +
                (0 until batchSize).map { "$prefix-b-${it.toString().padStart(3, '0')}" }
        Log.i(
            "DurableGapProof",
            durableGapDiagnostic(
                DurableGapCheck.Baseline,
                true,
                baseline.generation,
                baseline.checkpoint,
                baseline.completedVersion,
                baseline.items.size,
                baseline.items.size,
            ),
        )
        try {
            reconnect()
            var initialRecovery: Pair<AuthoritativeHistoryProbe.Observation, ThreadEvidence>? = null
            rule.waitUntil(30_000) {
                currentRecovery { it.admitted }?.also { initialRecovery = it } != null
            }
            rule.waitForIdle()
            val initial = checkNotNull(initialRecovery).first
            val initialRequests = initial.requests
            val savedRequest =
                initialRequests.first() as? ThreadQueryRequest.CatchUp
                    ?: throw AssertionError("saved-checkpoint recovery did not start with catch-up")
            assertEquals("saved source epoch", baseline.epoch, savedRequest.epoch)
            assertEquals("saved source checkpoint", baseline.checkpoint.toULong(), savedRequest.version)
            assertTrue("no page demand before the older gesture", initialRequests.none { it is ThreadQueryRequest.Page })
            val resets = initial.resets
            val recoveryEpoch =
                resets
                    .singleOrNull()
                    ?.reply
                    ?.checkpoint
                    ?.epoch ?: baseline.epoch
            if (resets.isEmpty()) {
                val certificate = initial.certificates.first()
                assertTrue("the exact saved request was certified", certificate.request === savedRequest)
                val coverage =
                    certificate.reply.coverage as? ThreadQueryReply.Coverage.CatchUp
                        ?: throw AssertionError("saved tail was not certified")
                assertEquals("complete captured source interval", savedRequest.version, coverage.fromVersion)
                assertTrue("bounded changed tail", certificate.reply.checkpoint.version - savedRequest.version <= 1024uL)
                assertEquals(
                    "one accepted saved-tail request",
                    1,
                    initialRequests.count {
                        it is ThreadQueryRequest.CatchUp && it.epoch == savedRequest.epoch && it.version == savedRequest.version
                    },
                )
                verifyFollowUps(initial.certificates, recoveryEpoch)
            } else {
                val reset = resets.single()
                assertTrue("reset belongs to the exact accepted saved request", reset.request === savedRequest)
                assertTrue(
                    "reset names its actual current source",
                    reset.reply.checkpoint.epoch
                        .isNotBlank(),
                )
                val certificates = initial.certificates
                val newest = certificates.first()
                val opening =
                    newest.request as? ThreadQueryRequest.CatchUp
                        ?: throw AssertionError("reset did not request a fresh window")
                assertEquals("reset is followed by its exact zero request", initialRequests[1], opening)
                assertEquals("fresh window names the actual reset source", recoveryEpoch, opening.epoch)
                assertEquals("fresh window begins without a borrowed checkpoint", 0uL, opening.version)
                val window =
                    newest.reply.coverage as? ThreadQueryReply.Coverage.Window
                        ?: throw AssertionError("reset did not receive a certified newest window")
                assertTrue("fresh window starts a real older gap", window.olderExists)
                assertTrue("fresh window is from the observed reset generation", newest.snapshot.generation > 0)
                verifyFollowUps(certificates, recoveryEpoch)
                val first = checkNotNull(authoritativeEvidence())
                val source = first.owner
                assertTrue("newest post was actually recovered", first.records.any { it.fullText == newestPost })
                assertTrue("older post is genuinely absent", first.records.none { it.fullText == olderPost })
                assertTrue("the missed reply is genuinely absent", reply.admitted(first, serverId) == null)
                var pulls = 0
                while (checkNotNull(authoritativeEvidence()).sourceSnapshot?.coverage?.olderExists == true) {
                    check(pulls < 16) { "older page walk exceeded the bounded fixture" }
                    val before = checkNotNull(authoritativeEvidence())
                    check(source.matches(before.owner)) { "older walk source changed" }
                    val previous = checkNotNull(before.sourceSnapshot?.coverage)
                    val oldest = before.records.first { it.message.toolCall == null && it.fullText.isNotBlank() }
                    val requests = AuthoritativeHistoryProbe.requests()
                    val proofs = AuthoritativeHistoryProbe.certificates()
                    val bubble = hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
                    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(oldest.fullText) and bubble)
                    rule.onNode(hasText(oldest.fullText) and bubble, useUnmergedTree = true).assertIsDisplayed()
                    assertEquals("revealing the supplied oldest row is inert", requests, AuthoritativeHistoryProbe.requests())
                    pull()
                    rule.waitUntil(30_000) {
                        AuthoritativeHistoryProbe.current() &&
                            AuthoritativeHistoryProbe.requests().size == requests.size + 1 &&
                            AuthoritativeHistoryProbe.certificates().size == proofs.size + 1 &&
                            authoritativeEvidence()?.let {
                                source.matches(it.owner) &&
                                    it.sourceSnapshot
                                        ?.coverage
                                        ?.lowerOrder
                                        ?.let { lower -> lower < previous.lowerOrder } == true
                            } == true
                    }
                    val proof = AuthoritativeHistoryProbe.certificates().last()
                    val request =
                        proof.request as? ThreadQueryRequest.Page
                            ?: throw AssertionError("older gesture did not request a page")
                    assertTrue("page is the actual newly accepted request", AuthoritativeHistoryProbe.requests().last() === request)
                    assertEquals("page begins at the previous actual continuation", previous.continuation, request.beforeOrder)
                    val coverage =
                        proof.reply.coverage as? ThreadQueryReply.Coverage.Window
                            ?: throw AssertionError("older gesture did not certify a window")
                    assertEquals("page's exclusive upper boundary", request.beforeOrder, coverage.upperOrder)
                    assertEquals("actual continuation matches lower boundary", coverage.lowerOrder, coverage.continuation)
                    assertEquals("page source epoch", recoveryEpoch, proof.reply.checkpoint.epoch)
                    val accumulated = checkNotNull(checkNotNull(authoritativeEvidence()).sourceSnapshot?.coverage)
                    assertEquals("accumulated coverage is continuous", coverage.lowerOrder, accumulated.lowerOrder)
                    assertEquals("accumulated coverage retains its newest boundary", previous.upperOrder, accumulated.upperOrder)
                    rule.waitForIdle()
                    assertEquals(
                        "page arrival does not initiate another demand",
                        requests.size + 1,
                        AuthoritativeHistoryProbe.requests().size,
                    )
                    pulls++
                }
                assertTrue("this fixture crossed multiple real older pages", pulls >= 2)
                assertEquals(
                    "older walk reached its certified beginning",
                    false,
                    checkNotNull(authoritativeEvidence()).sourceSnapshot?.coverage?.olderExists,
                )
            }
            var finalRecovery: Pair<AuthoritativeHistoryProbe.Observation, ThreadEvidence>? = null
            rule.waitUntil(30_000) {
                currentRecovery { evidence ->
                    val texts = evidence.records.map { it.fullText }
                    evidence.admitted &&
                        expected.all { it in texts } &&
                        reply.admitted(evidence, serverId) != null &&
                        rendererHasNewestRecovery(evidence)
                }?.also { finalRecovery = it } != null
            }
            val (observation, evidence) = checkNotNull(finalRecovery)
            val snapshot = checkNotNull(evidence.sourceSnapshot)
            val certificates = observation.certificates
            val version = observation.currentCertificates.maxOf { it.reply.checkpoint.version }
            assertEquals("recovered source epoch", recoveryEpoch, snapshot.epoch)
            assertTrue("current source certifies every actual recovery", snapshot.checkpoint.toULong() >= version)
            assertTrue("current source is completely certified", snapshot.checkpoint >= snapshot.completedVersion)
            assertEquals(
                "every accepted request has an exact terminal receipt",
                observation.requests.size,
                certificates.size + observation.resets.size,
            )
            assertTrue("every receipt belongs to its exact accepted request and generation", observation.exactlyAccounted)
            assertEquals("no legacy content request", 0, observation.legacyRequests)
            val recovered = checkNotNull(reply.admitted(evidence, serverId))
            assertEquals("complete independently produced reply", reply.text, recovered.records.joinToString("") { it.fullText })
            val terminalMatches =
                snapshot.items.filter {
                    it.kind == "turn_end" &&
                        (it.parent ?: 0) == 0L &&
                        it.session == recovered.session &&
                        it.turn == recovered.turn
                }
            Log.i(
                "DurableGapProof",
                durableGapDiagnostic(
                    DurableGapCheck.Terminal,
                    snapshot.epoch == baseline.epoch,
                    snapshot.generation,
                    snapshot.checkpoint,
                    snapshot.completedVersion,
                    1,
                    terminalMatches.size,
                ),
            )
            val terminal = terminalMatches.single()
            assertTrue(
                "completed root turn is certified",
                !terminal.active && terminal.status == "done" && terminal.rev.toULong() <= version,
            )
            val end = terminal.content as JsonObject
            assertEquals("successful source completion", JsonPrimitive("success"), end["outcome"])
            assertEquals("source completion is not an error", JsonPrimitive(false), end["is_error"])
            assertEquals("source completed its turn", JsonPrimitive("end_turn"), end["stop_reason"])

            val baselineRows = authoritativeThreadEvidence(serverId, conversationId, Any(), baseline).records
            val retained =
                baselineRows.mapIndexed { index, old ->
                    val matches =
                        evidence.records.filter { now ->
                            if (recoveryEpoch == baseline.epoch) {
                                now.fact?.id == checkNotNull(old.fact).id
                            } else {
                                now.session == old.session &&
                                    now.turn == old.turn &&
                                    now.message.role == old.message.role &&
                                    now.fullText == old.fullText &&
                                    durableParentMatches(now.fact?.parent, old.fact?.parent) &&
                                    (
                                        old.message.role != Role.User ||
                                            (
                                                (old.fact?.content as? JsonObject)?.get("message_id")?.let { id ->
                                                    (now.fact?.content as? JsonObject)?.get("message_id") == id
                                                } == true
                                            )
                                    )
                            }
                        }
                    if (matches.size != 1) {
                        Log.i(
                            "DurableGapProof",
                            durableGapDiagnostic(
                                DurableGapCheck.RetainedRow,
                                recoveryEpoch == baseline.epoch,
                                snapshot.generation,
                                snapshot.checkpoint,
                                snapshot.completedVersion,
                                1,
                                matches.size,
                                index,
                            ) +
                                " session_matches=${evidence.records.count { it.session == old.session }}" +
                                " turn_matches=${evidence.records.count { it.turn == old.turn }}" +
                                " role_matches=${evidence.records.count { it.message.role == old.message.role }}" +
                                " text_matches=${evidence.records.count { it.fullText == old.fullText }}" +
                                " parent_matches=${evidence.records.count {
                                    durableParentMatches(
                                        it.fact?.parent,
                                        old.fact?.parent,
                                    )
                                }}" +
                                " id_matches=${evidence.records.count { it.fact?.id == old.fact?.id }}",
                        )
                    }
                    val held = matches.single()
                    assertEquals("saved source session", old.session, held.session)
                    assertEquals("saved source turn", old.turn, held.turn)
                    assertEquals("saved source role", old.message.role, held.message.role)
                    assertEquals("saved source text", old.fullText, held.fullText)
                    held
                }
            Log.i(
                "DurableGapProof",
                durableGapDiagnostic(
                    DurableGapCheck.RetainedRows,
                    recoveryEpoch == baseline.epoch,
                    snapshot.generation,
                    snapshot.checkpoint,
                    snapshot.completedVersion,
                    baselineRows.size,
                    retained.size,
                ),
            )
            val texts = evidence.records.map { it.fullText }
            assertEquals("chronological durable posts, each once", expected, texts.filter { it.startsWith(prefix) })
            val indices = recovered.records.map { record -> evidence.records.indexOfFirst { it.fact?.id == record.fact?.id } }
            assertTrue("recovered reply fragments are chronological", indices == indices.sorted())
            assertTrue(
                "recovered reply lies between both durable post batches",
                indices.all { it > texts.indexOf(lastOlderPost) && it < texts.indexOf("$prefix-b-000") },
            )
            val newest = evidence.records.single { it.fullText == newestPost }
            assertTrue("the recovery certificate covers the latest synthetic fact", checkNotNull(newest.fact).rev.toULong() <= version)
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(newestPost))
            rule.onNodeWithText(newestPost, useUnmergedTree = true).assertIsDisplayed()
            val bubble = hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
            val baselineMatchers =
                retained.filter { it.message.role in setOf(Role.User, Role.Assistant) }.map {
                    if (it.message.role == Role.Assistant) ownedReplyMatcher(it) else hasText(it.fullText) and bubble
                }
            val requests = AuthoritativeHistoryProbe.requests()
            assertRenderedOrder(
                baselineMatchers +
                    listOf(hasText(lastOlderPost) and bubble, hasText(promptText) and bubble) +
                    recovered.records.map(::ownedReplyMatcher) + listOf(hasText("$prefix-b-000") and bubble),
            )
            assertEquals("rendered reveals issue no history demand", requests, AuthoritativeHistoryProbe.requests())
            assertTrue("the accepted source remains current through rendering", AuthoritativeHistoryProbe.current())
            finalAuthoritative = evidence
        } finally {
            AuthoritativeHistoryProbe.end()
        }
    }

    private fun verifyFollowUps(
        certificates: List<AuthoritativeHistoryProbe.Certificate>,
        recoveryEpoch: String?,
    ) {
        val newest = certificates.first()
        for (extra in certificates.drop(1)) {
            val request = extra.request as? ThreadQueryRequest.CatchUp ?: throw AssertionError("page arrived without a gesture")
            val coverage =
                extra.reply.coverage as? ThreadQueryReply.Coverage.CatchUp ?: throw AssertionError("unexpected replacement window")
            assertEquals("same source follow-up epoch", recoveryEpoch, request.epoch)
            assertEquals("follow-up begins at the freshly certified checkpoint", newest.reply.checkpoint.version, request.version)
            assertEquals("follow-up certifies its actual requested interval", request.version, coverage.fromVersion)
        }
        assertTrue("at most one actual post-reset follow-up", certificates.size <= 2)
    }

    private fun currentRecovery(accept: (ThreadEvidence) -> Boolean): Pair<AuthoritativeHistoryProbe.Observation, ThreadEvidence>? {
        val observation = AuthoritativeHistoryProbe.snapshot()
        if (!observation.ready) return null
        val evidence = authoritativeEvidence() ?: return null
        if (!observation.admits(evidence) || !accept(evidence) || !AuthoritativeHistoryProbe.isCurrent(observation)) return null
        Log.i(
            "DurableGapProof",
            "event=phone_recovery_admitted accepted=${observation.requests.size} certificates=${observation.certificates.size} " +
                "resets=${observation.resets.size} generation=${evidence.owner.generation} " +
                "checkpoint=${evidence.sourceSnapshot?.checkpoint} completed=${evidence.sourceSnapshot?.completedVersion}",
        )
        return observation to evidence
    }

    /** A repository emission precedes collection/layout; interrogate the actual lazy item provider without scrolling. */
    private fun rendererHasNewestRecovery(evidence: ThreadEvidence): Boolean {
        val newest = evidence.records.singleOrNull { it.fullText == newestPost } ?: return false
        val snapshot = evidence.sourceSnapshot ?: return false
        val row =
            projectAuthoritativeThread(serverId, conversationId, snapshot).rows.filterIsInstance<ThreadRow.Delivered>().singleOrNull {
                (it.item as? ThreadItem.MessageItem)?.message?.id == newest.message.id
            } ?: return false
        val indexForKey =
            rule
                .onAllNodes(hasScrollToNodeAction())
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.IndexForKey) ?: return false
        return indexForKey(row.listKey(0)) >= 0
    }

    private fun authoritativeEvidence(): ThreadEvidence? =
        runBlocking {
            val registry = GlobalContext.get().get<RelayConnectionRegistry>()
            val bundle = registry.connectionFor(serverId) ?: return@runBlocking null
            val repository = bundle.coordinator.currentRepository.value as? RemoteConversationRepository ?: return@runBlocking null
            val evidence = harnessThreadEvidence(repository).observe(conversationId).first()
            evidence.takeIf {
                registry.connectionFor(serverId) === bundle &&
                    bundle.coordinator.currentRepository.value === repository &&
                    it.owner.connection === repository
            }
        }

    fun messages(): List<String> = (finalAuthoritative ?: authoritativeEvidence())?.records.orEmpty().map { it.fullText }

    /** Compare rendered positions across lazy viewports, rather than repository row indices. */
    private fun assertRenderedOrder(matchers: List<SemanticsMatcher>) {
        val positions =
            matchers.map { matcher ->
                val list = rule.onNode(hasScrollToNodeAction())
                list.performScrollToNode(matcher)
                rule.onAllNodes(matcher, useUnmergedTree = true).assertCountEquals(1)
                val row = rule.onNode(matcher, useUnmergedTree = true)
                row.assertIsDisplayed()
                // ThreadScreen reverses its lazy list: increasing scroll position means older content.
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() to
                    row.fetchSemanticsNode().boundsInRoot.top
            }
        positions.zipWithNext().forEachIndexed { index, (older, newer) ->
            assertTrue(
                "rendered rows $index and ${index + 1} are distinct and chronological",
                older.first > newer.first || (older.first == newer.first && older.second < newer.second),
            )
        }
    }

    /** Numeric receipt/projection evidence is separate from the actual exhaustive Compose search. */

    private fun pull() =
        rule.onNodeWithTag("thread-message-region").performTouchInput {
            pullHistoryPage()
        }
}
