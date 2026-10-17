package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.ui.conversations.thread.authoritativeAgent
import de.pyryco.mobile.ui.conversations.thread.authoritativeItem
import de.pyryco.mobile.ui.conversations.thread.authoritativeSnapshot

/**
 * Agent 1 (raw task `raw-task`, raw call `raw-agent`) with prose 2 and tools 3 and 4 in the child turn;
 * main reply 5 in the main turn.
 */
internal fun agentReplySnapshot(
    child: ThreadItemDto = authoritativeItem(2, parent = 1, turn = "child"),
    main: ThreadItemDto = authoritativeItem(5, turn = "main"),
    root: ThreadItemDto = authoritativeAgent(1, task = "raw-task", turn = "launch", toolUse = "raw-agent"),
) = authoritativeSnapshot(
    root,
    child,
    authoritativeItem(3, "tool_call", parent = 1, content = """{"name":"Bash","tool_use_id":"raw-tool-3"}""", turn = "child"),
    authoritativeItem(4, "tool_call", parent = 1, content = """{"name":"Bash","tool_use_id":"raw-tool-4"}""", turn = "child"),
    main,
)
