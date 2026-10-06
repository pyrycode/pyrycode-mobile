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
        isThinking: Boolean = false,
        isBusy: Boolean = false,
        localSendStage: LocalSendStage = LocalSendStage.None,
        hasOpenTool: Boolean = false,
    ): StatusArm =
        statusArm(
            connectionState = connectionState,
            resetting = resetting,
            apiRetrying = apiRetrying,
            isCompacting = isCompacting,
            isStalled = isStalled,
            isThinking = isThinking,
            isBusy = isBusy,
            localSendStage = localSendStage,
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
    fun localStages_yieldToEveryRunningTurnArm() {
        for ((stage, expected) in listOf(LocalSendStage.Sending to StatusArm.Sending, LocalSendStage.Waiting to StatusArm.Waiting)) {
            assertEquals(expected, arm(localSendStage = stage))
            assertEquals(StatusArm.Thinking, arm(localSendStage = stage, isThinking = true, isBusy = true))
            assertEquals(StatusArm.Working, arm(localSendStage = stage, isBusy = true))
            assertEquals(StatusArm.RunningTool, arm(localSendStage = stage, isBusy = true, hasOpenTool = true))
            assertEquals(StatusArm.RunningTool, arm(localSendStage = stage, isThinking = true, isBusy = true, hasOpenTool = true))
        }
    }

    @Test
    fun localStages_yieldToConnectionResetRetryCompactionAndStall() {
        for (stage in listOf(LocalSendStage.Sending, LocalSendStage.Waiting)) {
            assertEquals(StatusArm.None, arm(localSendStage = stage, connectionState = ConnectionState.Offline))
            assertEquals(StatusArm.Connection, arm(localSendStage = stage, connectionState = ConnectionState.Connecting))
            assertEquals(StatusArm.Connection, arm(localSendStage = stage, connectionState = ConnectionState.Reconnecting(5)))
            assertEquals(StatusArm.Resetting, arm(localSendStage = stage, resetting = true, apiRetrying = true))
            assertEquals(StatusArm.ApiRetry, arm(localSendStage = stage, apiRetrying = true, isCompacting = true))
            assertEquals(StatusArm.Compacting, arm(localSendStage = stage, isCompacting = true, isStalled = true))
            assertEquals(StatusArm.Stalled, arm(localSendStage = stage, isStalled = true))
        }
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
            )

        fun current() =
            arm(
                connectionState = if (live.getValue("connecting")) ConnectionState.Connecting else ConnectionState.Connected,
                resetting = live.getValue("resetting"),
                apiRetrying = live.getValue("apiRetrying"),
                isCompacting = live.getValue("compacting"),
                isStalled = live.getValue("stalled"),
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
        assertEquals(StatusArm.Stalled, arm(isStalled = true, localSendStage = LocalSendStage.Sending))
    }

    @Test
    fun offline_leavesTheBandToTheTopOverlay() {
        assertEquals(StatusArm.None, arm(connectionState = ConnectionState.Offline, resetting = true, isBusy = true))
    }
}
