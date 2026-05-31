package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer

/**
 * Builds a fresh single-use [RelayTransport] per dial. The reconnect supervisor (#307) discards a
 * spent instance after its terminal `Down` and asks for a new one to reconnect (#306 sockets are
 * single-use; there is no resumable state, since Noise ephemerals are per-handshake).
 *
 * Production binds a lambda closing over the shared [okhttp3.WebSocket.Factory] (from
 * [OkHttpRelayTransport.defaultClient]) and the [NoiseClientInfo]; tests inject a fake returning
 * fake transports.
 */
fun interface RelayTransportFactory {
    fun create(pairedServer: PairedServer): RelayTransport
}
