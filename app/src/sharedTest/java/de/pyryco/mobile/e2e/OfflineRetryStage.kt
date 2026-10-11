package de.pyryco.mobile.e2e

/** Static diagnostic vocabulary; no labels come from daemon or prompt text. */
internal enum class OfflineRetryStage {
    ListArrival,
    HostSetup,
    ReadConversations,
    CreateThread,
    ReadConversationId,
    StopDaemon,
    OfflinePill,
    CappedBackoff,
    RestartDaemon,
    BeforeRetry,
    TapRetry,
    RepositoryRecovery,
    PillClearing,
    SameConversation,
    SendPrompt,
    RenderReply,
    RestoreDaemon,
}
