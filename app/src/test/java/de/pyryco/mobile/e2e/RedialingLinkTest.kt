package de.pyryco.mobile.e2e

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1036: the live second client redials a link that ends, and a request follows it only when it is safe to. */
class RedialingLinkTest {
    /** One dialed link; it ends when [end] completes with a label. */
    private class Link(
        val n: Int,
    ) {
        val end = CompletableDeferred<String>()
        var released = false
    }

    /** Dials links numbered from 1; attempt numbers in [failing] do not settle. */
    private class Dialer(
        private val failing: Set<Int> = emptySet(),
    ) {
        var attempts = 0
        val settled = mutableListOf<Link>()

        fun dial(): Link? {
            attempts += 1
            if (attempts in failing) return null
            return Link(attempts).also { settled += it }
        }
    }

    private fun TestScope.redialing(
        dialer: Dialer,
        scope: CoroutineScope = backgroundScope,
    ) = RedialingLink(
        scope = scope,
        dial = { dialer.dial() },
        awaitEnd = { it.end.await() },
        release = { it.released = true },
    )

    @Test
    fun `start dials past attempts that do not settle and publishes the first settled link`() =
        runTest {
            val dialer = Dialer(failing = setOf(1, 2))
            val link = redialing(dialer)

            link.start()

            assertEquals(3, dialer.attempts)
            assertSame(dialer.settled.single(), link.current.value)
            assertTrue(link.describe(), link.describe().startsWith("session open"))
        }

    @Test
    fun `a settled link that ends is replaced and the replacement is described`() =
        runTest {
            val dialer = Dialer()
            val link = redialing(dialer)
            link.start()
            val first = dialer.settled.single()

            first.end.complete("clean close")
            runCurrent()
            assertNull("the ended link is still published", link.current.value)
            assertTrue(link.describe(), link.describe().contains("clean close"))

            advanceTimeBy(REDIAL_MS)
            runCurrent()
            val second = dialer.settled.last()
            assertSame(second, link.current.value)
            assertEquals("session open (link 2, replaced 1×)", link.describe())
        }

    @Test
    fun `a resendable request is retried on the replacement after its link ended`() =
        runTest {
            val dialer = Dialer()
            val link = redialing(dialer)
            link.start()
            val tried = mutableListOf<Int>()

            val reply =
                async {
                    link.request("request_history", resend = true) { current ->
                        tried += current.n
                        if (current.n == 1) {
                            current.end.complete("clean close")
                            RedialingLink.Attempt.Ended
                        } else {
                            RedialingLink.Attempt.Answered("page from ${current.n}")
                        }
                    }
                }
            advanceUntilIdle()

            assertEquals("page from 2", reply.await())
            assertEquals(listOf(1, 2), tried)
        }

    @Test
    fun `a request that is not resendable fails naming itself after its link ended`() =
        runTest {
            val dialer = Dialer()
            val link = redialing(dialer)
            link.start()

            val failure =
                runCatching {
                    link.request<String>("send_message", resend = false) { current ->
                        current.end.complete("clean close")
                        RedialingLink.Attempt.Ended
                    }
                }.exceptionOrNull()

            assertTrue("expected an AssertionError, got $failure", failure is AssertionError)
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().startsWith("send_message:"))
        }

    @Test
    fun `a request the link refused is retried on the replacement even when it is not resendable`() =
        runTest {
            val dialer = Dialer()
            val link = redialing(dialer)
            link.start()

            val reply =
                async {
                    link.request("send_message", resend = false) { current ->
                        if (current.n == 1) {
                            current.end.complete("clean close")
                            RedialingLink.Attempt.NotSent
                        } else {
                            RedialingLink.Attempt.Answered("ack")
                        }
                    }
                }
            advanceUntilIdle()

            assertEquals("ack", reply.await())
        }

    @Test
    fun `close releases the current link and stops redialing`() =
        runTest {
            val dialer = Dialer()
            val link = redialing(dialer)
            link.start()
            val first = dialer.settled.single()

            link.close()
            first.end.complete("clean close")
            advanceTimeBy(REDIAL_MS)
            runCurrent()

            assertTrue("the live link was not released", first.released)
            assertEquals(1, dialer.attempts)
            assertFalse(link.describe(), link.describe().startsWith("session open"))
        }

    private companion object {
        /** Past the first redial's backoff; `backgroundScope` work does not count toward `advanceUntilIdle`. */
        const val REDIAL_MS = 2_000L
    }
}
