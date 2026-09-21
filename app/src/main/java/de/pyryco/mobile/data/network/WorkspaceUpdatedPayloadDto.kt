package de.pyryco.mobile.data.network

import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `workspace_updated` application payload (#721): the single typed
 * decode-and-validate boundary that turns the untrusted [Envelope.payload]
 * ([kotlinx.serialization.json.JsonElement]) of a workspace-label notification into two scalars the
 * repository's projection fold consumes.
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § Renaming a workspace (daemon #2209). **One DTO
 * models BOTH kinds of producer**: the correlated reply to `rename_workspace` and the unsolicited
 * push the daemon fans to every *other* interactive-capable conn. The record is identical either
 * way — the reply/push distinction lives at the [Envelope.inReplyTo] layer, not here — so do NOT
 * "fix" this by splitting into two byte-identical classes.
 *
 * The payload is a **bare object** at [Envelope.payload] (contrast #316's `conversations`, which
 * wraps an array), so [Envelope.payload] decodes directly to this type. Always decode through
 * [MobileJson] (`MobileJson.decodeFromJsonElement<WorkspaceUpdatedPayloadDto>(payload)`), never a
 * default `Json`. Decode-only: the phone never sends this payload (`rename_workspace` — the request
 * side — is #663's).
 *
 * No `toDomain` mapper: the payload is two scalars applied directly by
 * `RemoteConversationRepository`'s `applyWorkspaceLabel` fold, so a mapper would only add a second
 * throw site to a boundary whose whole point is that decode is the single one.
 */
@Serializable
data class WorkspaceUpdatedPayloadDto(
    /**
     * The workspace being named: the exact `cwd` string stored on one or more conversations. A
     * **lookup key and nothing more** — compared as bytes against [de.pyryco.mobile.data.model.Conversation.cwd]
     * and never resolved, normalized, joined, stat'd, opened or logged. Two paths differing only by a
     * trailing separator or case are distinct workspaces, so canonicalising here would merge
     * workspaces the daemon deliberately keeps apart (Security review).
     *
     * Required with **no default**: an absent key is a malformed frame, not an empty path, and must
     * fail at decode rather than silently label every `cwd = ""` row.
     */
    val path: String,
    /**
     * The operator-chosen display name now stored for [path], or `null` when it has been **cleared**.
     *
     * Nullable **with** a default so the protocol's two spellings of "clear" — the key omitted, and an
     * explicit `"label": null` — decode identically under [MobileJson]'s `explicitNulls = false`.
     *
     * Carried **verbatim**: never trimmed, truncated or sanitized. The daemon's 128-byte bound is a
     * size limit and not a safety property, and the client deliberately does not re-enforce it — a
     * silently shortened name is a different name than the operator typed. Blank is likewise stored as
     * given rather than folded to `null`, because the protocol keeps "labelled blank" and "unlabelled"
     * distinct. Safe **rendering** of this opaque text belongs to the consuming slices (#722, #641).
     */
    val label: String? = null,
)
