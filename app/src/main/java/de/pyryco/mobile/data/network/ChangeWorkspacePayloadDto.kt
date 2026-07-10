package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `change_workspace` request payload (#560): the phone→binary request that changes
 * an existing conversation's workspace (channel or discussion). **Encode-only** — the phone sends it;
 * the server replies with a `conversation_updated` envelope carrying the bare updated conversation
 * object (the new [cwd] reflected), decoded through the #318 [ConversationResponseDto] boundary — the
 * same reply reuse as [RenameConversationPayloadDto] (#530). Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/conversations_write.go` `ChangeWorkspacePayload` (#823), whose
 * two fields are both **required** non-pointer `string` (`conversation_id`, `cwd`). The path field is
 * `cwd`, **not** `workspace` — "workspace" **is** the conversation's `cwd`; this codebase has no
 * separate workspace-id concept, so the target is a filesystem path (mirroring create/promote `cwd`),
 * not an id. A sibling of [RenameConversationPayloadDto] with `name` replaced by `cwd`; field
 * declaration order matches the Go struct (decode is by key name, so order has no wire effect).
 *
 * Both fields are **non-null/required** — with no Kotlin defaults every field is always sent
 * ([MobileJson]'s `explicitNulls=false` never elides a non-null `String`). The [cwd] is an
 * **untrusted** path forwarded **verbatim**: the phone does **not** validate, canonicalise, or open
 * it — the daemon confines it to `$HOME` (fail-closed, strict non-creating confiner) *before* storing
 * the resolved realpath and rejects out-of-`$HOME` / empty paths server-side (`protocol.malformed`),
 * treated here as an ordinary server error. Adding a client-side path check would be false assurance
 * (the phone cannot know the daemon's `$HOME`).
 *
 * Encode-only — model only what is sent (the [RenameConversationPayloadDto] /
 * [SendMessagePayloadDto] discipline), not the full decode surface (#318's job). The JSON key is
 * already `cwd`, so [cwd] needs no `@SerialName`.
 */
@Serializable
data class ChangeWorkspacePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val cwd: String,
)
