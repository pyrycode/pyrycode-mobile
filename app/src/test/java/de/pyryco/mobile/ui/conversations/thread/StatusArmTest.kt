package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1311: the status band's one arm order, desktop's `workingIndicatorState` plus Mobile's own arms. */
class StatusArmTest {
    private fun arm(
        connectionState: ConnectionState = ConnectionState.Connected,
        resetting: Boolean = false,
        apiRetrying: Boolean = false,
        isCompacting: Boolean = false,
        isStalled: Boolean = false,
        hasTurnOutcome: Boolean = false,
        isThinking: Boolean = false,
        isBusy: Boolean = false,
        localSendPending: Boolean = false,
        hasOpenTool: Boolean = false,
    ): StatusArm =
        statusArm(
            connectionState = connectionState,
            resetting = resetting,
            apiRetrying = apiRetrying,
            isCompacting = isCompacting,
            isStalled = isStalled,
            hasTurnOutcome = hasTurnOutcome,
            isThinking = isThinking,
            isBusy = isBusy,
            localSendPending = localSendPending,
            hasOpenTool = hasOpenTool,
        )

    @Test
    fun nothingLive_showsNoReading() {
        assertEquals(StatusArm.None, arm())
    }

    @Test
    fun theTurnArms_thinkingWorkingAndRunningTool() {
        assertEquals(StatusArm.Thinking, arm(isThinking = true, isBusy = true))
        assertEquals(StatusArm.Working, arm(isBusy = true))
        assertEquals(StatusArm.RunningTool, arm(isBusy = true, hasOpenTool = true))
        assertEquals(StatusArm.RunningTool, arm(isThinking = true, isBusy = true, hasOpenTool = true))
    }

    @Test
    fun anOpenToolWithoutARunningTurn_showsNothing() {
        assertEquals(StatusArm.None, arm(hasOpenTool = true))
    }

    @Test
    fun aLocalSend_readsThinkingUntilTheDaemonSpeaks() {
        assertEquals(StatusArm.Thinking, arm(localSendPending = true))
        // A send issued mid-turn keeps the running turn's own label.
        assertEquals(StatusArm.Working, arm(localSendPending = true, isBusy = true))
    }

    @Test
    fun aLocalSend_hidesTheLastTurnsStaleOutcome() {
        assertEquals(StatusArm.TurnOutcome, arm(hasTurnOutcome = true))
        assertEquals(StatusArm.Thinking, arm(hasTurnOutcome = true, localSendPending = true))
    }

    @Test
    fun resetOutranksApiRetry() {
        assertEquals(StatusArm.Resetting, arm(resetting = true, apiRetrying = true))
    }

    @Test
    fun fullOrder_eachArmOutranksEveryArmBelowIt() {
        // Every signal live at once; peel them off from the top.
        var live =
            mapOf(
                "connecting" to true,
                "resetting" to true,
                "apiRetrying" to true,
                "compacting" to true,
                "stalled" to true,
                "outcome" to true,
            )

        fun current() =
            arm(
                connectionState = if (live.getValue("connecting")) ConnectionState.Connecting else ConnectionState.Connected,
                resetting = live.getValue("resetting"),
                apiRetrying = live.getValue("apiRetrying"),
                isCompacting = live.getValue("compacting"),
                isStalled = live.getValue("stalled"),
                hasTurnOutcome = live.getValue("outcome"),
                isThinking = true,
                isBusy = true,
                hasOpenTool = true,
            )
        val expected =
            listOf(
                "connecting" to StatusArm.Connection,
                "resetting" to StatusArm.Resetting,
                "apiRetrying" to StatusArm.ApiRetry,
                "compacting" to StatusArm.Compacting,
                "stalled" to StatusArm.Stalled,
                "outcome" to StatusArm.TurnOutcome,
            )
        for ((key, want) in expected) {
            assertEquals("with everything from $key down live", want, current())
            live = live + (key to false)
        }
        assertEquals(StatusArm.RunningTool, current())
    }

    @Test
    fun aStall_needsNoRunningTurn_andPreemptsEveryTurnArm() {
        assertEquals(StatusArm.Stalled, arm(isStalled = true))
        assertEquals(StatusArm.Stalled, arm(isStalled = true, isThinking = true, isBusy = true))
        assertEquals(StatusArm.Stalled, arm(isStalled = true, isBusy = true))
        assertEquals(StatusArm.Stalled, arm(isStalled = true, isBusy = true, hasOpenTool = true))
        assertEquals(StatusArm.Stalled, arm(isStalled = true, localSendPending = true))
    }

    @Test
    fun offline_leavesTheBandToTheTopOverlay() {
        assertEquals(StatusArm.None, arm(connectionState = ConnectionState.Offline, resetting = true, isBusy = true))
    }
}
