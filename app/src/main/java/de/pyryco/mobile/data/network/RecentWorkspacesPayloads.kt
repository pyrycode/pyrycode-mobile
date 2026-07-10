package de.pyryco.mobile.data.network

import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `recent_workspaces_list` reply payload (#565): the daemon's correlated reply to
 * a `recent_workspaces` request, carrying the distinct workspace folders recently bound to any
 * conversation. **Decode-only** — the phone sends the bare `recent_workspaces` request (empty `{}`
 * payload, built inline like `list_conversations`) and decodes this reply. Always decode through
 * [MobileJson] (`MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(...)`).
 *
 * Wire SSOT: server `internal/protocol/workspace.go` `RecentWorkspacesListPayload{ Workspaces }`
 * (#888, the recents half of the split #825). The `workspaces` slice is **always present** — an empty
 * result marshals as `"workspaces":[]`, never `null`. Ordering and dedup are **daemon-authoritative**
 * (the handler folds the conversations registry's distinct non-empty `Cwd` values, most-recent-first),
 * so the client preserves wire order and does **not** re-sort or re-dedup. Request↔reply correlation
 * rides `Envelope.inReplyTo`, not a payload field.
 */
@Serializable
data class RecentWorkspacesListPayloadDto(
    val workspaces: List<RecentWorkspaceDto>,
)

/**
 * One recent-workspace row (#565). **Decode-only** — only `path` is consumed (the folder cwd the
 * picker surfaces and forwards to a later `change_workspace`). The daemon's `last_used_at` timestamp
 * is deliberately **not** modeled: the Figma "Last used …" recency subtitle is out of scope for this
 * wire, and [MobileJson]'s `ignoreUnknownKeys = true` tolerates it (and any future row field) on
 * decode — the "model only what you consume" reply-DTO discipline ([ConversationDeletedPayloadDto]).
 * The JSON key is already the wire name, so no `@SerialName` is needed.
 */
@Serializable
data class RecentWorkspaceDto(
    val path: String,
)
