package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadSnapshot
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

    fun tap(
        currentOwner: () -> ThreadEvidenceOwner?,
        upstream: Flow<ThreadSnapshot>,
    ): Flow<ThreadSnapshot> =
        flow {
            val observation = synchronized(this@PassiveThreadProbe) { active }
            val reading = if (observation == null) null else currentOwner()?.let { observation.started(it, currentOwner) }
            try {
                upstream.collect { snapshot ->
                    if (reading != null) observation?.record(reading, snapshot)
                    emit(snapshot)
                }
            } finally {
                if (reading != null) observation?.ended(reading)
            }
        }
}

internal class PassiveThreadReading(
    val owner: ThreadEvidenceOwner,
    internal val currentOwner: () -> ThreadEvidenceOwner?,
) {
    val subscription = Any()

    @Volatile var snapshot: ThreadSnapshot? = null
        internal set

    override fun toString(): String = "PassiveThreadReading(redacted)"
}

/** A lease retains only active subscriptions created while it was armed. */
internal class PassiveThreadObservation : AutoCloseable {
    private val readings = mutableMapOf<Any, PassiveThreadReading>()
    private var closed = false

    @Synchronized internal fun started(
        owner: ThreadEvidenceOwner,
        currentOwner: () -> ThreadEvidenceOwner?,
    ): PassiveThreadReading? = if (closed) null else PassiveThreadReading(owner, currentOwner).also { readings[it.subscription] = it }

    @Synchronized internal fun record(
        reading: PassiveThreadReading,
        snapshot: ThreadSnapshot,
    ) {
        if (readings[reading.subscription] !== reading) return
        if (reading.currentOwner()?.let(reading.owner::matches) == true) {
            reading.snapshot = snapshot
        } else {
            ended(reading)
        }
    }

    @Synchronized internal fun ended(reading: PassiveThreadReading) {
        readings.remove(reading.subscription)
        reading.snapshot = null
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
        val snapshot = reading.snapshot ?: return null
        return legacyThreadEvidence(current.host, current.conversation, current.connection, snapshot.rows)
    }

    @Synchronized override fun close() {
        closed = true
        readings.values.forEach { it.snapshot = null }
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
    return records.singleOrNull { it.message.id == captured.message.id }?.takeIf {
        it.message.role == Role.User &&
            it.fullText == captured.fullText &&
            it.message.content == captured.message.content &&
            (it.session == captured.session || captured.session.isNullOrEmpty() && it.session == session) &&
            it.turn == captured.turn
    }
}
