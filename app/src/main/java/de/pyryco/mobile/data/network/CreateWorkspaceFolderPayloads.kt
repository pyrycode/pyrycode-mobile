package de.pyryco.mobile.data.network

import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `create_workspace_folder` request payload (#564): the phone→binary request that
 * creates a new workspace folder on the daemon. **Encode-only** — the phone sends it; the daemon
 * replies with a `workspace_folder_created` carrying the created folder's canonical path
 * ([WorkspaceFolderCreatedPayloadDto]). Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/workspace.go` `CreateWorkspaceFolderPayload` (pyrycode#887),
 * two required non-pointer `string` fields — the JSON keys `parent` / `name` are already the wire
 * names, so no `@SerialName` is needed.
 *
 * `parent` is the parent directory (the fixed client root `~/pyry-workspace`, tilde-anchored to the
 * daemon `$HOME`) and `name` is the single path element the operator typed. **Both are untrusted
 * path components** — the phone neither validates nor canonicalises them and never touches the
 * filesystem with them; the daemon joins them, confines the result to `$HOME` (fail-closed,
 * symlink-resolved), and independently rejects a `name` that is not a clean single element (empty /
 * absolute / contains a separator / `..`). Both fields are **non-null/required** — with no Kotlin
 * default they are always sent ([MobileJson]'s `explicitNulls=false` never elides a non-null
 * `String`). Encode-only: model only what is sent (the [DeleteConversationPayloadDto] discipline).
 */
@Serializable
data class CreateWorkspaceFolderPayloadDto(
    val parent: String,
    val name: String,
)

/**
 * Mobile Protocol v2 `workspace_folder_created` reply payload (#564): the daemon's correlated reply
 * carrying the created folder's path. **Decode-only** — binary→phone. `path` is the **canonical,
 * symlink-resolved absolute path** of the created folder, confined to the daemon's `$HOME`
 * (server-authoritative — not a client-derived join). Request↔reply correlation rides
 * `Envelope.inReplyTo`, not a payload field.
 *
 * Wire SSOT: server `internal/protocol/workspace.go` `WorkspaceFolderCreatedPayload` (pyrycode#887).
 * The single JSON key is `path`, so no `@SerialName` is needed. The value flows to the Workspace
 * Picker's `onPicked` and becomes the selected workspace; the phone never opens or joins it (§
 * Security — the daemon owns confinement, the phone treats it as a display/wire string).
 */
@Serializable
data class WorkspaceFolderCreatedPayloadDto(
    val path: String,
)
