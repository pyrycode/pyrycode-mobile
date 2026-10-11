package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveOrderProbeTest {
    private val disconnected = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
    private val connected = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)

    @Test
    fun initialListFailureRetainsTargetHostStateBeforeCleanup() {
        var cleaned = false
        var observedBeforeCleanup = false
        val original = AssertionError("list delivery failed")
        val probe =
            ArchiveOrderProbe(
                observe = {
                    observedBeforeCleanup = !cleaned
                    ArchiveOrderObservation(true, false, disconnected, false, false)
                },
                record = {},
                elapsedMs = { 12L },
            )
        val caught =
            try {
                probe.step(ArchiveOrderStage.InitialList) { throw original }
            } catch (failure: Throwable) {
                failure
            } finally {
                cleaned = true
            }
        assertSame(original, caught)
        assertTrue(cleaned)
        assertTrue(observedBeforeCleanup)
        val evidence =
            caught.suppressed
                .single()
                .message
                .orEmpty()
        assertTrue(evidence.contains("stage=InitialList"))
        assertTrue(evidence.contains("target_selected=false"))
        assertTrue(evidence.contains("relay=Offline daemon=Down repository=false rows_loaded=false"))
        assertTrue(evidence.contains("elapsed_ms=12"))
    }

    @Test
    fun completedStagesDistinguishFixtureCreationArchiveAndMembership() {
        val events = mutableListOf<String>()
        val probe =
            ArchiveOrderProbe(
                observe = { ArchiveOrderObservation(true, true, connected, true, true) },
                record = events::add,
                elapsedMs = { 0L },
            )
        assertEquals("fixture", probe.step(ArchiveOrderStage.CreateA) { "fixture" })
        probe.step(ArchiveOrderStage.ArchiveB) { Unit }
        probe.step(ArchiveOrderStage.MembershipB) { Unit }
        val failure =
            try {
                probe.step(ArchiveOrderStage.RelativeOrder) { throw AssertionError("order") }
            } catch (failure: Throwable) {
                failure
            }
        assertTrue(events.any { it.contains("stage=CreateA outcome=complete") })
        assertTrue(events.any { it.contains("stage=ArchiveB outcome=complete") })
        assertTrue(events.any { it.contains("stage=MembershipB outcome=complete") })
        assertTrue(
            failure.suppressed
                .single()
                .message
                .orEmpty()
                .contains("stage=RelativeOrder outcome=failure"),
        )
    }

    @Test
    fun diagnosticFailureCannotReplaceOrExposeOriginalFailure() {
        val original = IllegalStateException("original")
        val diagnostic = IllegalStateException("sensitive diagnostic text")
        val probe =
            ArchiveOrderProbe(
                observe = { throw diagnostic },
                record = { throw diagnostic },
                elapsedMs = { 0L },
            )
        val failure =
            try {
                probe.step(ArchiveOrderStage.CompatibilityReadiness) { throw original }
            } catch (failure: Throwable) {
                failure
            }
        assertSame(original, failure)
        val evidence =
            failure.suppressed
                .single()
                .message
                .orEmpty()
        assertTrue(evidence.contains("observation=unavailable"))
        assertFalse(evidence.contains("sensitive"))
        assertFalse(failure.suppressed.any { it === diagnostic })
    }

    @Test
    fun observationsAreBoundedAndRetainTheFinalFailure() {
        var elapsed = 0L
        val probe =
            ArchiveOrderProbe(
                observe = { ArchiveOrderObservation(true, true, connected, true, true) },
                record = {},
                elapsedMs = { elapsed++ },
            )
        repeat(200) { probe.sample() }
        val failure =
            try {
                probe.step(ArchiveOrderStage.InitialList) { throw AssertionError("list") }
            } catch (failure: Throwable) {
                failure
            }
        val evidence =
            failure.suppressed
                .single()
                .message
                .orEmpty()
        assertTrue(evidence.lines().size <= 33)
        assertTrue(evidence.length < 10_000)
        assertTrue(evidence.contains("stage=InitialList outcome=failure"))
    }

    @Test
    fun repositoryLossAndReplacementRemainSeparateFromHeldRows() {
        val events = mutableListOf<String>()
        var state = ArchiveOrderObservation(true, false, connected, true, true, 1)
        val probe = ArchiveOrderProbe(observe = { state }, record = events::add, elapsedMs = { 0L })
        probe.sample()
        state = state.copy(status = disconnected, repositoryPresent = false)
        probe.sample()
        state = state.copy(status = connected, repositoryPresent = true, repositoryGeneration = 2)
        probe.sample()
        assertEquals(3, events.size)
        assertTrue(events[1].contains("repository=false rows_loaded=true repository_generation=1"))
        assertTrue(events[2].contains("repository=true rows_loaded=true repository_generation=2"))
    }

    @Test
    fun cancellationAndUnavailableClockPreserveFailureIdentity() {
        val original = kotlinx.coroutines.CancellationException("cancelled")
        val probe =
            ArchiveOrderProbe(
                observe = { ArchiveOrderObservation(false, false, null, false, false) },
                record = {},
                elapsedMs = { error("sensitive clock error") },
            )
        val failure =
            try {
                probe.step(ArchiveOrderStage.ListArrival) { throw original }
            } catch (failure: Throwable) {
                failure
            }
        assertSame(original, failure)
        val evidence =
            failure.suppressed
                .single()
                .message
                .orEmpty()
        assertTrue(evidence.contains("relay=Missing daemon=Missing"))
        assertFalse(evidence.contains("sensitive"))
    }

    @Test
    fun dynamicConnectionFieldsNeverReachEvidence() {
        val events = mutableListOf<String>()
        val status = ConnectionStatus(RelayLinkStatus.UpdateRequired("sensitive minimum version"), PyrycodeLinkStatus.Handshaking)
        val probe =
            ArchiveOrderProbe(
                observe = { ArchiveOrderObservation(true, true, status, false, false) },
                record = events::add,
                elapsedMs = { 0L },
            )
        probe.sample()
        assertTrue(events.single().contains("relay=UpdateRequired daemon=Handshaking"))
        assertFalse(events.single().contains("sensitive"))
    }
}
