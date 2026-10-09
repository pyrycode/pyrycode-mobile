package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplySuggestionProgressTest {
    @Test fun `every unanswered checkpoint is named at its original deadline`() =
        runTest {
            for (stage in ReplySuggestionStage.entries) {
                var reads = 0
                val progress =
                    ReplySuggestionProgress {
                        reads++
                        "offers=0"
                    }
                progress.at(stage)
                val start = currentTime
                val failure =
                    runCatching {
                        progress.run { withTimeout(90_000) { awaitCancellation() } }
                    }.exceptionOrNull()
                assertEquals("reply-suggestion checkpoint=$stage timed out; offers=0", failure?.message)
                assertTrue(failure?.cause is TimeoutCancellationException)
                assertEquals(90_000, currentTime - start)
                assertEquals(1, reads)
            }
        }

    @Test fun `combined session and offer deadline identifies the inner offer checkpoint`() =
        runTest {
            val progress = ReplySuggestionProgress { "offers=1" }
            val failure =
                runCatching {
                    progress.run {
                        withTimeout(90_000) {
                            progress.at(ReplySuggestionStage.SessionReady)
                            kotlinx.coroutines.delay(15_000)
                            progress.at(ReplySuggestionStage.DaemonOffer)
                            awaitCancellation()
                        }
                    }
                }.exceptionOrNull()
            assertEquals("reply-suggestion checkpoint=DaemonOffer timed out; offers=1", failure?.message)
            assertEquals(90_000, currentTime)
        }

    @Test fun `Compose timeout names the checkpoint without copying its message`() {
        val timeout = ComposeTimeoutException("private-content-sentinel")
        val progress = ReplySuggestionProgress { "clears=1" }
        progress.at(ReplySuggestionStage.PlaceholderRemoved)
        val failure = runCatching { progress.run { throw timeout } }.exceptionOrNull()
        assertEquals("reply-suggestion checkpoint=PlaceholderRemoved timed out; clears=1", failure?.message)
        assertSame(timeout, failure?.cause)
    }

    @Test fun `success refusals and ordinary cancellation never read diagnostics`() {
        val progress = ReplySuggestionProgress { error("must stay lazy") }
        val result = Any()
        assertSame(result, progress.run { result })
        for (failure in listOf(IllegalStateException("refused"), CancellationException("cancelled"))) {
            assertSame(failure, runCatching { progress.run { throw failure } }.exceptionOrNull())
        }
    }

    @Test fun `wire witness distinguishes nonempty offers clears and user messages without payload text`() {
        val frames =
            listOf(
                frame(
                    "reply_suggestion",
                    """{"conversation_id":"$C","session_id":"$S","revision":1,"suggested_reply":"private-content-sentinel"}""",
                ),
                frame("reply_suggestion", """{"conversation_id":"$C","session_id":"$S","revision":2,"suggested_reply":null}"""),
                frame("reply_suggestion", """{"suggested_reply":"private-malformed-sentinel"}"""),
                frame("message", """{"role":"user","text":"private-content-sentinel"}"""),
                frame("message", """{"role":"assistant","text":"private-content-sentinel"}"""),
                frame("turn_end", "{}"),
            )
        assertEquals("offers=1 clears=1 malformed=1 user_messages=1 turn_ends=1", replySuggestionWireEvidence(frames))
        assertEquals("offers=0 clears=0 malformed=0 user_messages=0 turn_ends=0", replySuggestionWireEvidence(emptyList()))
    }

    private fun frame(
        type: String,
        payload: String,
    ) = Envelope(id = 1, type = type, ts = "2026-10-07T00:00:00Z", payload = MobileJson.parseToJsonElement(payload))

    private companion object {
        const val C = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val S = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
