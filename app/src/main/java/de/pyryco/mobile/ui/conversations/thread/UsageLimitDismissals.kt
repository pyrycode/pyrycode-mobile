package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.UsageLimitReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The usage readings the operator hid from the thread's Top overlay (#1002), held for the life of the app
 * process like [ComposerDraftStore].
 *
 * **The key is the reading, never the conversation.** A usage limit belongs to the account, so hiding a
 * reading in one thread hides the same reading in every thread. A change of status, limit type or reset
 * time is a different reading and shows again; a new `utilization` alone is the same reading.
 *
 * **In-memory only and never logged.** Nothing is written to disk or saved-instance state, so a restart
 * shows the reading again. The key holds daemon-authored strings and the account's quota posture; no
 * part of it may reach a log line, an exception message or a crash report.
 */
class UsageLimitDismissals {
    private val _dismissed = MutableStateFlow<Set<Key>>(emptySet())

    val dismissed: StateFlow<Set<Key>> = _dismissed.asStateFlow()

    fun dismiss(reading: UsageLimitReading) {
        _dismissed.update { it + reading.dismissalKey() }
    }

    data class Key(
        val status: String,
        val limitType: String,
        val resetsAt: Long,
    )
}

internal fun UsageLimitReading.dismissalKey(): UsageLimitDismissals.Key = UsageLimitDismissals.Key(status, limitType, resetsAt)
