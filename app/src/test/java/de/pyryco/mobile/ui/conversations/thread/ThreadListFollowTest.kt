package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1314: following is derived from the list's position on every scroll, and growth while following pins
 * the newest end. Under reverseLayout an insert at index 0 moves the anchor's index but keeps its key and
 * offset, so growth never reads as a scroll.
 */
class ThreadListFollowTest {
    private fun frame(
        key: Any? = "msg:30",
        index: Int = 0,
        offset: Int = 0,
        content: Any? = "msg:30",
    ) = ListFrame(anchorKey = key, anchorIndex = index, anchorOffset = offset, content = content)

    private fun step(
        previous: ListFrame?,
        current: ListFrame,
        following: Boolean,
    ) = followStep(previous, current, following, TOLERANCE)

    @Test
    fun theFirstFrameDecidesFromPosition_andNeverPins() {
        assertEquals(FollowStep(following = true, pin = false), step(null, frame(), following = false))
        assertEquals(FollowStep(following = true, pin = false), step(null, frame(offset = TOLERANCE), following = false))
        assertEquals(FollowStep(following = false, pin = false), step(null, frame(index = 5), following = true))
        assertEquals(FollowStep(following = false, pin = false), step(null, frame(offset = TOLERANCE + 1), following = true))
    }

    @Test
    fun aNewRowAtTheNewestEnd_pinsWhileFollowing_andNotOtherwise() {
        val before = frame()
        val grown = frame(index = 1, content = "msg:31")
        assertEquals(FollowStep(following = true, pin = true), step(before, grown, following = true))
        assertEquals(FollowStep(following = false, pin = false), step(before, grown, following = false))
    }

    @Test
    fun theAnchorGrowing_pinsWhileFollowing() {
        val before = frame(content = "msg:30" to 100)
        val grown = frame(content = "msg:30" to 140)
        assertEquals(FollowStep(following = true, pin = true), step(before, grown, following = true))
        assertEquals(FollowStep(following = false, pin = false), step(before, grown, following = false))
    }

    @Test
    fun aScrollRecomputesFromPosition_withinTheToleranceStillFollowing() {
        val atEnd = frame()
        assertEquals(FollowStep(following = true, pin = false), step(atEnd, frame(offset = TOLERANCE), following = true))
        assertEquals(FollowStep(following = false, pin = false), step(atEnd, frame(offset = TOLERANCE + 1), following = true))
        assertEquals(FollowStep(following = false, pin = false), step(atEnd, frame(key = "msg:20", index = 10), following = true))
        // A drag back that ends within the tolerance counts as at the end.
        assertEquals(FollowStep(following = true, pin = false), step(frame(offset = 80), frame(offset = 2), following = false))
    }

    @Test
    fun anUnchangedFrame_suchAsAnOverscrollAtTheEnd_leavesFollowingAlone() {
        assertEquals(FollowStep(following = true, pin = false), step(frame(), frame(), following = true))
    }

    @Test
    fun aRefusedPin_leavesFollowingOn_andTheNextGrowthPins() {
        // The pin was refused: the anchor kept its key and offset at index 1.
        val refused = frame(index = 1, content = "msg:31")
        val delta = frame(index = 1, content = "msg:31" to 200)
        assertEquals(FollowStep(following = true, pin = true), step(refused, delta, following = true))
    }

    @Test
    fun aPinLandingAtTheEnd_keepsFollowing() {
        val refused = frame(index = 1, content = "msg:31")
        assertEquals(FollowStep(following = true, pin = false), step(refused, frame(key = "msg:31", content = "msg:31"), following = true))
    }

    private companion object {
        const val TOLERANCE = 11
    }
}
