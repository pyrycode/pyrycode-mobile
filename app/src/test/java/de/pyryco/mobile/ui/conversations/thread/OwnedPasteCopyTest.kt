package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedPasteCopyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldSink = RelayLog.sink

    @Before fun quietLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
    }

    @Test fun capturedBytesRemainReadableAfterTheOriginalGrantIsRevoked() =
        runTest {
            var readable = true
            val bytes = byteArrayOf(1, 2, 3, 4)
            val reader = AttachmentReader { if (readable) AttachmentRead.Bytes(bytes) else AttachmentRead.Unreadable }
            val capture = OwnedPasteCopy.capture(temporary.root, reader, IMAGE_URI, StandardTestDispatcher(testScheduler))
            val copy = (capture as PasteCopyCapture.Captured).copy
            readable = false
            assertEquals(AttachmentRead.Unreadable, reader.read(IMAGE_URI))
            assertArrayEquals(bytes, (copy.read(StandardTestDispatcher(testScheduler)) as AttachmentRead.Bytes).bytes)
            assertFalse(copy.toString().contains(temporary.root.path))
            copy.release()
            assertEquals(
                0,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun actualBytesOverTheBoundLeaveNoCopy() =
        runTest {
            val capture =
                OwnedPasteCopy.capture(
                    temporary.root,
                    AttachmentReader {
                        AttachmentRead.Bytes(ByteArray(AttachmentUploadLimit.MAX_BYTES + 1))
                    },
                    IMAGE_URI,
                    StandardTestDispatcher(testScheduler),
                )
            assertEquals(PasteCopyCapture.TooLarge, capture)
            assertEquals(
                0,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun unreadableAndCancelledCaptureLeaveNoCopy() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            assertEquals(
                PasteCopyCapture.Unreadable,
                OwnedPasteCopy.capture(
                    temporary.root,
                    AttachmentReader { AttachmentRead.Unreadable },
                    IMAGE_URI,
                    dispatcher,
                ),
            )
            try {
                OwnedPasteCopy.capture(
                    temporary.root,
                    AttachmentReader { throw CancellationException() },
                    IMAGE_URI,
                    dispatcher,
                )
                fail("cancellation must propagate")
            } catch (_: CancellationException) {
            }
            assertEquals(
                0,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun removalWaitsForSendReferenceAndKeepsOtherDrafts() =
        runTest {
            val first = captured()
            val second = captured()
            val store = ComposerDraftStore()
            store.addAttachment("host", "chat", "content://foreign/one", "one.png", "image/png", 3, first)
            store.addAttachment("host", "other", "content://foreign/two", "two.png", "image/png", 3, second)
            assertTrue(first.retain())
            store.clearConversation("host", "chat")
            assertEquals(
                2,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            first.release()
            assertEquals(AttachmentRead.Unreadable, first.read(StandardTestDispatcher(testScheduler)))
            assertTrue(second.read(StandardTestDispatcher(testScheduler)) is AttachmentRead.Bytes)
            store.clearHost("host")
            assertEquals(
                0,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun countRefusalDiscardsTheUnpublishedCopy() =
        runTest {
            val store = ComposerDraftStore()
            repeat(MessageAttachmentIds.MAX) { store.addAttachment("host", "chat", "content://foreign/$it", "file", "image/png", 1) }
            val copy = captured()
            assertEquals(
                AttachmentAddOutcome.TOO_MANY,
                store.addAttachment("host", "chat", "content://foreign/new", "file", "image/png", 3, copy),
            )
            assertEquals(
                0,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun processStartupDropsOnlyTheDedicatedLeftovers() {
        val directory = File(temporary.root, "paste-copies").apply { mkdirs() }
        File(directory, "abandoned").writeText("private")
        val other = File(temporary.root, "other").apply { writeText("keep") }
        OwnedPasteCopy.clearLeftovers(directory)
        assertEquals(0, directory.listFiles().orEmpty().size)
        assertEquals("keep", other.readText())
    }

    @Test fun cancellationOnReturnFromIoDiscardsTheCompletedUnpublishedFile() =
        runTest {
            var ioWork: Runnable? = null
            val io =
                object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        ioWork = block
                    }
                }
            val job =
                launch {
                    OwnedPasteCopy.capture(temporary.root, AttachmentReader { AttachmentRead.Bytes(byteArrayOf(7)) }, IMAGE_URI, io)
                }
            runCurrent()
            requireNotNull(ioWork).run()
            assertEquals(
                1,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            job.cancel()
            advanceUntilIdle()
            assertEquals(
                0,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun copyWriteFailureLeavesNoPartialFile() =
        runTest {
            val notADirectory = File(temporary.root, "file").apply { writeText("keep") }
            assertEquals(
                PasteCopyCapture.Unreadable,
                OwnedPasteCopy.capture(
                    notADirectory,
                    AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1)) },
                    IMAGE_URI,
                    StandardTestDispatcher(testScheduler),
                ),
            )
            assertEquals("keep", notADirectory.readText())
            assertEquals(
                1,
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun lastOwnerCannotDeleteWhileAnotherCallerIsReading() = runTest {
        val copy = captured()
        val opened = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val releaseStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val read = workers.submit<ByteArray?> {
                copy.withInput { input ->
                    opened.countDown()
                    check(finishRead.await(5, TimeUnit.SECONDS))
                    input.readBytes()
                }
            }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            workers.submit {
                releaseStarted.countDown()
                copy.release()
                released.countDown()
            }
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
            assertFalse(released.await(100, TimeUnit.MILLISECONDS))
            finishRead.countDown()
            assertArrayEquals(byteArrayOf(1, 2, 3), read.get(5, TimeUnit.SECONDS))
            assertTrue(released.await(5, TimeUnit.SECONDS))
            assertEquals(0, temporary.root.listFiles().orEmpty().size)
        } finally {
            finishRead.countDown()
            workers.shutdownNow()
        }
    }

    private companion object {
        const val IMAGE_URI = "content://foreign/image"
    }

    private suspend fun captured(): OwnedPasteCopy =
        (
            OwnedPasteCopy.capture(
                temporary.root,
                AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1, 2, 3)) },
                IMAGE_URI,
                kotlinx.coroutines.Dispatchers.Unconfined,
            ) as PasteCopyCapture.Captured
        ).copy
}
