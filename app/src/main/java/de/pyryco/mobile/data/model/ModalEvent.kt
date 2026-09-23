package de.pyryco.mobile.data.model

/**
 * A decoded v2 interactive **modal** lifecycle event (#437): the typed in-process form of one of the
 * two **binary → phone** modal envelopes — `modal_shown` and `modal_dismissed` — the daemon emits when
 * the supervised `claude` surfaces a permission/choice modal and when that modal resolves
 * (pyrycode#701 wire types, #703 producer). Phase 3 of epic pyrycode#597 (ADR 025).
 *
 * The decode boundary lives in `data/network` (the `…PayloadDto.toEvent()` mappers); this is the
 * **portable** typed surface consumers read off `RemoteConversationRepository.modalEvents`. Decode-only:
 * nothing here folds `Shown`/`Dismissed` into a "current modal" projection, sends an answer, or renders
 * an overlay — those are the downstream consumer slices (#438 answer/cancel, #439 render UI, #440
 * read-only mode).
 *
 * [modalId] is the sole correlation key for answers (the phone treats it as an opaque token to echo back
 * in #438, never as a routing key it asserts; the daemon validates it against its own outstanding-modal
 * state). [Shown.conversationId] (daemon #1065) is an outbound-only **scoping** stamp naming the
 * conversation that raised the modal: the phone uses it to choose which thread displays the modal (#816)
 * and never sends it back. `modal_dismissed` carries no conversation. Modal events form their own family
 * on their own flow rather than a sixth [LiveSessionEvent], whose every subtype mandates a
 * `conversationId`.
 *
 * The free-form text fields ([Shown.title], [Shown.prompt], [ModalOption.label]) and the verbatim
 * strings ([Shown.modalClass], [Dismissed.outcome], [Dismissed.source]) are carried **verbatim** — the
 * decode seam neither trims, parses, nor sanitizes them. They may name a sensitive command or path, so a
 * rendering consumer (#439) MUST treat them as inert data (not markup/HTML/active content) and own its
 * own output-encoding at render time.
 *
 * Pure data, no Android imports — kept portable per CLAUDE.md (`data/` is a Compose Multiplatform
 * walk-back surface). The subtype names mirror the wire `type` strings 1:1.
 */
sealed interface ModalEvent {
    val modalId: String

    /**
     * A surfaced modal (`modal_shown`). [modalClass] is the wire `class` (a Kotlin keyword), carried as
     * a plain string over a closed set (e.g. `permission`) — **not** coerced to an enum, so a
     * forward-compat value survives (AC #3). [options] preserves wire array order, which **is** the
     * canonical display/selection order (AC #1/#5). [defaultOptionId] equals one of [options]`.id` by the
     * producer's invariant; this seam carries it verbatim and does not enforce the invariant.
     * [conversationId] is the conversation whose session raised the modal (#816), or `""` when the frame
     * omitted it — an unscoped modal that no thread displays. [context] is claude's optional decision
     * context for a permission ask (#817), [ModalContext.None] when the frame carried none.
     */
    data class Shown(
        override val modalId: String,
        val modalClass: String,
        val title: String,
        val prompt: String,
        val options: List<ModalOption>,
        val defaultOptionId: String,
        val conversationId: String = "",
        val context: ModalContext = ModalContext.None,
    ) : ModalEvent

    /**
     * A resolved modal (`modal_dismissed`). [outcome] is the selected option id when answered, or a
     * producer-defined sentinel on cancel/timeout. [source] is a closed set (`remote` | `local` |
     * `timeout`) distinguishing how it resolved. Both are carried as plain strings verbatim — a
     * forward-compat value survives rather than being dropped by an enum (AC #2/#3).
     */
    data class Dismissed(
        override val modalId: String,
        val outcome: String,
        val source: String,
    ) : ModalEvent
}

/**
 * One selectable modal option (`{id, label}`). Top-level (not nested under [ModalEvent.Shown]) for
 * consumer ergonomics — the render slice (#439) references it directly when laying out the option list.
 * [id] is the opaque token echoed back as the answer (#438); [label] is operator-authored display text.
 */
data class ModalOption(
    val id: String,
    val label: String,
)

/**
 * Claude's own decision context for a permission ask (#817, daemon #2346): `modal_shown`'s optional
 * `reason`, `reason_type`, `blocked_path` and `description`, each copied by the daemon from claude's
 * `can_use_tool` ask. `null` means absent — the wire makes absent and empty equivalent, so the decode seam
 * never hands out `""`. [reason] is display text even when the wire value was not a string (its JSON text,
 * so `false`, `0` and `null` stay visible), and [reasonType] is an open vocabulary carried verbatim.
 *
 * Every value is claude-authored and untrusted: display-only, never decision authority, never a path to
 * open or a value to log. A rendering consumer draws it as inert text.
 */
data class ModalContext(
    val reason: String? = null,
    val reasonType: String? = null,
    val blockedPath: String? = null,
    val description: String? = null,
) {
    /** `true` when the frame carried no context at all — the prompt then renders without a context area. */
    val isEmpty: Boolean
        get() = reason == null && reasonType == null && blockedPath == null && description == null

    companion object {
        val None = ModalContext()
    }
}
