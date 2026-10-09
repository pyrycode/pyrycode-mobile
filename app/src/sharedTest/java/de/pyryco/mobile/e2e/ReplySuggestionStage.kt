package de.pyryco.mobile.e2e

/** Fixed checkpoints; never include daemon text or pairing material. */
internal enum class ReplySuggestionStage {
    ChannelList,
    Connected,
    CreateConversation,
    OpenThread,
    ResolveConversation,
    Repository,
    OpenPeer,
    SendInitialMessage,
    InitialReply,
    SessionReady,
    DaemonOffer,
    Placeholder,
    LongPressRelease,
    UserEcho,
    RevisionedClear,
    PlaceholderRemoved,
}
