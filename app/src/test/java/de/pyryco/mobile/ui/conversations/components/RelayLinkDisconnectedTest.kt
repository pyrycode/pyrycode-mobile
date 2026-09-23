package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.model.RelayLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayLinkDisconnectedTest {
    @Test
    fun onlyTheErrorStatesReadAsDisconnected() {
        val classified =
            listOf(
                RelayLinkStatus.Reconnecting(secondsRemaining = 5),
                RelayLinkStatus.Offline,
                RelayLinkStatus.DaemonAbsent,
                RelayLinkStatus.PairingRejected,
                // Idle is a deliberate background close, not an error.
                RelayLinkStatus.Idle,
                RelayLinkStatus.Connecting,
                RelayLinkStatus.Connected,
            ).map { it.isDisconnected() }

        assertEquals(listOf(true, true, true, true, false, false, false), classified)
    }
}
