package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadFoldArrivalTest {
    @Test
    fun syntheticTimestamp_isFirstArrival_withOrWithoutHistory() {
        val history = ThreadItem.MessageItem(Message("old", "s1", Role.User, "Question", HISTORY, isStreaming = false))
        for (finished in listOf(emptyList(), listOf(history))) {
            val fold = ThreadFold(finished, null).reduce(delta("reply", 1, ARRIVAL), "c1")
            assertEquals(ARRIVAL, fold.syntheticTimestamp())
        }
    }

    @Test
    fun arrivalSurvivesAppendBackfillDuplicateAndTurnEnd_newTurnUsesItsOwnArrival() {
        var fold = ThreadFold(emptyList(), null).reduce(delta("reply", 1, ARRIVAL), "c1")
        fold = fold.reduce(delta("reply", 2, LATER), "c1")
        assertEquals(ARRIVAL, fold.syntheticTimestamp())
        val backfill = ThreadItem.MessageItem(Message("old", "s1", Role.User, "Question", HISTORY, isStreaming = false))
        fold = fold.reduce(ThreadInput.Finished(listOf(backfill)), "c1")
        assertEquals(ARRIVAL, fold.syntheticTimestamp())
        fold = fold.reduce(delta("reply", 1, LATER), "c1")
        assertEquals(ARRIVAL, fold.syntheticTimestamp())
        fold = fold.reduce(ThreadInput.Live(LiveSessionEvent.TurnEnd("c1", "reply", "end_turn"), LATER), "c1")
        assertEquals(ARRIVAL, fold.syntheticTimestamp())

        fold = fold.reduce(delta("next", 1, LATER), "c1")
        assertEquals(LATER, fold.syntheticTimestamp())
    }

    private fun delta(
        turnId: String,
        seq: Int,
        receivedAt: Instant,
    ) = ThreadInput.Live(LiveSessionEvent.AssistantDelta("c1", turnId, seq, "Text"), receivedAt)

    private fun ThreadFold.syntheticTimestamp() = (render().last() as ThreadItem.MessageItem).message.timestamp

    private companion object {
        val HISTORY = Instant.parse("2026-01-01T00:00:00Z")
        val ARRIVAL = Instant.parse("2026-10-04T17:00:00Z")
        val LATER = Instant.parse("2026-10-04T17:01:00Z")
    }
}
