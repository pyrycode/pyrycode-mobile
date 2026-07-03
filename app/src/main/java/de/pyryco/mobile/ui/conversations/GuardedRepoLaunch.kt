package de.pyryco.mobile.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.network.RelayErrorException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Launches [block] in [viewModelScope] with the relay-mode one-shot guard (#490): rethrow structured
 * cancellation first, then inertly swallow the three failure types a relay-backed [ConversationRepository]
 * can produce for the older conversation-action launches (send, create-discussion, change-workspace,
 * archive/rename/delete/promote) that predate the modal-send discipline:
 *
 *  - [RelayErrorException] — a crafted server `error` frame.
 *  - [IllegalStateException] — a not-connected session, or the not-wired interface-default `error(...)`.
 *  - [UnsupportedOperationException] — a not-yet-wired remote method (`archive` / `rename` / `changeWorkspace`).
 *
 * These throws are inert-swallowed so one failed action never reaches the default uncaught-exception handler
 * and kills the process; the posture mirrors `ThreadViewModel.onDropQueued` / `sendInterrupt` (no error
 * surface). A caught exception is **never logged** — [RelayErrorException.message] is server-supplied
 * (security-sensitive), so centralizing the catch here asserts that confidentiality in exactly one place.
 *
 * The [CancellationException] arm MUST stay first: on the JVM `java.util.concurrent.CancellationException`
 * extends [IllegalStateException], so a bare `catch (IllegalStateException)` would swallow structured
 * cancellation (the #451 rework). [IllegalArgumentException] is deliberately not caught — the call sites
 * always pass their own current conversation id, so it signals a programming bug and should crash.
 */
internal fun ViewModel.launchGuardedRepoCall(block: suspend () -> Unit) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // MUST precede the typed catches: j.u.c.CancellationException extends ISE on the JVM
        } catch (e: RelayErrorException) {
            // Inert: server error swallowed. Never log e.message (server-supplied).
        } catch (e: IllegalStateException) {
            // Inert: not-connected / not-wired interface-default swallowed.
        } catch (e: UnsupportedOperationException) {
            // Inert: not-yet-wired remote method swallowed.
        }
    }
}
