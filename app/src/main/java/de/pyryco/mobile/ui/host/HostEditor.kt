package de.pyryco.mobile.ui.host

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.HostSystemPromptReading
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.components.EditHostModal
import de.pyryco.mobile.ui.components.MobileModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** An unread value is never equivalent to an explicitly empty prompt. */
sealed interface HostPromptState {
    data object Loading : HostPromptState

    data object Unavailable : HostPromptState

    data class Loaded(
        val confirmed: String,
        val defaultPrompt: String,
        val draft: String,
        val saving: Boolean = false,
        val failed: Boolean = false,
    ) : HostPromptState {
        val overLimit: Boolean get() = !SystemPromptLimit.fits(draft)
        val canSave: Boolean get() = !saving && !overLimit
        val showReset: Boolean get() = draft != defaultPrompt

        override fun toString(): String = "Loaded(text=<redacted>, saving=$saving, failed=$failed)"
    }
}

sealed interface HostPromptEvent {
    data object Open : HostPromptEvent

    data class Edit(
        val text: String,
    ) : HostPromptEvent {
        override fun toString(): String = "Edit(text=<redacted>)"
    }

    data object Reset : HostPromptEvent

    data object Save : HostPromptEvent

    data object Discard : HostPromptEvent
}

/**
 * The Edit host modal's target and the caller-owned flags that component requires (#744).
 *
 * Holds **display text and the target id only** — never the [de.pyryco.mobile.data.crypto.PairedServer]
 * it was read from. That record also carries the pairing token and the server static key; keeping it here
 * would put both credentials in a `StateFlow` that outlives the modal, for no gain: the two fields the
 * ticket allows are copied out at open time and the entry is dropped. Never give this class a
 * record-typed field — it has no redacting `toString`, so a crash trace renders whatever it holds.
 *
 * [serverIdentity] and [relayAddress] are carried unclamped, deliberately. Since #752
 * `parsePairingPayload` bounds each of them at 512 UTF-8 bytes and rejects an over-long payload
 * outright, so neither is unbounded any more — but that is a length ceiling, not a display bound, and
 * 512 bytes is still four times what this surface can draw. The clamp therefore stays where
 * `EditHostModal` already applies it, at that component's own boundary before layout and semantics,
 * and it keeps its own unit: the parser counts bytes because it is defending the route argument and
 * the saved-state `Bundle`, while the modal counts characters because it is defending a line of text.
 * A record paired before #752 is also read back unbounded, which the render-side clamp covers and the
 * parser cannot. The modal keys its name buffer on the raw identity so two hosts sharing a
 * 128-character prefix cannot collapse onto one buffer; clamping here would defeat that. Nothing
 * outside the modal reads either field.
 *
 * [failed] and [unpairFailed] are flags rather than messages so the string resolves at the screen, which
 * keeps the owning view model free of `Context` and makes it impossible for an identity or a relay
 * address to reach the shell's live region. The name draft is absent for the same division:
 * `EditHostModal` owns its own buffer, so a failed save keeps what the operator typed with no
 * view-model involvement — provided the same state instance stays published, which is why the failure
 * path copies rather than reopens.
 *
 * [confirmingUnpair] is a flag on the open editor rather than a second pending-target flow (#745): the
 * target is already here. [serverId] is the exact id the modal was opened for, and `remove` being id-exact
 * and a no-op on an unknown id protects the other hosts only if the id handed to it is the right one — a
 * second id would be a second source of truth for which host is being removed.
 *
 * [unpairFailed] is separate from [failed] rather than shared so the screen picks its string from an
 * explicit flag instead of inferring the failing operation from [confirmingUnpair].
 *
 * [saving] means "a write is in flight; block the rest", and covers the removal as well as the rename —
 * a fifth flag would say the same thing.
 */
data class HostEditorState(
    val serverId: String,
    val serverIdentity: String,
    val relayAddress: String,
    val initialName: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val confirmingUnpair: Boolean = false,
    val unpairFailed: Boolean = false,
    val prompt: HostPromptState = HostPromptState.Loading,
    val editingPrompt: Boolean = false,
)

/**
 * The Edit host modal's state machine, shared by the two screens that open it (#751).
 *
 * Extracted from `ChannelListViewModel`, where it shipped in #744/#745, when Settings became its
 * second driver. A plain object rather than a second view model: the two owners have different
 * lifetimes and different constructor graphs, and this composes into both without either inheriting
 * the other's dependencies. One instance per owner, so two destinations never share an open editor.
 *
 * [pairedServers] is the modal's read and write, and the only store this class touches. It reads
 * exactly two of a record's four fields, `serverId` and `relayUrl`, and never the pairing token or
 * the server static key.
 *
 * **[scope] must be the owning view model's `viewModelScope`.** Clearing that owner has to cancel the
 * open-read, the rename and the removal; an application-lifetime scope would outlive the screen and
 * leave a write publishing into an editor nothing is watching. Taking a `ViewModel` instead would
 * enforce it structurally and defeat the point of the seam, so it is an obligation on the caller.
 */
class HostEditorController(
    private val scope: CoroutineScope,
    private val pairedServers: PairedServerCollectionStore,
    private val appPreferences: AppPreferences,
    private val repositoryFor: (String) -> ConversationRepository? = { null },
) {
    private val editor = MutableStateFlow<HostEditorState?>(null)

    /** The host whose Edit host modal is open, or null when none is. */
    val state: StateFlow<HostEditorState?> = editor.asStateFlow()

    // Conflated so a send while no destination collects (a rotation) waits for the next collector and
    // never suspends the removal that sent it.
    private val lastHostGone = Channel<Unit>(Channel.CONFLATED)

    /**
     * Fires once after a successful unpair leaves no saved host (#1323), as desktop's `runUnpairServer`
     * re-reads its servers and calls `onLastServerUnpaired`. The owner's destination returns to Welcome.
     */
    val lastHostUnpaired: Flow<Unit> = lastHostGone.receiveAsFlow()

    // Read and written only from a tap dispatch on the main dispatcher, so it needs no synchronisation.
    private var openJob: Job? = null
    private var openToken = Any()

    /**
     * Opens the Edit host modal on [serverId]'s own stored record (#744).
     *
     * Only the most recently tapped control may publish, which is what the cancellation buys: two rows'
     * controls tapped while the first `loadById` is still decrypting race on the assignment otherwise,
     * and the slower read wins — the modal would then show, and rename, a host the operator did not tap
     * last. The caller's own `serverId` cannot close that; both calls carry a correct id and the defect
     * is in which reply lands. A cancelled launch cannot publish at all, rather than being asked to
     * check whether it still should.
     */
    fun open(serverId: String) {
        openJob?.cancel()
        val token = Any().also { openToken = it }
        editor.value = null
        openJob =
            scope.launch {
                val entry =
                    try {
                        pairedServers.loadById(serverId)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // `list` re-raises an unclassified failure, and this launch is in viewModelScope:
                        // an escaping throw would reach the default handler and kill the process.
                        RelayLog.d { "event=host_editor_open_failed" }
                        return@launch
                    }
                if (entry == null) {
                    // No record to read: the design's two identity rows cannot be drawn from an absent one.
                    RelayLog.d { "event=host_editor_open_rejected code=unknown_host" }
                    return@launch
                }
                if (openToken !== token) return@launch
                editor.value =
                    HostEditorState(
                        serverId = serverId,
                        serverIdentity = entry.record.serverId,
                        relayAddress = entry.record.relayUrl,
                        // Blank reads as unnamed, exactly as the row reads it: an empty field, never the
                        // id and never the list's placeholder text.
                        initialName = entry.displayName?.takeIf { it.isNotBlank() }.orEmpty(),
                    )
                RelayLog.d { "event=host_editor_opened" }
                val reading = promptResult { repositoryFor(serverId)?.requestHostSystemPrompt()?.getOrThrow() }
                val current = editor.value
                if (openToken === token && current != null && !current.saving) {
                    editor.value = current.copy(prompt = reading?.loaded() ?: HostPromptState.Unavailable)
                    RelayLog.d { "event=host_prompt_read outcome=${if (reading == null) "unavailable" else "loaded"}" }
                }
            }
    }

    /** Events carry no target: the captured open host owns every read and write. */
    fun onPromptEvent(event: HostPromptEvent) {
        val target = editor.value ?: return
        if (target.saving || target.confirmingUnpair) return
        val loaded = target.prompt as? HostPromptState.Loaded
        when (event) {
            HostPromptEvent.Open -> {
                if (loaded?.saving == true) return
                editor.value =
                    target.copy(editingPrompt = true, prompt = loaded?.copy(draft = loaded.confirmed, failed = false) ?: target.prompt)
            }
            HostPromptEvent.Discard -> {
                editor.value =
                    target.copy(
                        editingPrompt = false,
                        prompt =
                            loaded?.copy(draft = loaded.confirmed, saving = false, failed = false) ?: target.prompt,
                    )
                RelayLog.d { "event=host_prompt_discarded" }
            }
            is HostPromptEvent.Edit -> {
                if (!target.editingPrompt || loaded == null || loaded.saving) return
                editor.value = target.copy(prompt = loaded.copy(draft = event.text, failed = false))
            }
            HostPromptEvent.Reset -> {
                if (!target.editingPrompt || loaded == null || loaded.saving) return
                editor.value = target.copy(prompt = loaded.copy(draft = loaded.defaultPrompt, failed = false))
                RelayLog.d { "event=host_prompt_reset_draft" }
            }
            HostPromptEvent.Save -> {
                if (!target.editingPrompt || loaded == null || !loaded.canSave) return
                val pending = target.copy(prompt = loaded.copy(saving = true, failed = false))
                val token = openToken
                editor.value = pending
                scope.launch {
                    RelayLog.d { "event=host_prompt_save_started" }
                    val ack = promptResult { repositoryFor(target.serverId)?.setHostSystemPrompt(loaded.draft)?.getOrThrow() }
                    if (openToken === token && editor.value === pending) {
                        editor.value =
                            if (ack == null) {
                                pending.copy(prompt = loaded.copy(failed = true))
                            } else {
                                pending.copy(prompt = ack.loaded(), editingPrompt = false)
                            }
                    }
                    RelayLog.d { "event=host_prompt_save outcome=${if (ack == null) "failed" else "acknowledged"}" }
                }
            }
        }
    }

    private suspend fun promptResult(read: suspend () -> HostSystemPromptReading?): HostSystemPromptReading? =
        try {
            read()?.takeIf { SystemPromptLimit.fits(it.systemPrompt) && SystemPromptLimit.fits(it.defaultSystemPrompt) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private fun HostSystemPromptReading.loaded() = HostPromptState.Loaded(systemPrompt, defaultSystemPrompt, systemPrompt)

    /**
     * Saves the entered name as the open host's local display name, clearing it when blank.
     *
     * The trim is this method's own rather than trusted from the component, so its contract holds for any
     * caller. The clamp is the same [MAX_WORKSPACE_LABEL_CHARS] every surface that renders a host name
     * already applies, moved to the write: the name is operator-authored, but a single-line field still
     * accepts an arbitrary paste, and this value is stored inside the encrypted pairing blob that
     * `KeystorePairedServerStore.list` decrypts and parses on every revision bump and every registry
     * reconcile — so an oversized one is a recurring cost on the same read path that loads credentials.
     * Bytes past the bound were never renderable.
     *
     * Both terminal transitions are `compareAndSet` against the state published before the call, so a save
     * that completes after a dismissal cannot resurrect a closed modal or overwrite a newer one.
     */
    fun submitName(name: String) {
        val target = editor.value ?: return
        if (target.saving || target.editingPrompt) return
        val pending = target.copy(saving = true, failed = false)
        editor.value = pending
        scope.launch {
            RelayLog.d { "event=host_name_save_started" }
            try {
                pairedServers.setDisplayName(target.serverId, name.trim().take(MAX_WORKSPACE_LABEL_CHARS).ifBlank { null })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the name or the store's message; the UI gets one static string.
                RelayLog.d { "event=host_name_save_failed" }
                editor.compareAndSet(pending, pending.copy(saving = false, failed = true))
                return@launch
            }
            // An id no longer stored is a silent no-op in the store, and the host is already gone from the
            // tree, so closing is the right outcome for it too.
            editor.compareAndSet(pending, null)
            RelayLog.d { "event=host_name_saved" }
        }
    }

    /**
     * Arms the unpair confirmation on the open editor (#745).
     *
     * Guarded on [HostEditorState.saving] like every other transition here: the shell disables its OK
     * while loading but leaves the content live, so the `Unpair host` action can still be tapped during a
     * rename. Publishing a confirmation step under that rename's pending state would make its
     * `compareAndSet` fail and strand the modal on a step the store never took.
     */
    fun requestUnpair() {
        val target = editor.value ?: return
        if (target.saving || target.editingPrompt) return
        editor.value = target.copy(confirmingUnpair = true, failed = false, unpairFailed = false)
        RelayLog.d { "event=host_unpair_requested" }
    }

    /** Backs out of the confirmation without writing, leaving the editor open — never closing it. */
    fun declineUnpair() {
        val target = editor.value ?: return
        // Same guard, same reason: a Cancel tap mid-removal must not defeat that removal's own close.
        if (target.saving) return
        editor.value = target.copy(confirmingUnpair = false, unpairFailed = false)
        RelayLog.d { "event=host_unpair_declined" }
    }

    /**
     * Removes the confirmed host's pairing, then its cached default workspace, then closes the editor.
     *
     * The order is the requirement: a failed store write has to leave the pairing intact, so the
     * host-owned workspace preference is not cleared until the removal has reported success, and a cleared
     * cache is never evidence the host is gone. The connection close needs no call of its own — this is
     * the shared observable store, whose revision bump `RelayConnectionRegistry` reconciles by closing
     * exactly the removed id's bundle.
     *
     * Only this host's own pairing and its own workspace go: `remove` is id-exact and
     * `removeDefaultWorkspace` deletes a key built from a fixed prefix plus the id, which no other
     * host's key and no app-wide preference key can equal.
     *
     * A failed workspace clear is deliberately not surfaced: the pairing is already gone and the
     * connection already closing, so reporting a failure would claim the host is still paired when it is
     * not. It leaves one inert preference keyed by an id nothing is paired to any more.
     *
     * Both terminal transitions are `compareAndSet` against the state published before the call, so a
     * removal completing after a dismissal cannot resurrect a closed modal or overwrite a newer one.
     */
    fun confirmUnpair() {
        val target = editor.value ?: return
        if (target.saving || !target.confirmingUnpair) return
        val pending = target.copy(saving = true, failed = false, unpairFailed = false)
        editor.value = pending
        scope.launch {
            RelayLog.d { "event=host_unpair_started" }
            try {
                pairedServers.remove(target.serverId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the id or the store's message; the UI gets one static string.
                RelayLog.d { "event=host_unpair_failed" }
                editor.compareAndSet(pending, pending.copy(saving = false, unpairFailed = true))
                return@launch
            }
            // Only now: the pairing is gone, so clearing this host's own cached workspace cannot strand
            // a host that is still paired without one.
            appPreferences.removeDefaultWorkspace(target.serverId)
            editor.compareAndSet(pending, null)
            RelayLog.d { "event=host_unpaired" }
            // Last, after every cleanup above: the signal pops this owner's destination, and clearing its
            // view model cancels this scope. A failed read keeps the operator on the list.
            val remaining =
                try {
                    pairedServers.list().size
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=host_last_check_failed" }
                    return@launch
                }
            if (remaining == 0) lastHostGone.trySend(Unit)
        }
    }

    /**
     * Cancel, Close and Back all land here, and none of the three writes anything.
     *
     * Unguarded, unlike the three above: it publishes `null`, which is the state a completing write lands
     * on anyway, so there is no pending transition for it to strand.
     */
    fun dismiss() {
        openToken = Any()
        openJob?.cancel()
        editor.value = null
        RelayLog.d { "event=host_editor_dismissed" }
    }
}

/**
 * [EditHostModal] bound to a [HostEditorState], and the one place either screen's editor is drawn.
 *
 * Present exactly while [state] is non-null, read straight off the owning view model: the component
 * closes on none of its callbacks, so removing it from composition is the caller's job (#743).
 *
 * This binding exists so the modal's three caller obligations are met once rather than agreed to
 * twice. It owns the `saving` → `loading` mapping and, more importantly, the failure-flag → string
 * resolution: resolving here rather than in a view model keeps both of them free of `Context` and
 * makes it impossible for an identity or a relay address to reach the shell's live region. Which
 * failure is read from its own flag rather than inferred from the step the modal is on.
 *
 * `submissionEnabled` keeps its default: a blank name must be submittable, because clearing the name
 * is how a host returns to its unnamed treatment.
 */
@Composable
internal fun HostEditorModal(
    state: HostEditorState?,
    onSubmit: (String) -> Unit,
    onUnpairRequested: () -> Unit,
    onUnpairConfirmed: () -> Unit,
    onUnpairDeclined: () -> Unit,
    onDismissRequest: () -> Unit,
    onPromptEvent: (HostPromptEvent) -> Unit = {},
) {
    if (state == null) return
    EditHostModal(
        serverIdentity = state.serverIdentity,
        relayAddress = state.relayAddress,
        initialHostName = state.initialName,
        onDismissRequest = onDismissRequest,
        onSubmit = onSubmit,
        onUnpairRequested = onUnpairRequested,
        onUnpairConfirmed = onUnpairConfirmed,
        onUnpairDeclined = onUnpairDeclined,
        loading = state.saving,
        error =
            when {
                state.unpairFailed -> stringResource(R.string.edit_host_unpair_failed)
                state.failed -> stringResource(R.string.edit_host_save_failed)
                else -> null
            },
        confirmingUnpair = state.confirmingUnpair,
        promptRow = { HostPromptRow(state.prompt, state.saving) { onPromptEvent(HostPromptEvent.Open) } },
        promptEditor = if (state.editingPrompt) ({ HostPromptEditor(state.prompt, onPromptEvent) }) else null,
    )
}

private val HostPromptLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

internal const val HOST_PROMPT_FIELD_TAG = "host_prompt_field"
internal const val HOST_PROMPT_PREVIEW_TAG = "host_prompt_preview"

@Composable
private fun HostPromptRow(
    state: HostPromptState,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val subtitle =
        when (state) {
            HostPromptState.Loading -> stringResource(R.string.host_prompt_loading)
            HostPromptState.Unavailable -> stringResource(R.string.host_prompt_unavailable)
            is HostPromptState.Loaded -> state.confirmed.ifEmpty { stringResource(R.string.host_prompt_empty) }
        }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !disabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.host_prompt_title),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeightStyle = HostPromptLineBox),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                Modifier.fillMaxWidth().testTag(HOST_PROMPT_PREVIEW_TAG),
                style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = HostPromptLineBox),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(painterResource(R.drawable.ic_settings_chevron), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HostPromptEditor(
    state: HostPromptState,
    onEvent: (HostPromptEvent) -> Unit,
) {
    val loaded = state as? HostPromptState.Loaded
    val error = if (loaded?.overLimit == true || loaded?.failed == true) stringResource(R.string.host_prompt_failed) else null
    val title = stringResource(R.string.host_prompt_title)
    MobileModal(
        title = title,
        onDismissRequest = { onEvent(HostPromptEvent.Discard) },
        onSubmit = { onEvent(HostPromptEvent.Save) },
        submissionEnabled = loaded?.canSave == true,
        loading = loaded?.saving == true,
        error = error,
    ) {
        if (loaded == null) {
            Text(
                stringResource(if (state == HostPromptState.Loading) R.string.host_prompt_loading else R.string.host_prompt_unavailable),
                style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = HostPromptLineBox),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = loaded.draft,
                    onValueChange = { onEvent(HostPromptEvent.Edit(it)) },
                    enabled = !loaded.saving,
                    maxLines = 24,
                    modifier = Modifier.fillMaxWidth().testTag(HOST_PROMPT_FIELD_TAG).semantics { contentDescription = title },
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.modalFieldText,
                            lineHeightStyle = HostPromptLineBox,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { field ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 280.dp)
                                .background(MaterialTheme.colorScheme.modalFieldContainer, MaterialTheme.shapes.modalControl)
                                .padding(16.dp),
                        ) { field() }
                    },
                )
                Text(
                    stringResource(R.string.host_prompt_helper),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = HostPromptLineBox),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loaded.showReset) {
                    // Same outlined secondary geometry as UnpairAction, with Figma's 4 dp reset inset.
                    Surface(
                        onClick = { onEvent(HostPromptEvent.Reset) },
                        enabled = !loaded.saving,
                        modifier = Modifier.heightIn(min = 48.dp),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(Modifier.padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                            Box(
                                Modifier
                                    .heightIn(
                                        min = 40.dp,
                                    ).border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.modalControl)
                                    .padding(horizontal = 20.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.host_prompt_reset),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview @Composable
private fun HostPromptDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) { HostPromptEditor(HostPromptState.Loaded("", "", ""), {}) }
}

@Preview @Composable
private fun HostPromptLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) { HostPromptEditor(HostPromptState.Loaded("", "", ""), {}) }
}
