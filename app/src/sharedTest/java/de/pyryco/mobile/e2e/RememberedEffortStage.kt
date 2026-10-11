package de.pyryco.mobile.e2e

/** Static stages only: never put a conversation, level, prompt or settings payload in diagnostics. */
internal enum class RememberedEffortStage {
    FixtureSetup,
    PrimingMenu,
    PrimingOpen,
    PrimingTap,
    PrimingAcknowledgement,
    PrimingPersistence,
    FreshFixtures,
    GraphRestart,
    RestartPersistence,
    ChatOpen,
    ChatRecallAcknowledgement,
    ChatRecallReading,
    ChatFirstSend,
    ChatFirstReply,
    ChatAppliedReading,
    ChatLeave,
    ChannelOpen,
    ChannelRecallAcknowledgement,
    ChannelRecallReading,
    ChannelFirstSend,
    ChannelFirstReply,
    ChannelAppliedReading,
    ChannelLeave,
    ExplicitChoice,
    CleanupSettings,
    CleanupActivity,
}

internal inline fun <T> rememberedEffortStep(
    stage: RememberedEffortStage,
    record: (String) -> Unit,
    block: () -> T,
): T {
    record("event=remembered_effort_step stage=${stage.name} outcome=start")
    return try {
        block().also { record("event=remembered_effort_step stage=${stage.name} outcome=complete") }
    } catch (e: Exception) {
        record("event=remembered_effort_step stage=${stage.name} outcome=failure")
        throw AssertionError("remembered effort stage=${stage.name} failed", e)
    } catch (e: AssertionError) {
        record("event=remembered_effort_step stage=${stage.name} outcome=failure")
        throw AssertionError("remembered effort stage=${stage.name} failed", e)
    }
}
