package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant

sealed interface ThreadEvent {
    data object NewSession : ThreadEvent

    data object Rename : ThreadEvent

    data class RenameSubmit(
        val name: String,
    ) : ThreadEvent

    data object RenameDismiss : ThreadEvent

    data object ChangeWorkspace : ThreadEvent

    data object Archive : ThreadEvent

    data object Delete : ThreadEvent

    data object DeleteConfirm : ThreadEvent

    data object DeleteDismiss : ThreadEvent

    data object ChannelInfo : ThreadEvent

    data object ChannelInfoDismiss : ThreadEvent

    data object SaveAsChannel : ThreadEvent

    /**
     * OK on the Save as channel modal (#957): promote in place under the trimmed [name], then store a
     * non-blank [systemPrompt] verbatim. [toString] is overridden: the generated one would print the
     * prompt, which may hold a pasted credential, into any crash trace or logged event.
     */
    data class SaveAsChannelSubmit(
        val name: String,
        val systemPrompt: String,
    ) : ThreadEvent {
        override fun toString(): String = "SaveAsChannelSubmit(name=$name, systemPrompt=<redacted>)"
    }

    data object SaveAsChannelDismiss : ThreadEvent
}

sealed interface ThreadNavigation {
    data object PopBack : ThreadNavigation

    /** Open the in-app reader on a markdown attachment of this thread (#1027): its id, never a name or path. */
    data class OpenMarkdown(
        val attachmentId: String,
    ) : ThreadNavigation

    /** Open the reader on the workspace note a link named (#1050). It carries nothing: the ViewModel holds the note. */
    data object OpenLinkedMarkdown : ThreadNavigation
}

data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    // #957: the conversation's own name, before [displayName]'s fallback; `null` when it has none.
    val conversationName: String? = null,
    val isPromoted: Boolean = false,
    // #1114: the agent that runs this conversation; the live status labels name it.
    // #1112: so do the reset line and the boundary explanation.
    // #1115: the usage-limit line and the effort notes name it too; Claude until the conversation is known.
    val agent: ConversationAgent = ConversationAgent.Claude,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val showRenameDialog: Boolean = false,
    val saveAsChannelDialog: SaveAsChannelDialogState? = null,
    val items: List<ThreadItem> = emptyList(),
    val queuedMessages: List<QueuedMessage> = emptyList(),
    val channelInfoOpen: Boolean = false,
    val deleteConfirmVisible: Boolean = false,
    val workspacePath: String = "",
    val lastUsedAt: Instant? = null,
    val sessionCount: Int = 0,
    // #807: the thread's whole run-configuration surface — what the daemon has configured, what it
    // published as selectable, and what a tap has asked for but not yet had confirmed. Replaces #544's
    // `selectedModel` / `selectedEffort` device-enum pair AND its `currentSessionId` routing field: the
    // session a write must address is [SessionSettings.sessionId], not Conversation.currentSessionId, and
    // one carrier for all three keeps the footer and the Status sheet agreeing by construction.
    val runConfig: ThreadRunConfig = ThreadRunConfig(),
    val mutationsSupported: Boolean = true,
    // #777/#778: what the thread's single oldest-end slot shows — loading, a retry, a dead end or
    // nothing. The walk's TERMINATION reasons deliberately do not reach the screen, only its failures:
    // the screen asks, the VM decides whether the ask is honoured, and a second copy of that decision in
    // Compose would be a second place to get it wrong.
    val historyTail: ThreadHistoryTail = ThreadHistoryTail.None,
    // #884: the Actions menu's commands this conversation's published slash-command menu proves absent,
    // greyed out in the menu.
    val absentActions: Set<ComposerAction> = emptySet(),
    // #678: this conversation's background-task roster on the open host — `null` when nothing has been
    // reported — and its live count (unfinished tasks plus dropped ones), which the Actions row shows.
    val backgroundTasks: BackgroundTaskRoster? = null,
    val backgroundTaskCount: Int = 0,
    // #885: this conversation's published slash commands, verbatim and in daemon order, or null while no
    // menu has been received. The composer's type-ahead reads them. They are workspace-authored, so they
    // reach the screen only through slashCommandOptions' inert display text, and a pick inserts the name.
    val slashCommands: List<SlashCommandMenuRow>? = null,
)

/**
 * The Save as channel modal's seed and flags (#957). No typed value lives here — the name and prompt are
 * the modal's own buffers — so a failure publishes a flag, never a daemon message, and no prompt reaches
 * a logged state. [promoted] is a promote the daemon confirmed: a retry then writes only the prompt.
 */
data class SaveAsChannelDialogState(
    val initialName: String,
    val saving: Boolean = false,
    val promoted: Boolean = false,
    val failure: SaveAsChannelFailure? = null,
)

/** Which write of Save as channel failed (#957); each resolves its own static string on screen. */
enum class SaveAsChannelFailure { Promote, SystemPrompt }

/**
 * One selectable model (#807) — a [de.pyryco.mobile.data.repository.ModelMenuRow] reduced to what the
 * Status sheet renders plus the argument a write sends back.
 *
 * **[value] is the only field that stays verbatim, and the only one that is never rendered.** It is the
 * argument [ConversationRepository.setSessionSettings] takes; it is an alias (`sonnet`), a bracketed
 * variant (`opus[1m]`) or `default`, so nothing parses it and nothing presents it as a version.
 * [label] is the display-only Claude family derived from [value] or the inert published name; Codex
 * uses the inert published name. [detail] is inert resolved text. [resolvedModel] stays raw for exact
 * inherited-default comparison and is never rendered directly.
 */
data class ThreadModelChoice(
    val value: String,
    val label: String,
    /** The row's `resolvedModel`, or `""` when it says nothing [label] does not already say. */
    val detail: String,
    val effortChoices: List<ThreadEffortChoice>,
    /** Whether the row accepts `auto` permission mode (#650) — the only thing that offers Auto approval. */
    val supportsAutoMode: Boolean = false,
    /** Exact concrete identifier for inherited-default resolution; never used as a write value. */
    val resolvedModel: String = "",
)

/** One selectable reasoning-effort level of one [ThreadModelChoice] (#807). Same split as its parent:
 *  [value] is the verbatim write argument, [label] the inert render of it. */
data class ThreadEffortChoice(
    val value: String,
    val label: String,
)

/**
 * The thread's run configuration (#807) — the daemon's saved reading, the vocabulary it published, and a
 * tap that has not yet been confirmed, in one value the footer line and the Status sheet both read.
 *
 * **Nothing here falls back to `AppPreferences`.** An unavailable reading is rendered as *unknown*: the
 * three-entry `Model` and five-entry `Effort` device enums are this phone's guesses, and a value this
 * server never published is refused server-side.
 *
 * @param choices The ordinary published models in daemon order; the `default` row is held in
 *   [inheritedChoice] for metadata and is never a visible choice.
 * @param menuAvailable Whether a menu was ever published for this conversation. `false` with empty
 *   [choices] is "no list"; `true` with empty [choices] is the different, equally legal reading that
 *   claude offered nothing.
 * @param droppedModels Entries the **producer** cut, exactly as reported and never recomputed from
 *   `choices.size` — what lets the sheet say "10 of 47" rather than present a shortened menu as complete.
 * @param hiddenChoices Entries **this client** cut at [MAX_RENDERED_MODEL_CHOICES]. Separate from
 *   [droppedModels] so each number keeps its provenance; the sheet sums them for display only.
 * @param settingsAvailable Whether a settings reading is available at all. `false` ⇒ both labels read
 *   unknown; it covers no connection, no `interactive` capability, and the window before the first reply.
 * @param savedModel The saved model override verbatim; `""` and confirmed `default` mean inherited.
 * @param savedEffort The **saved** effort choice verbatim, `""` meaning inherited default. It is the
 *   display fallback only while [appliedEffort] reports no value (#889), and never a write source.
 * @param pendingModel / @param pendingEffort A tap whose write has not settled, or `null`. Cleared by an
 *   arriving reading — never by the acknowledgement, which is not a reading.
 * @param sessionId The session a write must address. **`""` means the daemon has no session to address**,
 *   so the controls are read-only and nothing is sent.
 * @param permissionMode The permission mode the current child confirmed (#650), verbatim. `""` means no
 *   confirmation — no reading, a child that has not confirmed, a dormant session, or a reading left over
 *   from a session the conversation has since replaced. It is never filled from a pending write, an ack,
 *   stored settings or `yolo`.
 * @param pendingPermission A permission write whose request or settle is still running, or `null`. It
 *   marks the button pending and blocks a second write; it never changes the label.
 * @param appliedEffort Claude's **applied** effort (#889), verbatim from the reading. Display-only: it
 *   outranks [savedEffort] on screen and is never sent back.
 * @param running What claude says it runs (#891), for the Status sheet only. Independent of the selection:
 *   it is never derived from [savedModel] or [pendingModel], and nothing falls back to them.
 * @param contextPercent How full the context window is, as Claude last reported it (#946), verbatim. `null` is
 *   the unavailable state: no reading yet, a refused ask, a reconnect or a session transition. The footer and
 *   the Status sheet both read it, and it is never derived from token totals or the settings' figures.
 * @param capabilities What the session accepts (#1111), or `null` when the reading carried no list. A list
 *   narrows [effortChoices], the permission menu and the Actions commands; `null` narrows nothing.
 * @param memorySearch Search access for this thread's current session; unknown while its reading is pending.
 */
data class ThreadRunConfig(
    val choices: List<ThreadModelChoice> = emptyList(),
    /** Published default metadata, deliberately absent from the visible [choices]. */
    val inheritedChoice: ThreadModelChoice? = null,
    /** Whether its concrete identifier matches exactly one row in the full published menu. */
    val inheritedResolutionUnique: Boolean = true,
    val menuAvailable: Boolean = false,
    val droppedModels: Int = 0,
    val hiddenChoices: Int = 0,
    val settingsAvailable: Boolean = false,
    val savedModel: String = "",
    val savedEffort: String = "",
    val pendingModel: String? = null,
    val pendingEffort: String? = null,
    val sessionId: String = "",
    val permissionMode: String = "",
    val pendingPermission: String? = null,
    val appliedEffort: EffectiveEffort = EffectiveEffort.Unavailable,
    val running: ThreadRunningModel = ThreadRunningModel(),
    val contextPercent: Int? = null,
    val capabilities: SessionCapabilities? = null,
    val memorySearch: MemorySearchReport = MemorySearchReport.Unknown,
) {
    /** What the surfaces show: a pending tap while one is outstanding, the confirmed reading otherwise. */
    val selectedModel: String get() = pendingModel ?: savedModel

    /**
     * The effort the surfaces show and select (#889, desktop #1549 / #1554): a pending tap, else the value
     * Claude applies, else — only when that reading is missing or empty — the saved choice. An explicit
     * `null` reading selects nothing rather than falling back: Claude says it runs no effort parameter.
     */
    val selectedEffort: String
        get() =
            pendingEffort ?: when (appliedEffort) {
                is EffectiveEffort.Applied -> appliedEffort.value.ifEmpty { savedEffort }
                EffectiveEffort.NotReported -> ""
                EffectiveEffort.Unavailable -> savedEffort
            }

    /** Why [selectedEffort] is not Claude's applied value, or `null` when it is (or a tap is pending, or
     *  there is no reading at all). */
    val effortNote: EffortNote?
        get() =
            when {
                !settingsAvailable || pendingEffort != null -> null
                appliedEffort == EffectiveEffort.NotReported -> EffortNote.NotReported
                appliedEffort is EffectiveEffort.Applied && appliedEffort.value.isNotEmpty() -> null
                savedEffort.isNotEmpty() -> EffortNote.SelectedRunningUnavailable
                else -> EffortNote.DefaultRunningUnavailable
            }

    /** Only a confirmed inherited choice may resolve through the hidden default's concrete identifier. */
    val selectedChoice: ThreadModelChoice?
        get() {
            if (!settingsAvailable && pendingModel == null) return null
            if (pendingModel == null && (savedModel.isEmpty() || savedModel == INHERITED_DEFAULT_MODEL_VALUE)) {
                val resolved = inheritedChoice?.resolvedModel.orEmpty()
                if (!inheritedResolutionUnique || resolved.isBlank() || resolved.startsWith("<")) return null
                return choices.filter { it.resolvedModel == resolved }.singleOrNull()
            }
            return choices.firstOrNull { it.value == selectedModel }
        }

    /** Metadata follows the saved inherited setting even when no ordinary row can represent it. */
    val selectedMetadata: ThreadModelChoice?
        get() =
            if (!settingsAvailable && pendingModel == null) {
                null
            } else if (pendingModel == null && (savedModel.isEmpty() || savedModel == INHERITED_DEFAULT_MODEL_VALUE)) {
                inheritedChoice
            } else {
                selectedChoice
            }

    /** Published effort levels for the explicit row or hidden inherited metadata, narrowed by capabilities. */
    val effortChoices: List<ThreadEffortChoice>
        get() {
            val levels = selectedMetadata?.effortChoices.orEmpty()
            val accepted = capabilities?.effortLevels ?: return levels
            return levels.filter { it.value in accepted }
        }

    /** Whether a model or effort write is outstanding: the surfaces keep it visibly distinct from confirmed
     *  state. A permission write is [pendingPermission], kept apart so it gates only its own control. */
    val pending: Boolean get() = pendingModel != null || pendingEffort != null

    /** Whether a write can be addressed at all — the `""`-session-id read-only gate. */
    val writable: Boolean get() = sessionId.isNotEmpty()

    /** The footer's model segment, sourced from selection rather than the independent running reading. */
    val modelLabel: String
        get() =
            when {
                !settingsAvailable && pendingModel == null -> UNKNOWN_RUN_CONFIG_LABEL
                selectedChoice != null -> selectedChoice?.label.orEmpty()
                selectedModel.isEmpty() || (pendingModel == null && savedModel == INHERITED_DEFAULT_MODEL_VALUE) ->
                    UNAVAILABLE_MODEL_LABEL
                else -> selectedModel.inert()
            }

    /** Text for an unrepresented confirmed or pending choice in the sheet, never a radio label. */
    val modelSelectionNote: String?
        get() = modelLabel.takeIf { settingsAvailable && selectedChoice == null }

    /** The footer's effort segment. No menu lookup: a level is its own label. With nothing selected it
     *  names the control (#889) rather than claiming "default", which an explicit `null` would contradict. */
    val effortLabel: String
        get() =
            when {
                !settingsAvailable -> UNKNOWN_RUN_CONFIG_LABEL
                selectedEffort.isEmpty() -> EFFORT_PLACEHOLDER_LABEL
                else -> selectedEffort.inert()
            }
}

internal const val UNKNOWN_RUN_CONFIG_LABEL = "unknown"

internal const val UNAVAILABLE_MODEL_LABEL = "Model unavailable"

/** The published row `value` the daemon gives the inherited-default model (#972). A lookup key, not a label. */
internal const val INHERITED_DEFAULT_MODEL_VALUE = "default"

internal const val EFFORT_PLACEHOLDER_LABEL = "Effort"

/** Why the effort control does not show Claude's applied value (#889). Resolved to text at the UI layer. */
enum class EffortNote {
    /** The saved choice is shown; the running effort is unavailable. */
    SelectedRunningUnavailable,

    /** Nothing is selected, so Claude's default applies; the running effort is unavailable. */
    DefaultRunningUnavailable,

    /** Claude reports no effort parameter. */
    NotReported,
}

/**
 * One claude-reported value (#891), already made inert by the ViewModel. [truncated] says characters are
 * missing, whether the daemon cut them or the client's inert bound did, so the value is never shown as whole.
 */
data class ThreadReportedText(
    val text: String,
    val truncated: Boolean,
)

/**
 * What claude says it runs for this conversation (#891): the model it announced for the latest turn and its
 * own build. [model] `null` is the explicit unavailable state (nothing announced yet, or the reading was
 * cleared); [build] `null` means claude reported none, and the line is left out.
 */
data class ThreadRunningModel(
    val model: ThreadReportedText? = null,
    val build: ThreadReportedText? = null,
)
