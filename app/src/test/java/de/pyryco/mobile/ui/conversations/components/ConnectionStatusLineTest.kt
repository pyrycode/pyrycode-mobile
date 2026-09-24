package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-mapper tests for the two-part connection-status presentation (#397). Each case asserts the
 * full (category, label, contentDescription) triple so a copy change is caught and moved
 * deliberately rather than drifting silently.
 */
class ConnectionStatusLineTest {
    // --- Relay leg (RelayLinkStatus) ---

    @Test
    fun relayConnected_mapsToUp() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Up, "Connected", "Relay: connected"),
            RelayLinkStatus.Connected.toLegVisual(),
        )
    }

    @Test
    fun relayConnecting_mapsToInProgress() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.InProgress, "Connecting…", "Relay: connecting"),
            RelayLinkStatus.Connecting.toLegVisual(),
        )
    }

    @Test
    fun relayReconnecting_mapsToInProgress() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.InProgress, "Reconnecting", "Relay: reconnecting"),
            RelayLinkStatus.Reconnecting(secondsRemaining = 12).toLegVisual(),
        )
    }

    /** AC#2: relay reachable with no daemon behind it is an UP (green) state — never red. */
    @Test
    fun relayDaemonAbsent_mapsToUp_neverDown() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Up, "Reachable", "Relay: reachable, no daemon"),
            RelayLinkStatus.DaemonAbsent.toLegVisual(),
        )
    }

    @Test
    fun relayOffline_mapsToDown() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Down, "Offline", "Relay: offline"),
            RelayLinkStatus.Offline.toLegVisual(),
        )
    }

    /** AC#1: idle (unpaired / initial / closed) is a not-connected, non-green state — a distinct
     *  "Not connected" label under the Down category, never the green "Connected" of a live socket. */
    @Test
    fun relayIdle_mapsToDown_notConnected() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Down, "Not connected", "Relay: not connected"),
            RelayLinkStatus.Idle.toLegVisual(),
        )
    }

    /** #841: a refused credential reads as down, with its own label so the cause is legible. */
    @Test
    fun relayPairingRejected_mapsToDown() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Down, "Pairing rejected", "Relay: pairing rejected"),
            RelayLinkStatus.PairingRejected.toLegVisual(),
        )
    }

    /** #1008: a host that rejects the app as too old reads as down; the minimum is not shown here. */
    @Test
    fun relayUpdateRequired_mapsToDown_withOrWithoutAMinimum() {
        val expected = ConnectionLegVisual(ConnectionLegCategory.Down, "Update required", "Relay: update required")
        assertEquals(expected, RelayLinkStatus.UpdateRequired(null).toLegVisual())
        assertEquals(expected, RelayLinkStatus.UpdateRequired("1.4.0").toLegVisual())
    }

    // --- Pyrycode leg (PyrycodeLinkStatus) ---

    @Test
    fun pyrycodeHandshaking_mapsToInProgress() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.InProgress, "Handshaking…", "Pyrycode: handshaking"),
            PyrycodeLinkStatus.Handshaking.toLegVisual(),
        )
    }

    @Test
    fun pyrycodeConnected_mapsToUp() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Up, "Connected", "Pyrycode: connected"),
            PyrycodeLinkStatus.Connected.toLegVisual(),
        )
    }

    @Test
    fun pyrycodeDown_mapsToDown() {
        assertEquals(
            ConnectionLegVisual(ConnectionLegCategory.Down, "Down", "Pyrycode: down"),
            PyrycodeLinkStatus.Down.toLegVisual(),
        )
    }
}
