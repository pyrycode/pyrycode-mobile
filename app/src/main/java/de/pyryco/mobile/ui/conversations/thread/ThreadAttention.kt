package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.notifications.isMuted
import de.pyryco.mobile.notifications.nameOf
import de.pyryco.mobile.notifications.notificationTitle
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A count has no target; a single waiting or finished conversation keeps its exact host identity. */
internal data class ThreadAttention(
    val waitingCount: Int,
    val target: HostConversationTarget? = null,
    val name: String? = null,
)

/** Cold and visible-destination-owned. Each collection starts with current waiting and no old finish. */
internal fun observeThreadAttention(
    current: HostConversationTarget,
    snapshots: StateFlow<List<HostConversationSnapshot>>,
    attention: StateFlow<Map<String, Map<String, ConversationAttention>>>,
    alerts: Flow<AttentionAlert>,
): Flow<ThreadAttention?> =
    channelFlow {
        var finished: HostConversationTarget? = null
        var expiry: Job? = null
        var lastWaitingCount = 0

        fun waitingTargets(): List<HostConversationTarget> =
            attention.value
                .flatMap { (host, rows) ->
                    rows.filterValues { it == ConversationAttention.WaitingForAnswer }.keys.map { HostConversationTarget(host, it) }
                }.filter { it != current && !snapshots.value.isMuted(it.serverId, it.conversationId) }

        fun single(
            count: Int,
            target: HostConversationTarget,
        ) = ThreadAttention(count, target, notificationTitle(snapshots.value.nameOf(target.serverId, target.conversationId)))

        fun clearFinish(reason: String) {
            if (finished != null) RelayLog.d { "event=thread_attention_finish_cleared reason=$reason" }
            finished = null
            expiry?.cancel()
            expiry = null
        }

        fun publish() {
            val waiting = waitingTargets()
            if (waiting.size != lastWaitingCount) {
                lastWaitingCount = waiting.size
                RelayLog.d { "event=thread_attention_waiting_changed count=$lastWaitingCount" }
            }
            if (waiting.isNotEmpty()) {
                clearFinish("waiting")
                trySend(if (waiting.size == 1) single(1, waiting.single()) else ThreadAttention(waiting.size))
            } else {
                val target = finished
                if (target != null && snapshots.value.isMuted(target.serverId, target.conversationId)) clearFinish("muted")
                trySend(finished?.let { single(0, it) })
            }
        }

        RelayLog.d { "event=thread_attention_opened" }
        publish()
        try {
            launch {
                combine(snapshots, attention) { _, _ -> Unit }.collect { publish() }
            }
            launch {
                alerts.collect { alert ->
                    val target = HostConversationTarget(alert.serverId, alert.conversationId)
                    if (alert.kind != AttentionAlert.Kind.TurnCompleted ||
                        target == current ||
                        snapshots.value.isMuted(target.serverId, target.conversationId)
                    ) {
                        return@collect
                    }
                    if (waitingTargets().isNotEmpty()) {
                        RelayLog.d { "event=thread_attention_finish_suppressed reason=waiting" }
                        publish()
                        return@collect
                    }
                    expiry?.cancel()
                    finished = target
                    RelayLog.d { "event=thread_attention_finish_shown" }
                    publish()
                    expiry =
                        launch {
                            delay(5_000)
                            finished = null
                            RelayLog.d { "event=thread_attention_finish_expired" }
                            publish()
                        }
                }
            }
            awaitCancellation()
        } finally {
            RelayLog.d { "event=thread_attention_closed" }
        }
    }.distinctUntilChanged()

/** RESUMED excludes background and covered back-stack entries even when their composition remains. */
@Composable
internal fun rememberThreadAttention(
    current: HostConversationTarget,
    snapshots: StateFlow<List<HostConversationSnapshot>>,
    attention: StateFlow<Map<String, Map<String, ConversationAttention>>>,
    alerts: Flow<AttentionAlert>,
    lifecycle: Lifecycle,
): State<ThreadAttention?> =
    produceState<ThreadAttention?>(null, current, snapshots, attention, alerts, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                observeThreadAttention(current, snapshots, attention, alerts).collect { value = it }
            } finally {
                value = null
            }
        }
    }
