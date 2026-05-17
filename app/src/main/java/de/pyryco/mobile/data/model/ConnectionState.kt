package de.pyryco.mobile.data.model

sealed class ConnectionState {
    data object Connected : ConnectionState()

    data object Connecting : ConnectionState()

    data class Reconnecting(
        val secondsRemaining: Int,
    ) : ConnectionState()

    data object Offline : ConnectionState()
}
