package de.pyryco.mobile.data.model

/**
 * The combined two-part connection status (#392): the relay leg ([RelayLinkStatus]: phone → relay,
 * socket-level) and the pyrycode leg ([PyrycodeLinkStatus]: relay → daemon, end-to-end session
 * readiness), held verbatim. The Settings connection-status line (#390) consumes this so each leg is
 * shown independently — a green relay dot paired with a handshaking pyrycode dot is an expressible,
 * honest state, no longer collapsed into a single false "connected". Portable (no `android.*`).
 */
data class ConnectionStatus(
    val relay: RelayLinkStatus,
    val pyrycode: PyrycodeLinkStatus,
)
