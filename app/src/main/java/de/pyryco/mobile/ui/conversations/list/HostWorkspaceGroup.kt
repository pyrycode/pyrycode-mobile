package de.pyryco.mobile.ui.conversations.list

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.ui.workspace.workspaceDisplayName

/**
 * One conversation qualified by the host that owns it.
 *
 * [serverId] and [conversation]`.id` identify a row together; neither is unique on its own across
 * hosts. Mirrors [HostConversationTarget]'s pairing without replacing it — the target addresses an
 * action, this addresses a row of state.
 */
data class HostConversationRow(
    val serverId: String,
    val conversation: Conversation,
)

/**
 * One host's conversations that share an exact [cwd].
 *
 * The identity is the ([serverId], [cwd]) pair — that is the list key, and `cwd` is compared
 * exactly: not trimmed, normalised or case-folded. Two hosts holding the same path are two groups.
 *
 * [displayName] is **display text only**: never a key, a path, a filename or a log field. A rename
 * or a clear changes only that text, so no conversation moves between groups; keying a list on it
 * would collapse two distinct workspaces that share an operator-chosen name. Anything that acts on
 * the workspace reads [cwd] instead.
 */
data class HostWorkspaceGroup(
    val serverId: String,
    val cwd: String,
    val displayName: String,
    val conversations: List<HostConversationRow>,
)

/**
 * Groups one host's [conversations] by their exact `cwd`, preserving source order.
 *
 * Pure and synchronous: it subscribes to nothing, so a relabel is re-projected without any
 * resubscription. Group order is first-encounter, so a group takes the position of its first
 * conversation, and each group's members keep the order the host snapshot supplied. No sort key of
 * its own — the projection slices and groups, it never re-sorts.
 *
 * The display name comes from the shared [workspaceDisplayName] rule, read off the group's first
 * conversation: an applied `workspace_updated` carries the same label onto every row of the owning
 * host that shares the path, so the group is uniform, and the first row is already the row that
 * decides the group's position.
 */
internal fun groupConversationsByWorkspace(
    serverId: String,
    conversations: List<Conversation>,
): List<HostWorkspaceGroup> =
    // groupBy returns a LinkedHashMap: keys in first-encounter order, members in source order.
    conversations
        .groupBy { it.cwd }
        .map { (cwd, rows) ->
            HostWorkspaceGroup(
                serverId = serverId,
                cwd = cwd,
                displayName = workspaceDisplayName(cwd = cwd, label = rows.first().workspaceLabel),
                conversations = rows.map { HostConversationRow(serverId, it) },
            )
        }
