package de.pyryco.mobile.data.repository

/**
 * One host's thread verdict, taken only from an authenticated `hello_ack` on a current connection.
 * The connection token fences work to that acknowledgement; held content never grants support.
 */
sealed interface ThreadSupport {
    data object Unverified : ThreadSupport

    class Supported(
        val connection: Any,
    ) : ThreadSupport {
        override fun toString(): String = "Supported"
    }

    /** Sticky until a later authenticated acknowledgement replaces it. */
    class Missing(
        val connection: Any,
    ) : ThreadSupport {
        override fun toString(): String = "Missing"
    }
}
