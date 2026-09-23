package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ResetStatus.Handoff
import de.pyryco.mobile.data.repository.ResetStatus.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label selection behind `ResettingIndicator` (#872): every reachable [ResetStatus] maps to one local
 * string resource. The wire carries `phase` and `handoff` independently, so the mapping stays total over
 * both enums rather than assuming the documented pairings.
 */
class ResettingIndicatorLabelTest {
    @Test
    fun `wrapping up reads as writing a handoff note`() {
        assertEquals(R.string.thread_resetting_wrapping_up, resettingLabelRes(ResetStatus(Phase.WrappingUp, Handoff.Pending)))
    }

    @Test
    fun `wrapping up ignores an early handoff outcome`() {
        assertEquals(R.string.thread_resetting_wrapping_up, resettingLabelRes(ResetStatus(Phase.WrappingUp, Handoff.Written)))
        assertEquals(R.string.thread_resetting_wrapping_up, resettingLabelRes(ResetStatus(Phase.WrappingUp, Handoff.Skipped)))
    }

    @Test
    fun `restarting with a written note says the note was saved`() {
        assertEquals(
            R.string.thread_resetting_restarting_written,
            resettingLabelRes(ResetStatus(Phase.Restarting, Handoff.Written)),
        )
    }

    @Test
    fun `restarting with a skipped note says there is no note`() {
        assertEquals(
            R.string.thread_resetting_restarting_skipped,
            resettingLabelRes(ResetStatus(Phase.Restarting, Handoff.Skipped)),
        )
    }

    @Test
    fun `restarting with an unresolved outcome claims no outcome`() {
        assertEquals(R.string.thread_resetting_restarting, resettingLabelRes(ResetStatus(Phase.Restarting, Handoff.Pending)))
    }
}
