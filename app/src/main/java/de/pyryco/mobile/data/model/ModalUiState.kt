package de.pyryco.mobile.data.model

/**
 * The hoisted "current modal" projection (#445): which permission/choice modal — if any — is currently
 * outstanding in one thread. Folded from the `replay = 0` [ModalEvent] stream (#437) into the host's
 * [HostModalState]; because that stream holds no current state, the fold owns the accumulation.
 *
 * The host holds every outstanding prompt in a [HostModalState] (#1337). [Open] and [Dismissed] carry the
 * conversation that raised the modal (#816), and each thread shows the host's prompts only through
 * [HostModalState.scopedTo] its own conversation. The thread ViewModel exposes that scoped value
 * ([ThreadViewModel.currentModal]) as a sibling to the other transient thread signals (`isThinking` #406
 * / `isStalled` #395), and the stateless render slice (#446) consumes it.
 *
 * Every field is carried **verbatim** from the source event — no parsing, enum-coercion, trimming, or
 * reordering (preserves #437's forward-compat decode posture). The free-form strings ([Open.title],
 * [Open.prompt], [ModalOption.label]) and the verbatim wire strings ([Open.modalClass], [Open.defaultOptionId],
 * [Dismissed.outcome], [Dismissed.source]) may name a sensitive command or path: this type carries them as
 * **inert data only**. Output-encoding (rendering them as inert, not active markup) and the fail-safe-deny
 * default highlight are the render slice (#446)'s responsibility — never interpreted here.
 */
sealed interface ModalUiState {
    /** No modal outstanding — the initial / resolved-and-cleared state. */
    data object Hidden : ModalUiState

    /**
     * A modal is currently surfaced and awaiting an answer. Mirrors [ModalEvent.Shown] field-for-field;
     * [options] preserves the wire array order (the canonical display/selection order), and
     * [defaultOptionId] is the producer's fail-safe-deny default (carried verbatim, never auto-applied).
     * [context] is the permission ask's decision context (#817), display-only like every other field.
     * [alwaysAllowRules] is the session-grant offer (#818), empty when none is available.
     */
    data class Open(
        val modalId: String,
        val modalClass: String,
        val title: String,
        val prompt: String,
        val options: List<ModalOption>,
        val defaultOptionId: String,
        val conversationId: String = "",
        val context: ModalContext = ModalContext.None,
        val alwaysAllowRules: List<String> = emptyList(),
    ) : ModalUiState {
        /**
         * Whether this prompt offers "don't ask again this session" (#818): only a `permission` ask whose
         * offer the daemon marked available. The overlay and the answer path both gate on this one value.
         */
        val offersAlwaysAllow: Boolean
            get() = modalClass == "permission" && alwaysAllowRules.isNotEmpty()
    }

    /**
     * The currently-open modal resolved. Mirrors [ModalEvent.Dismissed]; [source] is the verbatim
     * resolution reason (`remote` | `local` | `timeout`, or any forward-compat value unchanged).
     * [conversationId] is the resolved modal's own (#816): the wire dismiss carries none, so the host fold
     * copies it from the [Open] modal it resolves.
     */
    data class Dismissed(
        val modalId: String,
        val outcome: String,
        val source: String,
        val conversationId: String = "",
    ) : ModalUiState
}

/**
 * Every prompt one host holds (#1337), keyed on `modalId`: the [outstanding] prompts in the order they were
 * first shown, and the prompts [resolved] on this connection in the order they were dismissed. A thread sees
 * its own conversation's prompt through [scopedTo]. The coordinator folds each [ModalEvent] in with `reduce`
 * and starts from an empty value on every new connection, so the daemon's connect-time re-sends are the only
 * way a prompt held before the reconnect returns (desktop `reduceModal`, #415/#510/#1140).
 *
 * Every field is carried verbatim, as in [ModalUiState]; nothing here interprets or logs a modal field.
 */
data class HostModalState(
    val outstanding: List<ModalUiState.Open> = emptyList(),
    val resolved: List<ModalUiState.Dismissed> = emptyList(),
)

/**
 * Folds one [ModalEvent] into the host's prompts. Pure and side-effect-free — **no logging** of any modal
 * field (the fields may name a sensitive command/path).
 *
 * - [ModalEvent.Shown] whose id was already [resolved][HostModalState.resolved] on this connection → unchanged,
 *   so an answered prompt never comes back before the next reconnect.
 * - [ModalEvent.Shown] whose id is held → that prompt replaced in place, keeping its position.
 * - Any other [ModalEvent.Shown] → appended as an [ModalUiState.Open] carrying the event verbatim.
 * - [ModalEvent.Dismissed] whose id is held → removed, and recorded as a [ModalUiState.Dismissed] carrying the
 *   verbatim resolution reason and the held prompt's conversation (the wire dismiss carries none, #816).
 * - [ModalEvent.Dismissed] for an id the host does not hold → unchanged. The exact-`modalId` match is the
 *   spoofed-dismiss safety property: an out-of-band dismiss cannot clear an unresolved prompt.
 */
internal fun HostModalState.reduce(event: ModalEvent): HostModalState =
    when (event) {
        is ModalEvent.Shown -> {
            if (resolved.any { it.modalId == event.modalId }) {
                this
            } else {
                val open =
                    ModalUiState.Open(
                        modalId = event.modalId,
                        modalClass = event.modalClass,
                        title = event.title,
                        prompt = event.prompt,
                        options = event.options,
                        defaultOptionId = event.defaultOptionId,
                        conversationId = event.conversationId,
                        context = event.context,
                        alwaysAllowRules = event.alwaysAllowRules,
                    )
                val index = outstanding.indexOfFirst { it.modalId == event.modalId }
                copy(outstanding = if (index < 0) outstanding + open else outstanding.toMutableList().also { it[index] = open })
            }
        }
        is ModalEvent.Dismissed -> {
            val held = outstanding.firstOrNull { it.modalId == event.modalId }
            if (held == null) {
                this
            } else {
                copy(
                    outstanding = outstanding - held,
                    resolved =
                        resolved +
                            ModalUiState.Dismissed(
                                modalId = event.modalId,
                                outcome = event.outcome,
                                source = event.source,
                                conversationId = held.conversationId,
                            ),
                )
            }
        }
    }

/**
 * The host's prompts as one thread with [conversationId] sees them (#1337): its first outstanding prompt;
 * with none, its most recent dismissal, so the thread's dismissal message still fires; otherwise
 * [ModalUiState.Hidden]. Matching goes through [ModalUiState.scopedTo], so a blank conversation matches nothing.
 */
fun HostModalState.scopedTo(conversationId: String): ModalUiState =
    outstanding.firstOrNull { it.scopedTo(conversationId) !== ModalUiState.Hidden }
        ?: resolved.lastOrNull { it.scopedTo(conversationId) !== ModalUiState.Hidden }
        ?: ModalUiState.Hidden

/**
 * The single-value view the conversation-list attention readers still take (#1337, until #1338 reads the
 * whole list): the last outstanding prompt, else [ModalUiState.Hidden]. Never a [ModalUiState.Dismissed].
 */
val HostModalState.latestOutstanding: ModalUiState
    get() = outstanding.lastOrNull() ?: ModalUiState.Hidden

/**
 * The host's modal as one thread with [conversationId] sees it (#816): the receiver when it is
 * [ModalUiState.Open] or [ModalUiState.Dismissed] and its conversation is non-blank and equal to
 * [conversationId], else [ModalUiState.Hidden]. A blank conversation on either side matches nothing, so
 * an unscoped modal renders in no thread rather than in every thread. Pure, and like the host fold it never
 * logs a modal field.
 */
fun ModalUiState.scopedTo(conversationId: String): ModalUiState {
    val owner =
        when (this) {
            is ModalUiState.Open -> this.conversationId
            is ModalUiState.Dismissed -> this.conversationId
            ModalUiState.Hidden -> return this
        }
    return if (owner.isNotBlank() && owner == conversationId) this else ModalUiState.Hidden
}
