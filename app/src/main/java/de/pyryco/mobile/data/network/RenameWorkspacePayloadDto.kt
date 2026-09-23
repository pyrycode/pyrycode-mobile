package de.pyryco.mobile.data.network

import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `rename_workspace` request payload (#663): set or clear the display name the
 * daemon stores for one workspace. **Encode-only**, through [MobileJson]. The daemon answers with a
 * correlated `workspace_updated` ([WorkspaceUpdatedPayloadDto]) or an `error`.
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § "Renaming a workspace". Correlation rides
 * [Envelope.inReplyTo], so there is no request-id key.
 *
 * @param path The workspace's exact `cwd`, sent as given — never trimmed or normalized, since the
 *   daemon compares it as bytes. A location on the daemon's host; never log it.
 * @param label The label to store, sent verbatim, or `null` to clear it. [MobileJson] omits a `null`,
 *   and the protocol reads an omitted key as the same clear. Operator-typed text; never log it.
 */
@Serializable
data class RenameWorkspacePayloadDto(
    val path: String,
    val label: String? = null,
)
