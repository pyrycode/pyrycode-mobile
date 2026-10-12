package de.pyryco.mobile.e2e

/** Static labels only: no note paths, markers, replies or credentials in diagnostic records. */
internal enum class MarkdownNoteStage {
    PeerConnection,
    ListArrival,
    PhoneConnection,
    ChatCreation,
    PeerReadiness,
    ThreadOpening,
    FirstSend,
    FirstTurnEnd,
    FirstOpen,
    RewriteSend,
    RewriteTurnEnd,
    Refresh,
    Back,
    Reopen,
    FinalBack,
}

internal inline fun <T> markdownNoteStep(
    stage: MarkdownNoteStage,
    record: (String) -> Unit,
    block: () -> T,
): T {
    record("event=markdown_note_step stage=${stage.name} outcome=start")
    return try {
        block().also { record("event=markdown_note_step stage=${stage.name} outcome=complete") }
    } catch (failure: Throwable) {
        record("event=markdown_note_step stage=${stage.name} outcome=failure")
        throw failure
    }
}
