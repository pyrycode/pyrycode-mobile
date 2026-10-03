package de.pyryco.mobile.data.diagnostics

import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The release-kept message trail (#1564): fixed-shape lines, once per state, a bounded file that survives
 * a restart, and no dependency on the debug gate `RelayLog` keeps.
 */
class MessageTrailTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun eachState_writesOneTimestampedLine_inTheDocumentedFormat() =
        runTest {
            val file = File(tmp.root, MessageTrail.FILE_NAME)
            val logcat = mutableListOf<String>()
            val trail = newTrail(file, logcat = logcat)

            trail.sent(ID_A, "1a2b3c4d")
            trail.acknowledged(ID_A)
            trail.queued(ID_A)
            trail.delivered(ID_A)
            trail.failed(ID_B, MessageTrail.Failure.DAEMON_ERROR, "conversation.not_found")
            trail.failed(ID_C, MessageTrail.Failure.TORN_DOWN)
            trail.dropped(ID_C)
            advanceUntilIdle()

            val expected =
                listOf(
                    "$T id=$ID_A state=sent conn=1a2b3c4d",
                    "$T id=$ID_A state=acknowledged",
                    "$T id=$ID_A state=queued",
                    "$T id=$ID_A state=delivered",
                    "$T id=$ID_B state=failed reason=daemon_error code=conversation.not_found",
                    "$T id=$ID_C state=failed reason=torn_down",
                    "$T id=$ID_C state=dropped reason=user_dropped",
                )
            assertEquals(expected, logcat)
            assertEquals(expected, file.readLines())
            trail.dispose()
        }

    @Test
    fun aStateReachedTwice_isLoggedOnce() =
        runTest {
            val logcat = mutableListOf<String>()
            val trail = newTrail(null, logcat = logcat)

            trail.queued(ID_A)
            trail.queued(ID_A)
            trail.delivered(ID_A)
            trail.delivered(ID_A)
            trail.queued(ID_B)

            assertEquals(
                listOf("$T id=$ID_A state=queued", "$T id=$ID_A state=delivered", "$T id=$ID_B state=queued"),
                logcat,
            )
            trail.dispose()
        }

    @Test
    fun aSecondTrailOnTheSameFile_appendsAfterTheFirst() =
        runTest {
            val file = File(tmp.root, MessageTrail.FILE_NAME)
            val first = newTrail(file)
            first.sent(ID_A, "1a2b3c4d")
            advanceUntilIdle()
            first.dispose()

            val second = newTrail(file)
            second.sent(ID_B, "5e6f7a8b")
            advanceUntilIdle()

            assertEquals(
                listOf("$T id=$ID_A state=sent conn=1a2b3c4d", "$T id=$ID_B state=sent conn=5e6f7a8b"),
                file.readLines(),
            )
            second.dispose()
        }

    @Test
    fun theFile_staysWithinItsCap_droppingTheOldestLinesFirst() =
        runTest {
            val file = File(tmp.root, MessageTrail.FILE_NAME)
            val maxBytes = 1_000L
            val trail = newTrail(file, maxBytes = maxBytes)
            val ids = (0 until 60).map { "00000000-0000-4000-8000-%012d".format(it) }

            ids.forEach { id ->
                trail.queued(id)
                advanceUntilIdle()
                assertTrue("file grew to ${file.length()} bytes", file.length() <= maxBytes)
            }

            val lines = file.readLines()
            assertEquals("$T id=${ids.last()} state=queued", lines.last())
            assertFalse(lines.any { ids.first() in it })
            // Whole lines only: a trim never leaves a cut line at the head.
            assertTrue(lines.all { it.startsWith("$T id=") && it.endsWith("state=queued") })
            trail.dispose()
        }

    @Test
    fun anUnwritableFile_stillReachesLogcat_andNeverThrows() =
        runTest {
            // A directory where the file should be: every append fails.
            val file = File(tmp.root, MessageTrail.FILE_NAME).apply { mkdirs() }
            val logcat = mutableListOf<String>()
            val trail = newTrail(file, logcat = logcat)

            trail.sent(ID_A, "1a2b3c4d")
            trail.acknowledged(ID_A)
            advanceUntilIdle()
            trail.queued(ID_B)
            advanceUntilIdle()

            assertEquals(3, logcat.size)
            assertTrue(file.isDirectory)
            trail.dispose()
        }

    @Test
    fun inputsOutsideTheirFixedShape_neverReachALine() =
        runTest {
            val logcat = mutableListOf<String>()
            val trail = newTrail(null, logcat = logcat)

            trail.sent("hello world, my secret text", "1a2b3c4d")
            trail.queued("")
            trail.sent(ID_A, "relay.example.com")
            trail.failed(ID_B, MessageTrail.Failure.DAEMON_ERROR, "Conversation not found: my secret")

            assertEquals(
                listOf(
                    "$T id=$ID_A state=sent conn=none",
                    "$T id=$ID_B state=failed reason=daemon_error code=unknown",
                ),
                logcat,
            )
            trail.dispose()
        }

    /** Emits with `RelayLog`'s gate shut, so the trail cannot ride it, and that gate is still `BuildConfig.DEBUG`. */
    @Test
    fun emits_whateverTheDebugGateSays() =
        runTest {
            assertEquals(BuildConfig.DEBUG, RelayLog.enabled)
            val gate = RelayLog.enabled
            RelayLog.enabled = false
            try {
                val logcat = mutableListOf<String>()
                val trail = newTrail(null, logcat = logcat)
                trail.sent(ID_A, "1a2b3c4d")
                assertEquals(listOf("$T id=$ID_A state=sent conn=1a2b3c4d"), logcat)
                trail.dispose()
            } finally {
                RelayLog.enabled = gate
            }
        }

    /**
     * The release half of the proof. This project runs unit tests in the debug variant only, and
     * `BuildConfig.DEBUG` is a compile-time constant inlined into the bytecode, so neither a test run nor
     * the compiled class can show a release-only gate. The source can: neither the trail, the code that
     * records into it, nor its `AppModule` binding reads `BuildConfig`. `./gradlew check` runs this.
     */
    @Test
    fun theTrailAndItsCallers_neverReadBuildConfig() {
        val root = File("src/main/java/de/pyryco/mobile")
        val sources =
            listOf(
                "data/diagnostics/MessageTrail.kt",
                "data/repository/MessageCommands.kt",
                "data/repository/ThreadProjection.kt",
                "data/repository/RelayRequests.kt",
            ).map { File(root, it).readText() }
        // BuildConfig lives in another package, so any read here needs its import or its full name.
        sources.forEach { assertFalse(it.contains("de.pyryco.mobile.BuildConfig")) }
        val appModule = File(root, "di/AppModule.kt").readText()
        val binding = appModule.substringAfter("// #1564").substringBefore("onClose")
        assertTrue(binding.contains("MessageTrail("))
        assertFalse(binding.contains("BuildConfig"))
    }

    private fun TestScope.newTrail(
        file: File?,
        maxBytes: Long = MessageTrail.DEFAULT_MAX_BYTES,
        logcat: MutableList<String> = mutableListOf(),
    ): MessageTrail =
        MessageTrail(
            file = { file },
            writerDispatcher = StandardTestDispatcher(testScheduler),
            maxBytes = maxBytes,
            now = { Instant.parse(T) },
            logcat = { logcat += it },
        )

    private companion object {
        const val T = "2026-10-03T10:00:00Z"
        const val ID_A = "0d3b7f62-6a3e-4c1e-9d0f-2b8e4a1c5f70"
        const val ID_B = "7c9e2a41-1f5b-4d8a-b3c6-9e0f1a2b3c4d"
        const val ID_C = "e4f5a6b7-c8d9-4e0f-a1b2-c3d4e5f6a7b8"
    }
}
