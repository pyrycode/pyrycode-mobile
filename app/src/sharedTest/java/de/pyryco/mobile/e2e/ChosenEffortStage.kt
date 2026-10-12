package de.pyryco.mobile.e2e

/** Fixed operation names only; never include settings, prompts or identity in diagnostics. */
internal enum class ChosenEffortStage {
    Readiness,
    ClearRemembered,
    AcquireRepository,
    CreateChat,
    RenameChat,
    InitialSettings,
    PublishedMenu,
    OpenChat,
    ModelTap,
    ModelAcknowledgement,
    EffortTap,
    EffortAcknowledgement,
    ChosenReading,
    BeforeTurnFooter,
    FirstSend,
    DisplayedReply,
    AppliedReading,
    LeaveChat,
    ReopenChat,
    ReopenedFooter,
    Cleanup,
}

internal inline fun <T> chosenEffortStep(
    stage: ChosenEffortStage,
    record: (String) -> Unit,
    block: () -> T,
): T {
    record("event=chosen_effort_step stage=${stage.name} outcome=start")
    return try {
        block().also { record("event=chosen_effort_step stage=${stage.name} outcome=complete") }
    } catch (e: Exception) {
        record("event=chosen_effort_step stage=${stage.name} outcome=failure")
        throw AssertionError("chosen effort stage=${stage.name} failed", e)
    } catch (e: AssertionError) {
        record("event=chosen_effort_step stage=${stage.name} outcome=failure")
        throw AssertionError("chosen effort stage=${stage.name} failed", e)
    }
}
