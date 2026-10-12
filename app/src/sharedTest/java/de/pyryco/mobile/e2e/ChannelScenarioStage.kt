package de.pyryco.mobile.e2e

internal enum class ChannelScenarioStage {
    ListArrival,
    Connection,
    SeededChannels,
    FixtureArchive,
    EmptyChannels,
    DefaultFolderProbe,
    Creation,
    CreationIdentity,
    FirstReply,
    EditorOpen,
    ReturnToList,
    OriginalPromptRead,
    RenameAndPromptSave,
    EditedPromptRead,
    ResetBoundary,
    SecondReply,
    MatchingPromptStatus,
    ResetPromptRead,
    PromptClear,
    NullPromptRead,
    EmptyChannelInfo,
    EmptyEditorRead,
    Archive,
    Restore,
    CleanupDelete,
    CleanupRestore,
}

internal fun <T> channelScenarioStep(
    stage: ChannelScenarioStage,
    snapshot: () -> String,
    emit: (String) -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
    block: () -> T,
): T {
    fun record(outcome: String) {
        val state =
            try {
                snapshot()
            } catch (_: Exception) {
                "snapshot=unavailable"
            }
        emit("event=channel_scenario stage=$stage outcome=$outcome at_ms=${nowMs()} $state")
    }
    record("start")
    val result =
        try {
            block()
        } catch (failure: Throwable) {
            record("failed")
            throw failure
        }
    record("complete")
    return result
}
