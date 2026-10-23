package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

internal fun sessionErrorDiagnosticFailure(
    arm: String,
    phase: String,
    fixtureReady: Boolean,
    failure: Throwable,
    snapshot: () -> String,
): AssertionError {
    val observed = runCatching(snapshot).getOrDefault("snapshot=unavailable")
    return AssertionError("session-error arm=$arm phase=$phase fixture_ready=$fixtureReady $observed", failure)
}

internal fun <T> sessionErrorCleanup(
    cleanup: () -> Unit,
    onPrimaryFailure: () -> Unit = {},
    block: () -> T,
): T {
    var primary: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            cleanup()
        } catch (failure: Throwable) {
            val original = primary
            if (original == null) {
                onPrimaryFailure()
                throw failure
            }
            if (original !== failure) original.addSuppressed(failure)
        }
    }
}

internal suspend fun sessionErrorSavedPairing(
    store: PairedServerCollectionStore,
    serverId: String,
): String =
    withTimeout(1_000) {
        store.readSnapshot().fold(
            onSuccess = { entries -> entries.any { it.record.serverId == serverId }.toString() },
            onFailure = { "unknown" },
        )
    }

/** Bounded, content-free observations; never format an arbitrary wire field. */
internal fun sessionErrorPeerTrace(frames: List<Envelope>): List<String> {
    val omitted = (frames.size - 256).coerceAtLeast(0)
    return listOf("peer_frames=${frames.size} omitted=$omitted") +
        frames.takeLast(256).mapIndexed { index, frame ->
            val type =
                when (frame.type) {
                    "turn_state", "session_error", "assistant_delta", "turn_end", "queue_state", "message",
                    "session_transition", "thread_item_added", "thread_item_changed", "thread_text_append",
                    -> frame.type
                    else -> "other"
                }

            fun stringField(name: String): String? =
                ((frame.payload as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val state =
                if (type == "turn_state") {
                    when (val value = stringField("state")) {
                        "thinking", "responding", "idle" -> value
                        else -> "unknown"
                    }
                } else {
                    "none"
                }
            val error =
                if (type == "session_error") {
                    when (stringField("code")) {
                        "session.child_crashing" -> "child_crashing"
                        "session.blocked" -> "blocked"
                        else -> "unknown"
                    }
                } else {
                    "none"
                }
            val clear = (frame.sessionStateCleared as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            "index=${omitted + index} type=$type state=$state error=$error clear=${clear ?: "unknown"}"
        }
}

@RunWith(AndroidJUnit4::class)
class SessionErrorDiagnosticsTest {
    @Test
    fun peerTracePreservesRecoveryOrderingWithoutPrivateValues() {
        val frames =
            listOf(
                traceFrame("session_error", """{"code":"session.child_crashing","message":"private-prompt"}"""),
                traceFrame("turn_state", """{"state":"responding","conversation_id":"private-id"}"""),
                traceFrame("assistant_delta", """{"text":"private-reply"}"""),
                traceFrame("turn_state", """{"state":"idle"}"""),
                traceFrame("session_error", """{"code":"private-unknown-code"}"""),
                traceFrame("private-type", """{"token":"private-token"}"""),
            )
        assertEquals(
            listOf(
                "peer_frames=6 omitted=0",
                "index=0 type=session_error state=none error=child_crashing clear=false",
                "index=1 type=turn_state state=responding error=none clear=false",
                "index=2 type=assistant_delta state=none error=none clear=false",
                "index=3 type=turn_state state=idle error=none clear=false",
                "index=4 type=session_error state=none error=unknown clear=false",
                "index=5 type=other state=none error=none clear=false",
            ),
            sessionErrorPeerTrace(frames),
        )
        assertFalse(sessionErrorPeerTrace(frames).joinToString().contains("private"))
    }

    @Test
    fun peerTraceHandlesMalformedFieldsAndBoundsOutput() {
        val frames =
            List(300) { index ->
                traceFrame("turn_state", if (index % 2 == 0) "[]" else """{"state":{"private":"value"}}""")
            }
        val trace = sessionErrorPeerTrace(frames)
        assertEquals("peer_frames=300 omitted=44", trace.first())
        assertEquals(257, trace.size)
        assertEquals("index=44 type=turn_state state=unknown error=none clear=false", trace[1])
        assertEquals("index=299 type=turn_state state=unknown error=none clear=false", trace.last())
        assertEquals(listOf("peer_frames=0 omitted=0"), sessionErrorPeerTrace(emptyList()))
    }

    @Test
    fun peerTraceKeepsClearFramesDistinctFromIdle() {
        val frames =
            listOf(
                traceFrame("turn_state", "{}").copy(sessionStateCleared = kotlinx.serialization.json.JsonPrimitive(true)),
                traceFrame(
                    "turn_state",
                    """{"state":"idle"}""",
                ).copy(sessionStateCleared = kotlinx.serialization.json.JsonPrimitive(false)),
                traceFrame(
                    "session_error",
                    """{"code":123}""",
                ).copy(sessionStateCleared = kotlinx.serialization.json.JsonPrimitive("true")),
            )
        assertEquals(
            listOf(
                "peer_frames=3 omitted=0",
                "index=0 type=turn_state state=unknown error=none clear=true",
                "index=1 type=turn_state state=idle error=none clear=false",
                "index=2 type=session_error state=none error=unknown clear=unknown",
            ),
            sessionErrorPeerTrace(frames),
        )
    }

    private fun traceFrame(
        type: String,
        payload: String,
    ) = Envelope(id = 1L, type = type, ts = "2026-10-20T19:00:00Z", payload = MobileJson.parseToJsonElement(payload))

    @Test
    fun peerTracePreservesOrderWithoutPublishingPrivateFields() {
        val frames =
            listOf(
                traceFrame("session_error", """{"code":"session.child_crashing","message":"private-prompt"}"""),
                traceFrame("turn_state", """{"state":"responding","conversation_id":"private-id"}"""),
                traceFrame("assistant_delta", """{"text":"private-reply"}"""),
                traceFrame("turn_state", """{"state":"idle"}"""),
                traceFrame("session_error", """{"code":"private-unknown-code"}"""),
                traceFrame("private-type", """{"token":"private-token"}"""),
            )
        assertEquals(
            listOf(
                "peer_frames=6 omitted=0",
                "index=0 type=session_error state=none error=child_crashing clear=false",
                "index=1 type=turn_state state=responding error=none clear=false",
                "index=2 type=assistant_delta state=none error=none clear=false",
                "index=3 type=turn_state state=idle error=none clear=false",
                "index=4 type=session_error state=none error=unknown clear=false",
                "index=5 type=other state=none error=none clear=false",
            ),
            sessionErrorPeerTrace(frames),
        )
        assertFalse(sessionErrorPeerTrace(frames).joinToString().contains("private"))
    }

    @Test
    fun peerTraceBoundsMalformedFramesAndRetainsOriginalOrdinals() {
        val frames = List(300) { traceFrame("turn_state", """{"state":{"private":"value"}}""") }
        val trace = sessionErrorPeerTrace(frames)
        assertEquals("peer_frames=300 omitted=44", trace.first())
        assertEquals(257, trace.size)
        assertEquals("index=44 type=turn_state state=unknown error=none clear=false", trace[1])
        assertEquals("index=299 type=turn_state state=unknown error=none clear=false", trace.last())
        assertEquals(listOf("peer_frames=0 omitted=0"), sessionErrorPeerTrace(emptyList()))
    }

    @Test
    fun peerTraceDistinguishesClearIdleAndMalformedFlags() {
        val frames =
            listOf(
                traceFrame("turn_state", "{}").copy(sessionStateCleared = kotlinx.serialization.json.JsonPrimitive(true)),
                traceFrame("turn_state", """{"state":"idle"}"""),
                traceFrame(
                    "session_error",
                    """{"code":123}""",
                ).copy(sessionStateCleared = kotlinx.serialization.json.JsonPrimitive("true")),
            )
        assertEquals(
            listOf(
                "peer_frames=3 omitted=0",
                "index=0 type=turn_state state=unknown error=none clear=true",
                "index=1 type=turn_state state=idle error=none clear=false",
                "index=2 type=session_error state=none error=unknown clear=unknown",
            ),
            sessionErrorPeerTrace(frames),
        )
    }

    @Test
    fun bothArmsRetainPhaseReadinessAndOriginalTimeoutWithoutFormattingIt() {
        val timeout = ComposeTimeoutException("private-pairing-token authorization private-frame")
        for (arm in listOf("retained", "dropped")) {
            val failure = sessionErrorDiagnosticFailure(arm, "pair_return", true, timeout) { "saved=true connection=false" }
            assertEquals("session-error arm=$arm phase=pair_return fixture_ready=true saved=true connection=false", failure.message)
            assertSame(timeout, failure.cause)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }

    @Test
    fun unavailableSnapshotCannotReplaceOriginalTimeoutOrPublishItsOwnDetails() {
        val timeout = ComposeTimeoutException("original timeout")
        val failure =
            sessionErrorDiagnosticFailure("dropped", "pair_return", true, timeout) {
                throw IllegalStateException("private-pairing-token")
            }
        assertSame(timeout, failure.cause)
        assertEquals("session-error arm=dropped phase=pair_return fixture_ready=true snapshot=unavailable", failure.message)
    }

    @Test
    fun cleanupRetainsPrimaryFailureAndStillRuns() {
        val timeout = ComposeTimeoutException("original timeout")
        val teardown = IllegalStateException("teardown")
        var cleaned = false
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                sessionErrorCleanup(cleanup = {
                    cleaned = true
                    throw teardown
                }) { throw timeout }
            }
        assertTrue(cleaned)
        assertSame(timeout, failure)
        assertEquals(listOf(teardown), failure.suppressed.toList())
    }

    @Test
    fun cleanupFailureWithoutPrimaryFailureRemainsAFailure() {
        val teardown = IllegalStateException("teardown")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                sessionErrorCleanup(cleanup = { throw teardown }) { Unit }
            }
        assertSame(teardown, failure)
    }

    @Test
    fun failedSnapshotResultIsUnknownAndRetainsOriginalTimeout() =
        runTest {
            val store = SnapshotStore(Result.failure(IllegalStateException("private-token authorization private-frame")))
            val saved = sessionErrorSavedPairing(store, "owned-host")
            val timeout = ComposeTimeoutException("original timeout")
            for (arm in listOf("retained", "dropped")) {
                val failure = sessionErrorDiagnosticFailure(arm, "pair_return", true, timeout) { "saved=$saved" }
                assertEquals("session-error arm=$arm phase=pair_return fixture_ready=true saved=unknown", failure.message)
                assertSame(timeout, failure.cause)
                assertFalse(failure.message.orEmpty().contains("private"))
            }
        }

    @Test
    fun readableSnapshotIdentifiesExactPresenceAndAbsence() =
        runTest {
            val entry = PairedServerEntry(PairedServer("owned-host", "private-token", "private-relay", "private-key"))
            val store = SnapshotStore(Result.success(listOf(entry)))
            assertEquals("true", sessionErrorSavedPairing(store, "owned-host"))
            assertEquals("false", sessionErrorSavedPairing(store, "OWNED-HOST"))
            assertEquals("false", sessionErrorSavedPairing(SnapshotStore(Result.success(emptyList())), "owned-host"))
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun authoritativeSnapshotReadHasOneSecondDeadline() =
        runTest {
            val store = SnapshotStore(Result.success(emptyList()), readDelay = 2_000)
            val failedRead = runCatching { sessionErrorSavedPairing(store, "owned-host") }
            assertTrue(failedRead.isFailure)
            assertEquals(1_000, testScheduler.currentTime)
            val timeout = ComposeTimeoutException("original timeout")
            val failure = sessionErrorDiagnosticFailure("retained", "pair_return", true, timeout) { failedRead.getOrThrow() }
            assertSame(timeout, failure.cause)
            assertTrue(failure.message.orEmpty().endsWith("snapshot=unavailable"))
        }

    @Test
    fun primaryCleanupFailureIdentifiesRemovalOrCloseAndRetainsCause() {
        for (cleanupPhase in listOf("pairing_teardown", "fixture_close")) {
            var phase = "list_return"
            val teardown = IllegalStateException("private-teardown")
            val primary =
                assertThrows(IllegalStateException::class.java) {
                    sessionErrorCleanup(
                        cleanup = { throw teardown },
                        onPrimaryFailure = { phase = cleanupPhase },
                    ) { Unit }
                }
            val failure = sessionErrorDiagnosticFailure("dropped", phase, true, primary) { "saved=unknown" }
            assertEquals("session-error arm=dropped phase=$cleanupPhase fixture_ready=true saved=unknown", failure.message)
            assertSame(teardown, failure.cause)
        }
    }

    @Test
    fun secondaryCleanupFailuresPreserveOperationPhaseAndAllCauses() {
        var phase = "pair_return"
        val timeout = ComposeTimeoutException("original timeout")
        val removal = IllegalStateException("private-removal")
        val close = IllegalStateException("private-close")
        val original = sessionErrorDiagnosticFailure("retained", phase, true, timeout) { "saved=true" }
        val failure =
            assertThrows(AssertionError::class.java) {
                sessionErrorCleanup(
                    cleanup = { throw close },
                    onPrimaryFailure = { phase = "fixture_close" },
                ) {
                    sessionErrorCleanup(
                        cleanup = { throw removal },
                        onPrimaryFailure = { phase = "pairing_teardown" },
                    ) { throw original }
                }
            }
        assertEquals("pair_return", phase)
        assertSame(original, failure)
        assertSame(timeout, failure.cause)
        assertEquals(listOf(removal, close), failure.suppressed.toList())
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    private class SnapshotStore(
        private val snapshot: Result<List<PairedServerEntry>>,
        private val readDelay: Long = 0,
    ) : PairedServerCollectionStore {
        override suspend fun readSnapshot(): Result<List<PairedServerEntry>> {
            delay(readDelay)
            return snapshot
        }

        override suspend fun list(): List<PairedServerEntry> = snapshot.getOrDefault(emptyList())

        override suspend fun loadById(serverId: String): PairedServerEntry? = list().find { it.record.serverId == serverId }

        override suspend fun load(): PairedServer? = error("unused")

        override suspend fun save(record: PairedServer) = error("unused")

        override suspend fun remove(serverId: String) = error("unused")

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) = error("unused")
    }
}
