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
    internal class Observation(
        internal val revision: Any,
        val requests: List<ThreadQueryRequest>,
        val certificates: List<Certificate>,
        val resets: List<Reset>,
        val receipts: List<OwnedThreadQueryObservation.Receipt>,
        val legacyRequests: Int,
        val owner: ThreadEvidenceOwner?,
    ) {
        val current: Boolean = owner != null
        val exactlyAccounted: Boolean =
            receipts.size == requests.size &&
                certificates.size + resets.size == requests.size &&
                receipts.all { receipt ->
                    requests.count { it === receipt.request } == 1 &&
                        when (receipt.disposition) {
                            "certified" ->
                                certificates.count {
                                    it.request === receipt.request && it.snapshot.generation == receipt.generation
                                } == 1 &&
                                    resets.none { it.request === receipt.request }
                            "reset" ->
                                resets.count { it.request === receipt.request } == 1 &&
                                    certificates.none { it.request === receipt.request }
                            else -> false
                        }
                }
        val currentCertificates: List<Certificate> =
            certificates.filter {
                it.reply.checkpoint.epoch == owner?.epoch && it.snapshot.generation == owner?.generation
            }
        val ready: Boolean = current && currentCertificates.isNotEmpty() && exactlyAccounted

        fun admits(evidence: ThreadEvidence): Boolean {
            val source = owner ?: return false
            val snapshot = evidence.sourceSnapshot ?: return false
            return ready &&
                evidence.admitted &&
                source.host == evidence.owner.host &&
                source.conversation == evidence.owner.conversation &&
                source.epoch == evidence.owner.epoch &&
                source.generation == evidence.owner.generation &&
                snapshot.checkpoint.toULong() >= currentCertificates.maxOf { it.reply.checkpoint.version } &&
                snapshot.checkpoint >= snapshot.completedVersion
        }

        override fun toString(): String = "HistoryObservation(redacted)"
    }

    internal class Certificate(
        val request: ThreadQueryRequest,
        val reply: ThreadQueryReply.Success,
        val snapshot: AuthoritativeThreadSnapshot,
    ) {
        override fun toString(): String = "AuthoritativeHistoryCertificate(redacted)"
    }

    internal class Reset(
        val request: ThreadQueryRequest,
        val reply: ThreadQueryReply.Reset,
    ) {
        override fun toString(): String = "AuthoritativeHistoryReset(redacted)"
    }

    private var host: String? = null
    private var conversation: String? = null
    private var baseline: AuthoritativeThreadSnapshot? = null
    private var witness: OwnedThreadQueryObservation? = null
    private val accepted = mutableListOf<ThreadQueryRequest>()
    private val certified = mutableListOf<Certificate>()
    private val resets = mutableListOf<Reset>()
    private var legacy = 0
    private var active = false
    private var revision: Any = Any()

    @Synchronized fun begin(
        host: String,
        conversation: String,
        baseline: AuthoritativeThreadSnapshot,
    ) {
        require(baseline.epoch != null && baseline.checkpoint > 0 && !baseline.repairNeeded && !baseline.resetNeeded)
        end()
        witness = null
        accepted.clear()
        certified.clear()
        resets.clear()
        legacy = 0
        this.host = host
        this.conversation = conversation
        this.baseline = baseline
        active = true
    }

    @Synchronized fun end() {
        active = false
        revision = Any()
        witness?.end()
    }

    @Synchronized fun sending(
        host: String,
        connection: Any,
        envelope: Envelope,
        current: () -> Boolean,
    ) {
        if (!active || !selected(host, envelope) || envelope.type !in setOf("thread_catch_up", "thread_page")) return
        revision = Any()
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
                    onReset = { request, reply -> resets += Reset(request, reply) },
                    followReplacementReset = true,
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
        revision = Any()
        if (selected(host, envelope) && result && envelope.type in setOf("request_history", "backfill_since")) legacy++
        witness?.sent(connection, envelope, result)
    }

    @Synchronized fun received(
        host: String,
        connection: Any,
        envelope: Envelope,
    ) {
        if (active && this.host == host) {
            revision = Any()
            witness?.received(connection, envelope)
        }
    }

    @Synchronized fun ended(
        host: String,
        connection: Any,
    ) {
        if (this.host == host) {
            revision = Any()
            witness?.close(connection)
        }
    }

    @Synchronized fun requests(): List<ThreadQueryRequest> = accepted.toList()

    @Synchronized fun certificates(): List<Certificate> = certified.toList()

    @Synchronized fun resets(): List<Reset> = resets.toList()

    @Synchronized fun legacyRequests(): Int = legacy

    @Synchronized fun current(): Boolean = active && witness?.owner() != null

    @Synchronized fun snapshot(): Observation =
        Observation(
            revision,
            accepted.toList(),
            certified.toList(),
            resets.toList(),
            witness?.receipts().orEmpty(),
            legacy,
            witness?.owner().takeIf { active },
        )

    /** Linearization point after the caller read current repository content without holding this monitor. */
    @Synchronized fun isCurrent(observation: Observation): Boolean = observation.revision === revision && observation.ready && current()

    private fun selected(
        host: String,
        envelope: Envelope,
    ): Boolean =
        this.host == host &&
            (envelope.payload as? JsonObject)?.get("conversation_id") == JsonPrimitive(conversation)
}
