package de.pyryco.mobile.ui.settings

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdEncode
import de.pyryco.mobile.data.repository.DebugBundleStatus
import de.pyryco.mobile.data.repository.DebugBundleTransfer
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The Log data download's own machine (#683): which host is asked, what a second tap does, and what
 * survives a picker round trip.
 *
 * Drives a real [DebugBundleTransfer] rather than a stand-in, so the status arms asserted here are
 * #682's own and a change to that enum reaches these assertions rather than a local copy of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugBundleDownloadControllerTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun everyFailureStatusCarriesItsOwnStaticSentenceAndLeavesTheActionIdle() =
        runTest {
            val expected =
                mapOf(
                    DebugBundleStatus.UNAVAILABLE to DebugBundleFailure.UNAVAILABLE,
                    DebugBundleStatus.BUSY to DebugBundleFailure.BUSY,
                    DebugBundleStatus.RECONNECT_REQUIRED to DebugBundleFailure.RECONNECT_REQUIRED,
                    DebugBundleStatus.SEND_FAILED to DebugBundleFailure.SEND_FAILED,
                    DebugBundleStatus.REFUSED to DebugBundleFailure.REFUSED,
                    DebugBundleStatus.INVALID_STREAM to DebugBundleFailure.INVALID_STREAM,
                    DebugBundleStatus.DISCONNECTED to DebugBundleFailure.DISCONNECTED,
                )
            for ((status, failure) in expected) {
                val transfer = DebugBundleTransfer(REQUEST_ID)
                val fixture = fixture { transfer }
                fixture.controller.open(HOST_NAME)
                fixture.controller.requestArchive()
                advanceUntilIdle()
                transfer.fail(status)
                advanceUntilIdle()
                val state = checkNotNull(fixture.controller.state.value)
                assertEquals(status.name, failure, state.failure)
                assertFalse(status.name, state.receiving)
                assertNull(status.name, state.readyBytes)
                assertNull(status.name, state.savedTo)
                // Idle again: a fresh request is allowed after a failure.
                fixture.controller.requestArchive()
                advanceUntilIdle()
                assertEquals(status.name, 2, fixture.requested.size)
            }
            // Nine distinct sentences, so no two failures can be confused for one another.
            assertEquals(
                9,
                DebugBundleFailure.entries
                    .map { it.message }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun aSecondTapWhileReceivingNeverReachesTheHostAgain() =
        runTest {
            val transfer = DebugBundleTransfer(REQUEST_ID)
            val fixture = fixture { transfer }
            fixture.controller.open(HOST_NAME)
            fixture.controller.requestArchive()
            advanceUntilIdle()
            assertTrue(checkNotNull(fixture.controller.state.value).receiving)
            repeat(3) { fixture.controller.requestArchive() }
            advanceUntilIdle()
            assertEquals(listOf(HOST_ID), fixture.requested)
            // And once an archive is held, the action saves rather than re-requesting.
            transfer.accept(done(0))
            advanceUntilIdle()
            fixture.controller.requestArchive()
            advanceUntilIdle()
            assertEquals(listOf(HOST_ID), fixture.requested)
        }

    @Test
    fun aCompletedArchiveSurvivesThePickerRoundTripByteForByte() =
        runTest {
            val parts = listOf("first-part".toByteArray(), byteArrayOf(0, -1, 7))
            val transfer = DebugBundleTransfer(REQUEST_ID)
            val fixture = fixture { transfer }
            fixture.controller.open(HOST_NAME)
            fixture.controller.requestArchive()
            advanceUntilIdle()
            parts.forEachIndexed { index, bytes ->
                transfer.accept(chunk(index, bytes))
                advanceUntilIdle()
                assertEquals(index + 1, checkNotNull(fixture.controller.state.value).acceptedChunks)
            }
            transfer.accept(done(parts.size))
            advanceUntilIdle()
            val ready = checkNotNull(fixture.controller.state.value)
            assertEquals(parts.sumOf { it.size }.toLong(), ready.readyBytes)
            assertFalse(ready.receiving)

            val destination = FakeDestination()
            fixture.controller.onDestination(destination)
            advanceUntilIdle()
            val saved = checkNotNull(fixture.controller.state.value)
            assertArrayEquals(parts.fold(byteArrayOf()) { a, b -> a + b }, destination.written.toByteArray())
            assertEquals(SAVED_NAME, saved.savedTo)
            assertNull(saved.failure)
            assertFalse(saved.saving)
            assertEquals(0, destination.discarded)
            assertTrue(logs.none { it.contains("first-part") })
        }

    @Test
    fun aCancelledPickerLeavesTheSameArchiveSaveable() =
        runTest {
            val fixture = readyFixture()
            fixture.controller.onDestination(null)
            advanceUntilIdle()
            val cancelled = checkNotNull(fixture.controller.state.value)
            assertEquals(DebugBundleFailure.PICKER_CANCELLED, cancelled.failure)
            assertEquals(ARCHIVE.size.toLong(), cancelled.readyBytes)
            assertNull(cancelled.savedTo)

            val destination = FakeDestination()
            fixture.controller.onDestination(destination)
            advanceUntilIdle()
            assertArrayEquals(ARCHIVE, destination.written.toByteArray())
            assertEquals(SAVED_NAME, checkNotNull(fixture.controller.state.value).savedTo)
        }

    @Test
    fun aFailedWriteDiscardsThePartialDocumentAndLeavesTheArchiveSaveable() =
        runTest {
            for (failing in Failing.entries) {
                val fixture = readyFixture()
                val broken = FakeDestination(failing = failing)
                fixture.controller.onDestination(broken)
                advanceUntilIdle()
                val failed = checkNotNull(fixture.controller.state.value)
                assertEquals(failing.name, DebugBundleFailure.WRITE_FAILED, failed.failure)
                assertNull(failing.name, failed.savedTo)
                assertFalse(failing.name, failed.saving)
                assertEquals(failing.name, ARCHIVE.size.toLong(), failed.readyBytes)
                assertEquals(failing.name, 1, broken.discarded)

                val destination = FakeDestination()
                fixture.controller.onDestination(destination)
                advanceUntilIdle()
                assertArrayEquals(failing.name, ARCHIVE, destination.written.toByteArray())
                assertEquals(failing.name, SAVED_NAME, checkNotNull(fixture.controller.state.value).savedTo)
            }
        }

    @Test
    fun successIsReportedOnlyAfterTheWriteReturns() =
        runTest {
            val fixture = readyFixture()
            // Sampled from inside the save, at the moment the document is opened: the ordering claim
            // is about what the screen shows while bytes are still in flight, so the assertion is
            // made there rather than at a dispatcher boundary a later refactor could move.
            var duringWrite: DebugBundleDownloadState? = null
            val destination = FakeDestination(probe = { duringWrite = fixture.controller.state.value })
            fixture.controller.onDestination(destination)
            advanceUntilIdle()
            val during = checkNotNull(duringWrite)
            assertTrue(during.saving)
            assertNull(during.savedTo)
            assertEquals(SAVED_NAME, checkNotNull(fixture.controller.state.value).savedTo)
        }

    @Test
    fun dismissDropsTheHeldArchiveAndLaterResultsWriteNothing() =
        runTest {
            val fixture = readyFixture()
            fixture.controller.dismiss()
            advanceUntilIdle()
            assertNull(fixture.controller.state.value)
            val destination = FakeDestination()
            fixture.controller.onDestination(destination)
            advanceUntilIdle()
            assertEquals(0, destination.written.size())
            assertNull(fixture.controller.state.value)
        }

    @Test
    fun externallyAuthoredTextIsClampedBeforeItReachesTheState() =
        runTest {
            val fixture = readyFixture(hostName = "h".repeat(MAX_WORKSPACE_LABEL_CHARS * 3))
            assertEquals(MAX_WORKSPACE_LABEL_CHARS, checkNotNull(fixture.controller.state.value).hostName.length)
            val destination = FakeDestination(label = "n".repeat(MAX_WORKSPACE_LABEL_CHARS * 3))
            fixture.controller.onDestination(destination)
            advanceUntilIdle()
            assertEquals(
                MAX_WORKSPACE_LABEL_CHARS,
                checkNotNull(checkNotNull(fixture.controller.state.value).savedTo).length,
            )
        }

    @Test
    fun requestsAlwaysCarryTheCapturedHostNeverAnother() =
        runTest {
            val fixture = fixture(serverId = "other-host-2026") { DebugBundleTransfer(REQUEST_ID) }
            fixture.controller.open(HOST_NAME)
            fixture.controller.requestArchive()
            advanceUntilIdle()
            assertEquals(listOf("other-host-2026"), fixture.requested)
        }

    private class Fixture(
        val controller: DebugBundleDownloadController,
        val requested: MutableList<String>,
    )

    private fun TestScope.fixture(
        serverId: String = HOST_ID,
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        next: () -> DebugBundleTransfer,
    ): Fixture {
        val requested = mutableListOf<String>()
        val controller =
            DebugBundleDownloadController(
                // The background scope's Job, so the collector dies with the test, but an unconfined
                // dispatcher, so a transfer emission is delivered at the point it is published rather
                // than at the next scheduler drain. The save keeps its own StandardTestDispatcher
                // below, which is what makes "not saved until the write returns" assertable.
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                serverId = serverId,
                request = { id ->
                    requested += id
                    next()
                },
                io = io,
            )
        return Fixture(controller, requested)
    }

    /** A controller holding one completed archive, which is where every save assertion starts. */
    private fun TestScope.readyFixture(hostName: String = HOST_NAME): Fixture {
        val transfer = DebugBundleTransfer(REQUEST_ID)
        val fixture = fixture { transfer }
        fixture.controller.open(hostName)
        fixture.controller.requestArchive()
        advanceUntilIdle()
        transfer.accept(chunk(0, ARCHIVE))
        transfer.accept(done(1))
        advanceUntilIdle()
        return fixture
    }

    /** Which step of the save throws — each has to reach the same single sentence. */
    private enum class Failing { NAME, OPEN, WRITE }

    private class FakeDestination(
        private val label: String = SAVED_NAME,
        private val failing: Failing? = null,
        private val probe: (() -> Unit)? = null,
    ) : ArchiveDestination {
        val written = ByteArrayOutputStream()
        var discarded = 0

        override fun name(): String = if (failing == Failing.NAME) throw IOException("provider") else label

        override fun openStream(): OutputStream =
            probe.let {
                it?.invoke()
                openFor()
            }

        private fun openFor(): OutputStream =
            when (failing) {
                Failing.OPEN -> throw IOException("no stream")
                Failing.WRITE ->
                    object : OutputStream() {
                        override fun write(b: Int) = throw IOException("disk full")
                    }
                else -> written
            }

        override fun discard() {
            discarded++
        }
    }

    private companion object {
        const val HOST_ID = "pyrybox-2026-0f3a"
        const val HOST_NAME = "Pyrybox"
        const val SAVED_NAME = "pyrycode-debug-bundle.tar.gz"
        const val REQUEST_ID = 7L
        val ARCHIVE = "archive-bytes".toByteArray()

        fun chunk(
            seq: Int,
            bytes: ByteArray,
        ) = Envelope(
            REQUEST_ID + 1,
            "debug_bundle_chunk",
            TS,
            MobileJson.parseToJsonElement("""{"seq":$seq,"data":"${base64StdEncode(bytes)}"}"""),
        )

        fun done(total: Int) = Envelope(REQUEST_ID + 2, "debug_bundle_done", TS, MobileJson.parseToJsonElement("""{"total":$total}"""))

        const val TS = "2026-09-22T00:00:00Z"
    }
}
