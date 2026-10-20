package de.pyryco.mobile.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.e2e.InteractiveStreamE2ETest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Every method delegates to the original scenario, including its original native assertions. */
@RunWith(AndroidJUnit4::class)
class RecordedMobileSuiteTest {
    private val original = InteractiveStreamE2ETest()

    @get:Rule(order = Int.MIN_VALUE)
    val recording = RecordedSuiteRule()

    @get:Rule(order = Int.MIN_VALUE + 1)
    val cleanup = original.sessionCleanup

    @get:Rule val compose: TestRule = RecordedActivityRule(original.composeTestRule, RecordedSuiteNative::beforePhoneTeardown)

    @get:Rule val ime = original.testIme

    @get:Rule val unrecognized = original.unrecognizedRowSentinel

    @Test
    fun interactiveTurn_archiveRestore_roundTripsListMembership() = original.interactiveTurn_archiveRestore_roundTripsListMembership()

    @Test fun interactiveTurn_archiveTwoChats_listsSecondArchivedFirst() =
        original
            .interactiveTurn_archiveTwoChats_listsSecondArchivedFirst()

    @Test fun interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes() =
        original.interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes()

    @Test
    fun interactiveTurn_attentionDot_followsARealTurn() = original.interactiveTurn_attentionDot_followsARealTurn()

    @Test fun interactiveTurn_backgroundAgentProgress_showsOnRunningCard() =
        original
            .interactiveTurn_backgroundAgentProgress_showsOnRunningCard()

    @Test fun interactiveTurn_backgroundAgent_followsBottomUntilFinished() =
        original
            .interactiveTurn_backgroundAgent_followsBottomUntilFinished()

    @Test
    fun interactiveTurn_backgroundAgent_replyStaysUnderAgent() = original.interactiveTurn_backgroundAgent_replyStaysUnderAgent()

    @Test fun interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect() =
        original.interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect()

    @Test fun interactiveTurn_backgroundTask_countsInActionsMenuAndPanel() =
        original
            .interactiveTurn_backgroundTask_countsInActionsMenuAndPanel()

    @Test fun interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread() =
        original.interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread()

    @Test fun interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn() =
        original.interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn()

    @Test
    fun interactiveTurn_chosenEffort_appliesFromTheFirstTurn() = original.interactiveTurn_chosenEffort_appliesFromTheFirstTurn()

    @Test fun interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart() =
        original.interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart()

    @Test fun interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost() =
        original.interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost()

    @Test fun interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip() =
        original.interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip()

    @Test fun interactiveTurn_createEditArchiveChannel_readsPromptBack() =
        original
            .interactiveTurn_createEditArchiveChannel_readsPromptBack()

    @Test fun interactiveTurn_deleteConversation_removesFromListAndClosesThread() =
        original.interactiveTurn_deleteConversation_removesFromListAndClosesThread()

    @Test fun interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts() =
        original
            .interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts()

    @Test fun interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes() =
        original.interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes()

    @Test fun interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend() =
        original.interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend()

    @Test
    fun interactiveTurn_hostSystemPrompt_editsResetsAndCancels() = original.interactiveTurn_hostSystemPrompt_editsResetsAndCancels()

    @Test fun interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn() =
        original.interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn()

    @Test fun interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile() =
        original.interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile()

    @Test fun interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes() =
        original.interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes()

    @Test
    fun interactiveTurn_listArchiveEntry_opensArchived() = original.interactiveTurn_listArchiveEntry_opensArchived()

    @Test
    fun interactiveTurn_longRunningTool_statusAreaShowsElapsed() = original.interactiveTurn_longRunningTool_statusAreaShowsElapsed()

    @Test
    fun interactiveTurn_markdownLink_opensLiveNoteInReader() = original.interactiveTurn_markdownLink_opensLiveNoteInReader()

    @Test
    fun interactiveTurn_markdownReply_rendersFormattedBody() = original.interactiveTurn_markdownReply_rendersFormattedBody()

    @Test fun interactiveTurn_modelChange_roundTripsAndStaysPerConversation() =
        original.interactiveTurn_modelChange_roundTripsAndStaysPerConversation()

    @Test
    fun interactiveTurn_muteChannel_roundTripsThroughTheHost() = original.interactiveTurn_muteChannel_roundTripsThroughTheHost()

    @Test fun interactiveTurn_newSession_rendersSessionBoundaryDelimiter() =
        original
            .interactiveTurn_newSession_rendersSessionBoundaryDelimiter()

    @Test fun interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect() =
        original
            .interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect()

    @Test fun interactiveTurn_offlineRetry_reconnectsSameHostAndReplies() =
        original
            .interactiveTurn_offlineRetry_reconnectsSameHostAndReplies()

    @Test fun interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk() =
        original.interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk()

    @Test fun interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild() =
        original.interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild()

    @Test fun interactiveTurn_otherConversationAttentionPills_waitingAndFinished() =
        original.interactiveTurn_otherConversationAttentionPills_waitingAndFinished()

    @Test fun interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload() =
        original.interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload()

    @Test
    fun interactiveTurn_peerQueue_staysConsistentAcrossClients() = original.interactiveTurn_peerQueue_staysConsistentAcrossClients()

    @Test
    fun interactiveTurn_peerStartedTurn_continuesOnPhone() = original.interactiveTurn_peerStartedTurn_continuesOnPhone()

    @Test fun interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation() =
        original.interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation()

    @Test fun interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool() =
        original.interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool()

    @Test
    fun interactiveTurn_permissionPrompts_heldPerConversation() = original.interactiveTurn_permissionPrompts_heldPerConversation()

    @Test
    fun interactiveTurn_pingPrompt_footerShowsContextUsage() = original.interactiveTurn_pingPrompt_footerShowsContextUsage()

    @Test
    fun interactiveTurn_pingPrompt_statusSheetShowsRunningModel() = original.interactiveTurn_pingPrompt_statusSheetShowsRunningModel()

    @Test
    fun interactiveTurn_pingPrompt_streamsPingReplyIntoThread() = original.interactiveTurn_pingPrompt_streamsPingReplyIntoThread()

    @Test fun interactiveTurn_questionAnswer_reachesTheAskingConversation() =
        original.interactiveTurn_questionAnswer_reachesTheAskingConversation()

    @Test fun interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive() =
        original.interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive()

    @Test fun interactiveTurn_reconnect_slashCommandsAndCompactStillWork() =
        original
            .interactiveTurn_reconnect_slashCommandsAndCompactStillWork()

    @Test fun interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel() =
        original.interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel()

    @Test fun interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage() =
        original.interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage()

    @Test fun interactiveTurn_renameConversation_relabelsTopBarAndListRow() =
        original.interactiveTurn_renameConversation_relabelsTopBarAndListRow()

    @Test fun interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn() =
        original.interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn()

    @Test fun interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately() =
        original.interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately()

    @Test
    fun interactiveTurn_saveAsChannel_promotesToChannelTier() = original.interactiveTurn_saveAsChannel_promotesToChannelTier()

    @Test fun interactiveTurn_scannerConfirm_waitsForHostThenOpensList() =
        original
            .interactiveTurn_scannerConfirm_waitsForHostThenOpensList()

    @Test fun interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched() =
        original.interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched()

    @Test
    fun interactiveTurn_sendQueuedNow_reachesRunningTurn() = original.interactiveTurn_sendQueuedNow_reachesRunningTurn()

    @Test fun interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes() =
        original.interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes()

    @Test fun interactiveTurn_stopBackgroundTask_completesThenRepliesAgain() =
        original.interactiveTurn_stopBackgroundTask_completesThenRepliesAgain()

    @Test fun interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain() =
        original.interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain()

    @Test fun interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn() =
        original
            .interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn()

    @Test
    fun interactiveTurn_toolPrompt_rendersToolStepInThread() = original.interactiveTurn_toolPrompt_rendersToolStepInThread()

    @Test fun interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy() =
        original
            .interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy()

    @Test
    fun interactiveTurn_twoHostsArchive_staysPerHost() = original.interactiveTurn_twoHostsArchive_staysPerHost()

    @Test fun interactiveTurn_twoHostsCollidingConversationId_stayPerHost() =
        original.interactiveTurn_twoHostsCollidingConversationId_stayPerHost()

    @Test
    fun negativeControl_toolClaudeNeverUses_isNeverDisplayed() = original.negativeControl_toolClaudeNeverUses_isNeverDisplayed()

    @Test
    fun negativeControl_wordClaudeNeverSays_isNeverDisplayed() = original.negativeControl_wordClaudeNeverSays_isNeverDisplayed()
}
