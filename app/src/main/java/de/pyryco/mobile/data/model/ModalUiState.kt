package de.pyryco.mobile.data.model

/**
 * The hoisted "current modal" projection (#445): which permission/choice modal — if any — is currently
 * outstanding across the app. Folded from the `replay = 0` [ModalEvent] stream (#437); because that
 * stream holds no current state, *this* slice owns the "which modal is open" accumulation (see [reduce]).
 *
 * The fold holds a **single** modal per host, keyed on `modalId`. [Open] and [Dismissed] carry the
 * conversation that raised the modal (#816), and each thread shows the host's modal only through
 * [scopedTo] its own conversation. The thread ViewModel exposes that scoped value
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
    ) : ModalUiState

    /**
     * The currently-open modal resolved. Mirrors [ModalEvent.Dismissed]; [source] is the verbatim
     * resolution reason (`remote` | `local` | `timeout`, or any forward-compat value unchanged).
     * [conversationId] is the resolved modal's own (#816): the wire dismiss carries none, so [reduce]
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
 * Folds one [ModalEvent] into the next [ModalUiState]. Pure and side-effect-free — **no logging** of any
 * modal field (the fields may name a sensitive command/path; mirrors the no-log contract of
 * `thinkingTransition` / `ThreadFold.reduce`).
 *
 * - [ModalEvent.Shown] → [ModalUiState.Open] carrying the event verbatim, **unconditionally** — a later
 *   `Shown` supersedes any currently-open modal (last-shown wins; AC #3).
 * - [ModalEvent.Dismissed] whose `modalId` matches the currently-[ModalUiState.Open] modal →
 *   [ModalUiState.Dismissed] carrying the verbatim resolution reason (AC #2) and the open modal's
 *   conversation (#816).
 * - [ModalEvent.Dismissed] in any other case (receiver [ModalUiState.Hidden], receiver already
 *   [ModalUiState.Dismissed], or [ModalUiState.Open] with a non-matching `modalId`) → receiver unchanged.
 *   The exact-`modalId` match is the spoofed-dismiss safety property: an out-of-band dismiss cannot
 *   silently clear an unresolved modal.
 */
internal fun ModalUiState.reduce(event: ModalEvent): ModalUiState =
    when (event) {
        is ModalEvent.Shown ->
            ModalUiState.Open(
                modalId = event.modalId,
                modalClass = event.modalClass,
                title = event.title,
                prompt = event.prompt,
                options = event.options,
                defaultOptionId = event.defaultOptionId,
                conversationId = event.conversationId,
                context = event.context,
            )
        is ModalEvent.Dismissed ->
            if (this is ModalUiState.Open && modalId == event.modalId) {
                ModalUiState.Dismissed(
                    modalId = event.modalId,
                    outcome = event.outcome,
                    source = event.source,
                    conversationId = conversationId,
                )
            } else {
                this
            }
    }

/**
 * The host's modal as one thread with [conversationId] sees it (#816): the receiver when it is
 * [ModalUiState.Open] or [ModalUiState.Dismissed] and its conversation is non-blank and equal to
 * [conversationId], else [ModalUiState.Hidden]. A blank conversation on either side matches nothing, so
 * an unscoped modal renders in no thread rather than in every thread. Pure, and like [reduce] it never
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
