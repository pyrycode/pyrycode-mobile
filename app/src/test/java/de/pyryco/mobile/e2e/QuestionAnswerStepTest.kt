package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
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
class QuestionAnswerStepTest {
    @Test
    fun `every unanswered operation identifies its stage at the original deadline`() =
        runTest {
            for (stage in QuestionAnswerStage.entries) {
                val started = currentTime
                var stateReads = 0
                val failure =
                    runCatching {
                        questionAnswerStep(stage, {
                            stateReads += 1
                            LINK_STATE
                        }) {
                            withTimeout(DEADLINE_MS) { awaitCancellation() }
                        }
                    }.exceptionOrNull()

                assertTrue("the wait must identify ${stage.label}", failure is AssertionError)
                assertEquals("question-answer step '${stage.label}' timed out; $LINK_STATE", failure?.message)
                assertTrue("retain the failing coroutine wait as cause", failure?.cause is TimeoutCancellationException)
                assertEquals(DEADLINE_MS, currentTime - started)
                assertEquals(1, stateReads)
            }
        }

    @Test
    fun `a Compose timeout names its phone operation without copying exception text`() {
        val timeout = ComposeTimeoutException("untrusted fixture text must stay in the original cause")
        val failure =
            runCatching {
                questionAnswerStep(QuestionAnswerStage.SubmitPhoneAnswer, { LINK_STATE }) { throw timeout }
            }.exceptionOrNull()

        assertEquals(
            "question-answer step 'select phone option and submit enabled Continue' timed out; $LINK_STATE",
            failure?.message,
        )
        assertSame(timeout, failure?.cause)
    }

    @Test
    fun `successful steps return values without reading diagnostics`() {
        val answer = Any()

        val result = questionAnswerStep(QuestionAnswerStage.AwaitFirstQuestion, { error("diagnostic must be lazy") }) { answer }

        assertSame(answer, result)
    }

    @Test
    fun `refusals and ordinary cancellation keep their original failure`() {
        for (failure in listOf(IllegalStateException("static refusal"), CancellationException("scenario cancelled"))) {
            val caught =
                runCatching {
                    questionAnswerStep(QuestionAnswerStage.SubmitPeerAnswer, { error("diagnostic must be lazy") }) { throw failure }
                }.exceptionOrNull()

            assertSame(failure, caught)
        }
    }

    private companion object {
        const val DEADLINE_MS = 30_000L
        const val LINK_STATE = "session open (link 2, replaced 1×)"
    }
}
