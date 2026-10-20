package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Actual phone query receipts for the native saved-checkpoint recovery proof. Never issues a request. */
internal object AuthoritativeHistoryProbe {
    internal class Certificate(
        val request: ThreadQueryRequest,
        val reply: ThreadQueryReply.Success,
        val snapshot: AuthoritativeThreadSnapshot,
    ) {
        override fun toString(): String = "AuthoritativeHistoryCertificate(redacted)"
    }

    private var host: String? = null
    private var conversation: String? = null
    private var baseline: AuthoritativeThreadSnapshot? = null
    private var witness: OwnedThreadQueryObservation? = null
    private val accepted = mutableListOf<ThreadQueryRequest>()
    private val certified = mutableListOf<Certificate>()
    private var legacy = 0

    @Synchronized fun begin(
        host: String,
        conversation: String,
        baseline: AuthoritativeThreadSnapshot,
    ) {
        require(baseline.epoch != null && baseline.checkpoint > 0 && !baseline.repairNeeded && !baseline.resetNeeded)
        end()
        this.host = host
        this.conversation = conversation
        this.baseline = baseline
    }

    @Synchronized fun end() {
        host = null
        conversation = null
        baseline = null
        witness = null
        accepted.clear()
        certified.clear()
        legacy = 0
    }

    @Synchronized fun sending(
        host: String,
        connection: Any,
        envelope: Envelope,
        current: () -> Boolean,
    ) {
        if (!selected(host, envelope) || envelope.type !in setOf("thread_catch_up", "thread_page")) return
        if (witness == null) {
            val saved = checkNotNull(baseline)
            val store = AuthoritativeThreadStore()
            store.restore(host, checkNotNull(conversation), saved, 0)
            witness =
                OwnedThreadQueryObservation(
                    host,
                    ThreadEvidenceOwner(host, checkNotNull(conversation), connection, ThreadEvidenceKind.Authoritative, saved.epoch, 0),
                    store,
                    AuthoritativeThreadRoute(host, store),
                    onAccepted = { accepted += it },
                    onCertified = { request, reply, snapshot -> certified += Certificate(request, reply, snapshot) },
                ).also { it.bind(connection, current) }
        }
        witness?.sending(connection, envelope)
    }

    @Synchronized fun sent(
        host: String,
        connection: Any,
        envelope: Envelope,
        result: Boolean,
    ) {
        if (this.host != host) return
        if (selected(host, envelope) && result && envelope.type in setOf("request_history", "backfill_since")) legacy++
        witness?.sent(connection, envelope, result)
    }

    @Synchronized fun received(
        host: String,
        connection: Any,
        envelope: Envelope,
    ) {
        if (this.host == host) witness?.received(connection, envelope)
    }

    @Synchronized fun ended(
        host: String,
        connection: Any,
    ) {
        if (this.host == host) witness?.close(connection)
    }

    @Synchronized fun requests(): List<ThreadQueryRequest> = accepted.toList()

    @Synchronized fun certificates(): List<Certificate> = certified.toList()

    @Synchronized fun legacyRequests(): Int = legacy

    @Synchronized fun current(): Boolean = witness?.owner() != null

    private fun selected(
        host: String,
        envelope: Envelope,
    ): Boolean =
        this.host == host &&
            (envelope.payload as? JsonObject)?.get("conversation_id") == JsonPrimitive(conversation)
}
