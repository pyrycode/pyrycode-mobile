package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Settlement still needs current source certification, even after every row renders. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun awaitCertifiedAgentSettlement(
    route: NegotiatedThread,
    conversation: String,
    launched: ThreadEvidence,
    family: AgentFamilyEvidence,
    followingText: String,
    observed: (ThreadEvidence) -> Unit = {},
): ThreadEvidence {
    require(route.host == launched.owner.host && conversation == launched.owner.conversation)
    return route.support
        .flatMapLatest { support ->
            flow {
                if (support !is ThreadSupport.Supported) return@flow
                val source = checkNotNull(support.connection as? RemoteConversationRepository)
                check(source.authoritativeHost == route.host)
                val interest = route.acquire(conversation, source) ?: return@flow
                try {
                    var refreshed = false
                    route.store.observe(route.host, conversation).collect { snapshot ->
                        if ((route.support.value as? ThreadSupport.Supported)?.connection !== source ||
                            route.store.current(route.host, conversation) !== snapshot
                        ) {
                            return@collect
                        }
                        val reading = authoritativeThreadEvidence(route.host, conversation, source, snapshot)
                        observed(reading)
                        val current = reading.agentFamily(reading.owner)
                        val coverage = snapshot.coverage
                        val orders =
                            reading.records
                                .filter {
                                    it.agentId == current?.agentId || it.agentId == null && it.fullText == followingText
                                }.mapNotNull { it.fact?.order }
                        val outside = coverage == null || orders.any { it.toULong() !in coverage.lowerOrder..coverage.upperOrder }
                        if (!refreshed &&
                            reading.admitted &&
                            snapshot.epoch == launched.owner.epoch &&
                            current != null &&
                            reading.settledAgentPrecedes(reading.owner, current, followingText) &&
                            outside
                        ) {
                            refreshed = true
                            RelayLog.i {
                                "event=scripted_agent_settlement code=refresh_requested generation=${snapshot.generation} " +
                                    "checkpoint=${snapshot.checkpoint} lower=${coverage?.lowerOrder} upper=${coverage?.upperOrder}"
                            }
                            // A nonzero catch-up preserves the old range. Existing reset obtains a certified newest window.
                            check(source.resetAuthoritativeThread(conversation, checkNotNull(snapshot.epoch)))
                        } else {
                            emit(reading)
                        }
                    }
                } finally {
                    interest.close()
                }
            }
        }.first {
            val supported = (route.support.value as? ThreadSupport.Supported)?.connection
            it.sourceSnapshot === route.store.current(route.host, conversation) &&
                it.isCurrentReplay(route.host, conversation, supported, it.sourceSnapshot) &&
                it.settledAgentAfterRecovery(launched, family, followingText, route.support.value) != null
        }.also { RelayLog.i { "event=scripted_agent_settlement code=certified" } }
}
