package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentContent
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The host-keyed attachment store (#899): verified bytes kept under a host's directory by temp-then-rename,
 * returned again without a fetch, one fetch shared by concurrent callers, and nothing kept on failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

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

    private val bytes = ByteArray(45_001) { (it % 251).toByte() }

    @Test
    fun success_keepsExactlyTheBytes_underTheHost_andLeavesNoTemporaryFile() =
        runTest {
            val store = store()
            var fetches = 0
            val result =
                store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                    fetches++
                    fetched(bytes)
                } as AttachmentRetrievalResult.Retrieved

            assertEquals(1, fetches)
            assertArrayEquals(bytes, result.file.readBytes())
            assertEquals(ATTACHMENT_ID, result.file.name)
            assertEquals("notes.txt", result.displayName)
            assertEquals("text/plain", result.mimeType)
            assertTrue(result.file.canonicalPath.startsWith(root().canonicalPath + File.separator))
            assertTrue(root().walkTopDown().none { it.name.endsWith(".part") })
            assertFalse("the remote name never becomes a path", root().walkTopDown().any { it.name == "notes.txt" })
        }

    @Test
    fun keptFile_isReturnedWithoutFetching_evenByAnotherStoreOverTheSameRoot() =
        runTest {
            store().retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { fetched(bytes) }

            val again = store().retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { error("must not fetch") }

            assertArrayEquals(bytes, (again as AttachmentRetrievalResult.Retrieved).file.readBytes())
            assertEquals("notes.txt", again.displayName)
        }

    @Test
    fun concurrentRetrievals_shareOneFetch_andOneOutcome() =
        runTest {
            val store = store()
            val gate = CompletableDeferred<AttachmentFetchResult>()
            var fetches = 0
            val first =
                async {
                    store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                        fetches++
                        gate.await()
                    }
                }
            val second =
                async {
                    store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                        fetches++
                        gate.await()
                    }
                }
            runCurrent()

            gate.complete(fetched(bytes))

            assertEquals(first.await(), second.await())
            assertEquals(1, fetches)
        }

    @Test
    fun concurrentFailure_isSharedToo() =
        runTest {
            val store = store()
            val gate = CompletableDeferred<AttachmentFetchResult>()
            var fetches = 0
            val first =
                async {
                    store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                        fetches++
                        gate.await()
                    }
                }
            val second =
                async {
                    store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                        fetches++
                        gate.await()
                    }
                }
            runCurrent()
            gate.complete(AttachmentRetrievalResult.Unavailable)

            assertEquals(AttachmentRetrievalResult.Unavailable, first.await())
            assertEquals(AttachmentRetrievalResult.Unavailable, second.await())
            assertEquals(1, fetches)
        }

    @Test
    fun cancelledLeader_handsTheFetchToAWaitingFollower() =
        runTest {
            val store = store()
            val never = CompletableDeferred<AttachmentFetchResult>()
            val leader = async { store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { never.await() } }
            runCurrent()
            val follower = async { store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { fetched(bytes) } }
            runCurrent()

            leader.cancel()

            assertTrue(follower.await() is AttachmentRetrievalResult.Retrieved)
        }

    @Test
    fun everyFailure_leavesNoFile_andALaterRetrievalFetchesAgain() =
        runTest {
            val store = store()
            listOf(
                AttachmentRetrievalResult.NotFound,
                AttachmentRetrievalResult.TooLarge,
                AttachmentRetrievalResult.Invalid,
                AttachmentRetrievalResult.Unavailable,
            ).forEach { failure ->
                assertEquals(failure, store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { failure })
                assertTrue(root().walkTopDown().none { it.isFile })
            }
            var fetches = 0
            store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                fetches++
                fetched(bytes)
            }
            assertEquals(1, fetches)
        }

    @Test
    fun writeFailure_isRetryable_andKeepsNothing() =
        runTest {
            val blocked = File(folder.root, "blocked").apply { writeText("a file where a directory must go") }
            val store = AttachmentStore(blocked, StandardTestDispatcher(testScheduler))

            assertEquals(AttachmentRetrievalResult.Unavailable, store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { fetched(bytes) })
            assertTrue(blocked.isFile)
            assertEquals(listOf("event=attachment_store_failed id=$ATTACHMENT_ID"), logs)
        }

    @Test
    fun idsOfTheWrongShape_neverFetch_andBuildNoPath() =
        runTest {
            val store = store()
            listOf("../../x", "", ATTACHMENT_ID.uppercase(), "$ATTACHMENT_ID/..").forEach { bad ->
                assertEquals(AttachmentRetrievalResult.NotFound, store.retrieve(SERVER_A, CONVERSATION_ID, bad) { error("must not fetch") })
                assertEquals(AttachmentRetrievalResult.NotFound, store.retrieve(SERVER_A, bad, ATTACHMENT_ID) { error("must not fetch") })
            }
            assertFalse(root().exists())
        }

    @Test
    fun hostsAreSeparate_andTheServerIdIsNeverAPathComponent() =
        runTest {
            val store = store()
            val a = store.retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { fetched(bytes) } as AttachmentRetrievalResult.Retrieved
            var fetchedForB = false
            val b =
                store.retrieve(SERVER_B, CONVERSATION_ID, ATTACHMENT_ID) {
                    fetchedForB = true
                    fetched(ByteArray(2))
                } as AttachmentRetrievalResult.Retrieved

            assertTrue(fetchedForB)
            assertNotEquals(a.file.parentFile?.parentFile, b.file.parentFile?.parentFile)
            assertTrue(root().walkTopDown().none { it.name.contains("server") })
            assertArrayEquals(bytes, a.file.readBytes())
        }

    @Test
    fun keptContentWithoutReadableMetadata_isFetchedAgain() =
        runTest {
            val first = store().retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) { fetched(bytes) } as AttachmentRetrievalResult.Retrieved
            File(first.file.parentFile, "$ATTACHMENT_ID.meta.json").writeText("{not json")
            var fetches = 0
            store().retrieve(SERVER_A, CONVERSATION_ID, ATTACHMENT_ID) {
                fetches++
                fetched(bytes)
            }
            assertEquals(1, fetches)
        }

    private fun TestScope.store() = AttachmentStore(root(), StandardTestDispatcher(testScheduler))

    private fun root() = File(folder.root, "attachments")

    private fun fetched(bytes: ByteArray) = AttachmentFetchResult.Fetched(AttachmentContent(listOf(bytes)), "notes.txt", "text/plain")

    private companion object {
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
        const val CONVERSATION_ID = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"
        const val ATTACHMENT_ID = "7c1d5e92-4a30-4b8f-9e21-6d4c3b0a8f55"
    }
}
