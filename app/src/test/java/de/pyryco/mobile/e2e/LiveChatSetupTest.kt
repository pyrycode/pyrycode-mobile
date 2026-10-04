package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.RelayLinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveChatSetupTest {
    private val live = MutableStateFlow<Any?>(Any())
    private val relay = MutableStateFlow<RelayLinkStatus>(RelayLinkStatus.Connected)

    @Test
    fun `missing repository identifies readiness rather than task assertion`() =
        runTest {
            live.value = null
            val failure = runCatching { createChat() }.exceptionOrNull()
            assertTrue(failure is AssertionError)
            assertEquals(
                "chat setup step 'await live repository' timed out; repository present=false; relay=Connected",
                failure?.message,
            )
        }

    @Test
    fun `stalled create identifies creation and failure time connection state`() =
        runTest {
            val read = async { runCatching { createChat(create = { awaitCancellation() }) }.exceptionOrNull() }
            runCurrent()
            relay.value = RelayLinkStatus.PairingRejected
            live.value = null
            advanceTimeBy(TIMEOUT_MS)
            runCurrent()
            assertEquals(
                "chat setup step 'create discussion' timed out; repository present=false; relay=PairingRejected",
                read.await()?.message,
            )
        }

    @Test
    fun `stalled rename excludes daemon metadata and object contents`() =
        runTest {
            live.value =
                object {
                    override fun toString(): String = "secret repository"
                }
            relay.value = RelayLinkStatus.UpdateRequired("secret daemon metadata")
            val failure = runCatching { createChat(rename = { _, _ -> awaitCancellation() }) }.exceptionOrNull()
            assertTrue(failure is AssertionError)
            assertEquals(
                "chat setup step 'rename discussion' timed out; repository present=true; relay=UpdateRequired",
                failure?.message,
            )
        }

    @Test
    fun `replacement retry creates and renames on the new repository`() =
        runTest {
            val first = live.value
            val second = Any()
            val createdOn = mutableListOf<Any>()
            val read =
                async {
                    createChat(
                        create = {
                            createdOn += it
                            "chat"
                        },
                        rename = { source, chat ->
                            if (source === first) error("link ended")
                            assertSame(second, source)
                            "$chat renamed"
                        },
                    )
                }
            runCurrent()
            assertFalse(read.isCompleted)
            live.value = second
            assertEquals("chat renamed", read.await())
            assertEquals(listOf(first, second), createdOn)
        }

    @Test
    fun `stalled retry identifies replacement wait`() =
        runTest {
            val failure = runCatching { createChat(create = { error("link ended") }) }.exceptionOrNull()
            assertEquals(
                "chat setup step 'await replacement repository' timed out; repository present=true; relay=Connected",
                failure?.message,
            )
        }

    @Test
    fun `success returns renamed chat once`() =
        runTest {
            var creates = 0
            assertEquals(
                "chat renamed",
                createChat(create = {
                    creates++
                    "chat"
                }),
            )
            assertEquals(1, creates)
        }

    @Test
    fun `non timeout failure propagates unchanged`() =
        runTest {
            val refused = IllegalStateException("refused")
            val failure =
                runCatching {
                    live.createLiveChatWithDiagnostics(TIMEOUT_MS, 1, relay, { throw refused }, { _, chat: String -> chat })
                }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it === refused })
        }

    @Test
    fun `external cancellation is not converted to setup assertion`() =
        runTest {
            val read = async { createChat(create = { awaitCancellation() }) }
            runCurrent()
            val cancelled = CancellationException("test ended")
            read.cancel(cancelled)
            val failure = runCatching { read.await() }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it === cancelled })
        }

    @Test
    fun `caller timeout propagates as cancellation`() =
        runTest {
            val failure = runCatching { withTimeout(TIMEOUT_MS / 2) { createChat(create = { awaitCancellation() }) } }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
        }

    private suspend fun createChat(
        create: suspend (Any) -> String = { "chat" },
        rename: suspend (Any, String) -> String = { _, chat -> "$chat renamed" },
    ): String = live.createLiveChatWithDiagnostics(TIMEOUT_MS, TIMEOUT_MS * 2, relay, create, rename)

    private companion object {
        const val TIMEOUT_MS = 100L
    }
}
