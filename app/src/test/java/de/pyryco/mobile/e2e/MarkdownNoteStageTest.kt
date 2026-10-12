package de.pyryco.mobile.e2e

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownNoteStageTest {
    @Test
    fun successRunsOnceAndRecordsCompletion() {
        val records = mutableListOf<String>()
        var calls = 0
        val value = markdownNoteStep(MarkdownNoteStage.Refresh, records::add) { ++calls }
        assertEquals(1, value)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                "event=markdown_note_step stage=Refresh outcome=start",
                "event=markdown_note_step stage=Refresh outcome=complete",
            ),
            records,
        )
    }

    @Test
    fun failurePreservesTheOriginalCauseAndNeverRetries() {
        val records = mutableListOf<String>()
        val failure = AssertionError("private fixture contents")
        var calls = 0
        val thrown =
            runCatching {
                markdownNoteStep(MarkdownNoteStage.FirstOpen, records::add) {
                    calls++
                    throw failure
                }
            }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                "event=markdown_note_step stage=FirstOpen outcome=start",
                "event=markdown_note_step stage=FirstOpen outcome=failure",
            ),
            records,
        )
    }

    @Test
    fun cancellationPropagatesUnchanged() {
        val records = mutableListOf<String>()
        val cancelled = CancellationException("private peer state")
        val thrown = runCatching { markdownNoteStep(MarkdownNoteStage.PeerConnection, records::add) { throw cancelled } }.exceptionOrNull()
        assertSame(cancelled, thrown)
        assertEquals(
            listOf(
                "event=markdown_note_step stage=PeerConnection outcome=start",
                "event=markdown_note_step stage=PeerConnection outcome=failure",
            ),
            records,
        )
    }
}
