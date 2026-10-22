package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Opt-in instrumentation of the destination's existing collection, never a repository reader. */
internal object PassiveThreadProbe {
    private var active: PassiveThreadObservation? = null

    @Synchronized fun open(): PassiveThreadObservation {
        check(active == null) { "passive thread observation already armed" }
        return PassiveThreadObservation().also { active = it }
    }

    @Synchronized internal fun release(observation: PassiveThreadObservation) {
        if (active === observation) active = null
    }

    /** Decorates the destination's existing read. All synchronization and interests stay with its provider. */
    fun negotiated(delegate: NegotiatedThread): NegotiatedThread =
        object : NegotiatedThread() {
            override val host = delegate.host
            override val support = delegate.support
            override val store = delegate.store

            override fun localHistory(conversationId: String) = delegate.localHistory(conversationId)

            override suspend fun open(conversationId: String) = delegate.open(conversationId)

            override suspend fun acquire(
                conversationId: String,
                connection: Any,
            ) = delegate.acquire(conversationId, connection)

            override fun older(conversationId: String) = delegate.older(conversationId)

            override fun cancel(conversationId: String) = delegate.cancel(conversationId)

            override fun progress(
                conversationId: String,
                connection: Any,
            ) = delegate.progress(conversationId, connection)

            override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> =
                flow {
                    val observation = synchronized(this@PassiveThreadProbe) { active }
                    var reading: PassiveThreadReading? = null
                    val subscription = Any()

                    fun currentOwner(): ThreadEvidenceOwner? {
                        val connection = (support.value as? ThreadSupport.Supported)?.connection ?: return null
                        val snapshot = store.current(host, conversationId)
                        return ThreadEvidenceOwner(
                            host,
                            conversationId,
                            connection,
                            ThreadEvidenceKind.Authoritative,
                            snapshot.epoch,
                            snapshot.generation,
                        )
                    }
                    try {
                        delegate.observe(conversationId).collect { snapshot ->
                            if (observation != null) {
                                val owner = currentOwner()
                                if (owner == null || snapshot.epoch != owner.epoch || snapshot.generation != owner.generation) {
                                    reading?.let(observation::ended)
                                    reading = null
                                } else if (reading?.owner?.let(owner::matches) != true) {
                                    reading?.let(observation::ended)
                                    reading = owner?.let { observation.started(it, ::currentOwner, subscription) }
                                }
                                val captured = reading
                                if (captured != null) {
                                    if (!observation.record(
                                            captured,
                                            authoritativeThreadEvidence(host, conversationId, captured.owner.connection, snapshot),
                                        )
                                    ) {
                                        reading =
                                            null
                                    }
                                }
                            }
                            emit(snapshot)
                        }
                    } finally {
                        reading?.let { observation?.ended(it) }
                    }
                }
        }
}

internal class PassiveThreadReading(
    val owner: ThreadEvidenceOwner,
    internal val currentOwner: () -> ThreadEvidenceOwner?,
    val subscription: Any = Any(),
) {
    @Volatile internal var authoritative: ThreadEvidence? = null

    override fun toString(): String = "PassiveThreadReading(redacted)"
}

/** A lease retains only active subscriptions created while it was armed. */
internal class PassiveThreadObservation : AutoCloseable {
    private val readings = mutableMapOf<Any, PassiveThreadReading>()
    private var closed = false

    @Synchronized internal fun started(
        owner: ThreadEvidenceOwner,
        currentOwner: () -> ThreadEvidenceOwner?,
        subscription: Any = Any(),
    ): PassiveThreadReading? =
        if (closed) {
            null
        } else {
            PassiveThreadReading(owner, currentOwner, subscription).also {
                readings[it.subscription] =
                    it
            }
        }

    @Synchronized internal fun record(
        reading: PassiveThreadReading,
        evidence: ThreadEvidence,
    ): Boolean {
        if (readings[reading.subscription] !== reading) return false
        if (reading.currentOwner()?.let(reading.owner::matches) == true && evidence.owner.matches(reading.owner)) {
            reading.authoritative = evidence
            return true
        }
        ended(reading)
        return false
    }

    @Synchronized internal fun ended(reading: PassiveThreadReading) {
        readings.remove(reading.subscription)
        reading.authoritative = null
    }

    @Synchronized fun reading(
        host: String,
        conversation: String,
        after: Any? = null,
    ): PassiveThreadReading? =
        readings.values.toList().singleOrNull {
            it.subscription !== after && it.owner.host == host && it.owner.conversation == conversation && evidence(it) != null
        }

    @Synchronized fun evidence(reading: PassiveThreadReading): ThreadEvidence? {
        if (closed || readings[reading.subscription] !== reading) return null
        val current = reading.currentOwner()
        if (current == null || !reading.owner.matches(current)) {
            ended(reading)
            return null
        }
        return reading.authoritative?.takeIf { it.admitted }
    }

    @Synchronized override fun close() {
        closed = true
        readings.values.forEach {
            it.authoritative = null
        }
        readings.clear()
        PassiveThreadProbe.release(this)
    }
}

/** Count in admitted data before revealing rows; lazy composition cannot establish uniqueness. */
internal fun ThreadEvidence.retainedPeerUser(
    captured: ThreadEvidenceRecord,
    current: ThreadEvidenceOwner,
    session: String,
): ThreadEvidenceRecord? {
    if (!admitted || !owner.matches(current) || userEchoCount(captured.fullText) != 1) return null
    return records
        .singleOrNull {
            if (owner.kind == ThreadEvidenceKind.Authoritative) {
                it.fact?.id == captured.fact?.id && it.fact != null && captured.fact != null
            } else {
                it.message.id == captured.message.id
            }
        }?.takeIf {
            it.message.role == Role.User &&
                it.fullText == captured.fullText &&
                it.message.content == captured.message.content &&
                (it.session == captured.session || captured.session.isNullOrEmpty() && it.session == session) &&
                it.turn == captured.turn
        }
}
