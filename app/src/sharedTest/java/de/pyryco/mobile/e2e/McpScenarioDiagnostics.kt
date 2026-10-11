package de.pyryco.mobile.e2e

/** Static checkpoints, independent of any daemon-authored text. */
internal enum class McpScenarioStage {
    ListArrival,
    Connection,
    ChatSetup,
    PingDelivery,
    SheetOpening,
    McpReport,
    ShowBuiltIn,
    BuiltInRow,
}

internal fun <T> mcpScenarioStep(
    stage: McpScenarioStage,
    snapshot: () -> String,
    emit: (String) -> Unit,
    block: () -> T,
): T {
    emit("event=mcp_scenario stage=${stage.name} status=started")
    val result =
        try {
            block()
        } catch (failure: Throwable) {
            val evidence = runCatching(snapshot).getOrDefault("connection=unknown")
            emit("event=mcp_scenario stage=${stage.name} status=failed $evidence")
            throw AssertionError("MCP scenario failed at ${stage.name}; $evidence", failure)
        }
    emit("event=mcp_scenario stage=${stage.name} status=completed")
    return result
}
