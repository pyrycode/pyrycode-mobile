# Mobile 2245 sweep02 independent recording-contract review

Contract source review is complete for all 62 successful genuine captures. No recording was changed or promoted. This is not a strict-compatibility pass or final acceptance. The six original fresh replay failures remain blockers until exact-byte reruns pass. Five of the 67 selected methods failed native capture and are outside these62 successful-fixture decisions.

Evidence root on HP: `/work/Projects/pyrycode-mobile-agents/logs/evidence/2245/operator-manual-20261020/recording-refresh-02`. Reviewed current PR2264 and the2245 plan plus `docs/recorded-tests.md`. Published source head inspected for context:549543381bea021b42f211a1b5f264dae12075e3. These captured bytes were produced by39bf38b300320eb34eb2ac779d11ad2c4b37957f, daemon e596971abb101d44d770dea33b4d29d61300e85e and Claude2.1.280. The current source must still pass its own final build and device gates.

## What was checked in every case

Each capture file was read directly and its SHA-256 matched its genuine capture receipt. Every receipt counts one executed, zero failed and zero skipped native method with the common recorded source and APK hashes. The reviewed fixture, comparison, capture and evidence paths remain separate. Baseline sweep01 counts49 captures/45 replays are not added to sweep02 counts62/56.

All94 phone opens advertise thread. There are zero phone request_history/backfill_since requests and zero replaced legacy message/assistant_delta/tool_use/tool_result/turn_end/queue/background-task content frames. Raw legacy peer requests remain explicit diagnostic traffic on their own owners. The325 phone catch-ups all have an actually sent same-owner request and a matching terminal or reset. Conversations match. Nonzero tails retain their exact request epoch/from_version. Thirteen actual resets are followed by the matching conversation’s zero query with the reset epoch. The62 fixtures have no thread_page request. Therefore this set does not prove paging.

All456 canonical live updates carry explicit conversation and epoch ownership. The29 actual phone read commands follow already delivered terminal query certificates, and never exceed those real checkpoints. Accepted requests stay inside their physical owner’s open lifetime. All delivered query-batch revisions are at or below the reported reply checkpoint. The1102 explicit session clears carry empty payloads. The1324 nonempty phone session-family readings carry actual conversation/session tags. All24 permission answers select an option from the exact same peer/phone owner’s preceding modal. Forty turn identities agree between independent legacy peer terminals and canonical phone terminals where both are recorded.

This is an explicit production-route change: legacy phone history requests are replaced by real canonical catch-ups. Actual completion and preparation flows add summary reads. Settings reads retain their captured owner and current-session data; polling counts belong to the declared bounded policy and do not certify missing state. Query matching stays exact in replay. The56 normalised command sequences that happen to match are only a comparison fact. Their acceptance here comes from raw capability, owner, correlation, session, lifecycle and read-boundary inspection, not from normalisation.

## Explicit command differences

### interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn

A real Android push wake follows the peer terminal and precedes replacement open. Explicitly accepted as observed lifecycle input, with no generated wake. Extra actual registration remains required.

### interactiveTurn_peerQueue_staysConsistentAcrossClients

The phone dequeue includes accepted_item_id 13. Raw canonical fact 13 is queued on the same conversation, observed at100 before action101. Queue ID, accepted message identity and body agree. Accepted as required production attribution. Replay still fails.

### interactiveTurn_attentionDot_followsARealTurn

The peer read moves from15 to18. Raw peer history206 contains the entries. Its conversations reply208 identifies latest shown18. Read209 uses that exact fresh producer watermark. Accepted as real current history, not a numeric relaxation.

### interactiveTurn_backgroundAgent_followsBottomUntilFinished

The genuine producer asks four permission modals rather than three, with changed interleaving. Each Allow once follows the same peer owner’s exact modal and offered option. No grant is upgraded to session-wide. Accepted as an explicit captured permission-contract difference; every answer remains required in replay.

### interactiveTurn_backgroundTask_countsInActionsMenuAndPanel

The new background-task-running-ui-checked checkpoint is emitted by the already merged test after its real menu/panel assertions at source5317. Raw checkpoint101 is present. Accepted as a stronger genuine observation, not an invented historical checkpoint.

### interactiveTurn_sendQueuedNow_reachesRunningTurn

The phone send-now includes accepted_item_id28. Raw queued fact28 is observed at126 before action127. Exact conversation, queued ID, accepted message identity and body agree. Accepted as required production attribution.

## Raw lifecycle differences

The inventory’s lifecycleEqual field describes its logical abstraction, not byte-identical raw lifecycle events. Nine cases invert a peer’s close and inbound_end suffix. Every checked suffix has no intervening own-owner application frame. Both events remain present on the same owner, so this is accepted as genuine asynchronous teardown order. The per-case table names these cases.

OfflineRetry changes four failed dial closures from1000 to4404 with the relay’s no-server reason. This is explicitly accepted as the genuine absent-daemon boundary while the source deliberately stops the daemon. The source still requires old-repository retirement, actual capped backoff, an explicit Retry beating passive redial, same-host reconnect and a real reply. The distinct failure details remain in the fixture. Other independent-owner interleavings do not move an action onto another owner.

## Per-case decisions

Every row accepts the inspected production-route and genuine data differences as contract-consistent only. A failed replay remains held. Query counts below are raw phone requests, not normalised counts. Each named path has sibling `.capture.json`, `.comparison-private.json` and retained evidence or failed-replay receipts.

| Case | Raw phone opens / queries / resets / reads | Explicit contract decision | Raw lifecycle | Existing fresh replay | Evidence |
|---|---|---|---|---|---|
| interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart | 2 / 7 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-01/interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart.json` SHA `9ceb3dabc64d` |
| interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost | 2 / 7 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-01/interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost.json` SHA `763c22b0f822` |
| interactiveTurn_peerStartedTurn_continuesOnPhone | 1 / 5 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-01/interactiveTurn_peerStartedTurn_continuesOnPhone.json` SHA `d1ddae12af09` |
| interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation | 2 / 10 / 1 / 1 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-01/interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation.json` SHA `824698ba4a98` |
| interactiveTurn_pingPrompt_statusSheetShowsRunningModel | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-01/interactiveTurn_pingPrompt_statusSheetShowsRunningModel.json` SHA `1dcacc4d3c92` |
| interactiveTurn_pingPrompt_streamsPingReplyIntoThread | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-01/interactiveTurn_pingPrompt_streamsPingReplyIntoThread.json` SHA `44b2c7799c8f` |
| interactiveTurn_reconnect_slashCommandsAndCompactStillWork | 2 / 6 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-01/interactiveTurn_reconnect_slashCommandsAndCompactStillWork.json` SHA `053f7e6bb836` |
| interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage | 1 / 5 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-01/interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage.json` SHA `d45ff07454b3` |
| interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn | 2 / 5 / 0 / 1 | Explicit producer/observation difference above | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-01/interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn.json` SHA `fe19b104a86a` |
| interactiveTurn_stopBackgroundTask_completesThenRepliesAgain | 1 / 7 / 1 / 0 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | FAIL, exact-byte retry required | `batch-01/interactiveTurn_stopBackgroundTask_completesThenRepliesAgain.json` SHA `6e6df67df784` |
| interactiveTurn_chosenEffort_appliesFromTheFirstTurn | 1 / 5 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-02/interactiveTurn_chosenEffort_appliesFromTheFirstTurn.json` SHA `9f8fb79ffebc` |
| interactiveTurn_modelChange_roundTripsAndStaysPerConversation | 1 / 6 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-02/interactiveTurn_modelChange_roundTripsAndStaysPerConversation.json` SHA `e0266388cd83` |
| interactiveTurn_peerQueue_staysConsistentAcrossClients | 1 / 7 / 1 / 0 | Exact canonical queue attribution | Per-owner lifecycle unchanged | FAIL, exact-byte retry required | `batch-02/interactiveTurn_peerQueue_staysConsistentAcrossClients.json` SHA `a6b7de2a59e4` |
| interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive | 2 / 9 / 1 / 2 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | FAIL, exact-byte retry required | `batch-02/interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive.json` SHA `f792a81395e5` |
| interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel | 2 / 10 / 0 / 2 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-02/interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel.json` SHA `74917b6cef80` |
| interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain | 1 / 6 / 1 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | FAIL, exact-byte retry required | `batch-02/interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain.json` SHA `436d696ddd56` |
| interactiveTurn_archiveRestore_roundTripsListMembership | 1 / 3 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_archiveRestore_roundTripsListMembership.json` SHA `c69289a517f4` |
| interactiveTurn_archiveTwoChats_listsSecondArchivedFirst | 1 / 4 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_archiveTwoChats_listsSecondArchivedFirst.json` SHA `582a12adadae` |
| interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes | 1 / 4 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes.json` SHA `727410dde9f0` |
| interactiveTurn_attentionDot_followsARealTurn | 2 / 6 / 0 / 1 | Explicit producer/observation difference above | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_attentionDot_followsARealTurn.json` SHA `0a41564bcadb` |
| interactiveTurn_backgroundAgentProgress_showsOnRunningCard | 1 / 2 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_backgroundAgentProgress_showsOnRunningCard.json` SHA `f6e3f3f2fd15` |
| interactiveTurn_backgroundAgent_followsBottomUntilFinished | 1 / 15 / 3 / 0 | Explicit producer/observation difference above | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_backgroundAgent_followsBottomUntilFinished.json` SHA `bd25b9e19344` |
| interactiveTurn_backgroundAgent_replyStaysUnderAgent | 1 / 10 / 2 / 0 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-03/interactiveTurn_backgroundAgent_replyStaysUnderAgent.json` SHA `e413073531a3` |
| interactiveTurn_markdownReply_rendersFormattedBody | 1 / 3 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | FAIL, exact-byte retry required | `batch-03/interactiveTurn_markdownReply_rendersFormattedBody.json` SHA `0cd8707239f0` |
| interactiveTurn_permissionPrompts_heldPerConversation | 2 / 11 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_permissionPrompts_heldPerConversation.json` SHA `8a210181b68a` |
| interactiveTurn_questionAnswer_reachesTheAskingConversation | 2 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-03/interactiveTurn_questionAnswer_reachesTheAskingConversation.json` SHA `a7ea720fc573` |
| interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect | 4 / 8 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-04/interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect.json` SHA `994404320d45` |
| interactiveTurn_backgroundTask_countsInActionsMenuAndPanel | 1 / 4 / 0 / 0 | Explicit producer/observation difference above | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_backgroundTask_countsInActionsMenuAndPanel.json` SHA `a3300df8ed16` |
| interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread | 3 / 9 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-04/interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread.json` SHA `7448127cbf6c` |
| interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn.json` SHA `d3bc5ae2ec55` |
| interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip.json` SHA `6719ca33da59` |
| interactiveTurn_deleteConversation_removesFromListAndClosesThread | 1 / 3 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_deleteConversation_removesFromListAndClosesThread.json` SHA `d9bba28635db` |
| interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts | 2 / 1 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts.json` SHA `6716b1f50f59` |
| interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes | 1 / 5 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes.json` SHA `4d218db4ebd7` |
| interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend | 1 / 2 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend.json` SHA `abe0c30efcea` |
| interactiveTurn_hostSystemPrompt_editsResetsAndCancels | 1 / 1 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-04/interactiveTurn_hostSystemPrompt_editsResetsAndCancels.json` SHA `200c648acc3c` |
| interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile | 3 / 8 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-05/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.json` SHA `d9e425235f56` |
| interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes | 2 / 6 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | FAIL, exact-byte retry required | `batch-05/interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes.json` SHA `d5f4ddb04b23` |
| interactiveTurn_listArchiveEntry_opensArchived | 1 / 1 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-05/interactiveTurn_listArchiveEntry_opensArchived.json` SHA `023a8739ab47` |
| interactiveTurn_markdownLink_opensLiveNoteInReader | 1 / 7 / 1 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-05/interactiveTurn_markdownLink_opensLiveNoteInReader.json` SHA `b489d340f23f` |
| interactiveTurn_muteChannel_roundTripsThroughTheHost | 1 / 3 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-05/interactiveTurn_muteChannel_roundTripsThroughTheHost.json` SHA `d128f20e0ee8` |
| interactiveTurn_newSession_rendersSessionBoundaryDelimiter | 1 / 6 / 0 / 2 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-05/interactiveTurn_newSession_rendersSessionBoundaryDelimiter.json` SHA `2090d44f1c67` |
| interactiveTurn_offlineRetry_reconnectsSameHostAndReplies | 2 / 5 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Four absent-daemon close codes 1000 to 4404 | Passed historical fresh replay | `batch-05/interactiveTurn_offlineRetry_reconnectsSameHostAndReplies.json` SHA `c4896642661c` |
| interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild | 2 / 8 / 1 / 1 | Production route, owner/session and query/read boundaries reviewed | Peer close/end suffix order | Passed historical fresh replay | `batch-05/interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.json` SHA `5d8169642b09` |
| interactiveTurn_otherConversationAttentionPills_waitingAndFinished | 3 / 8 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-05/interactiveTurn_otherConversationAttentionPills_waitingAndFinished.json` SHA `0d64113330a0` |
| interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload | 2 / 6 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload.json` SHA `1a80e4b7b383` |
| interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool | 1 / 4 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool.json` SHA `5a2da8a24df6` |
| interactiveTurn_pingPrompt_footerShowsContextUsage | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_pingPrompt_footerShowsContextUsage.json` SHA `4df8b01c98d3` |
| interactiveTurn_renameConversation_relabelsTopBarAndListRow | 1 / 2 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_renameConversation_relabelsTopBarAndListRow.json` SHA `efeb080463ed` |
| interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately | 1 / 4 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately.json` SHA `a3557ae9fab6` |
| interactiveTurn_saveAsChannel_promotesToChannelTier | 1 / 2 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_saveAsChannel_promotesToChannelTier.json` SHA `3ec155c1b3f9` |
| interactiveTurn_scannerConfirm_waitsForHostThenOpensList | 2 / 1 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_scannerConfirm_waitsForHostThenOpensList.json` SHA `c59b03b89709` |
| interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched | 2 / 2 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched.json` SHA `c1485d79d2be` |
| interactiveTurn_sendQueuedNow_reachesRunningTurn | 1 / 7 / 1 / 1 | Exact canonical queue attribution | Peer close/end suffix order | Passed historical fresh replay | `batch-06/interactiveTurn_sendQueuedNow_reachesRunningTurn.json` SHA `62a19d53a65d` |
| interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes | 1 / 4 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-06/interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes.json` SHA `bbb6c76f6fc9` |
| interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn | 1 / 2 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn.json` SHA `965e5aa7cf71` |
| interactiveTurn_toolPrompt_rendersToolStepInThread | 1 / 2 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/interactiveTurn_toolPrompt_rendersToolStepInThread.json` SHA `32a01a04adc7` |
| interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy.json` SHA `bfd014791761` |
| interactiveTurn_twoHostsArchive_staysPerHost | 2 / 4 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/interactiveTurn_twoHostsArchive_staysPerHost.json` SHA `a08aec0cb268` |
| interactiveTurn_twoHostsCollidingConversationId_stayPerHost | 6 / 12 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/interactiveTurn_twoHostsCollidingConversationId_stayPerHost.json` SHA `9340d313afa6` |
| negativeControl_toolClaudeNeverUses_isNeverDisplayed | 1 / 4 / 0 / 1 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/negativeControl_toolClaudeNeverUses_isNeverDisplayed.json` SHA `552e0b38aa58` |
| negativeControl_wordClaudeNeverSays_isNeverDisplayed | 1 / 3 / 0 / 0 | Production route, owner/session and query/read boundaries reviewed | Per-owner lifecycle unchanged | Passed historical fresh replay | `batch-07/negativeControl_wordClaudeNeverSays_isNeverDisplayed.json` SHA `5dd0f56910e2` |

## Remaining acceptance blockers

The six historical fresh replay failures are retained separately:

- interactiveTurn_stopBackgroundTask_completesThenRepliesAgain
- interactiveTurn_peerQueue_staysConsistentAcrossClients
- interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive
- interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain
- interactiveTurn_markdownReply_rendersFormattedBody
- interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes

The five native failures are excluded from this62-fixture review and need fresh genuine successful captures:

- interactiveTurn_createEditArchiveChannel_readsPromptBack
- interactiveTurn_longRunningTool_statusAreaShowsElapsed
- interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect
- interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk
- interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn

Every one of the62 strict comparisons remains changed. No successful strict-compatibility receipt is claimed. These decisions permit later reviewed replacement only after the remaining execution and complete final acceptance obligations succeed. The review does not authorise promotion. No new raw route/ownership blocker was found in the62 successful captures. UI visibility, elapsed time, current app behaviour and physical paging remain execution obligations.

## Exact provenance and receipt hashes

Every one of the62 native capture receipts matches the following common producer and installed APK hashes. Capture timestamps are listed per case. Hashes cover unchanged raw fixture and receipt bytes. Older committed fixture hashes identify the comparison baseline read from the source checkout.

```json
{
  "source": {
    "mobileRevision": "39bf38b300320eb34eb2ac779d11ad2c4b37957f",
    "daemonRevision": "e596971abb101d44d770dea33b4d29d61300e85e",
    "daemonBinarySha256": "646cb732b5cc303d3fc7ea3fe83d5dfc07a567f512fc1c23f003f2a5b4581ad5",
    "claudeVersion": "2.1.280 (Claude Code)",
    "presentation": "current-production",
    "androidApi": "33",
    "appApkSha256": "391a50f3d8048ed22b24b5fba9d8eb31cfeb0a51626046275c7a32cb2af7930e",
    "testApkSha256": "1125308ebd67745c47875e1261d2b085807c531384f7e452ff33fa9eddd5ef43"
  },
  "rows": [
    {
      "method": "interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart",
      "capturedAt": "2026-10-20T07:25:33Z",
      "hashes": {
        "batch-01/interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart.json": "9ceb3dabc64d8da922201db16e795a7bf35e3a87e8b21dd23339aefd05943bb6",
        "batch-01/interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart.capture.json": "70bdbbb3501f1ac6d1419f064e0f031e942c5557e1bb4e4450dbcd0e186212f2",
        "batch-01/interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart.comparison-private.json": "975cae2e647abde3ef9ff811dd7e9aba3006163f12d6a59eac791029b5f04c03",
        "batch-01/interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart.evidence.json": "9e7ce8ed8a725523dca58e9975bd6cd81ce9d2cfe07f66c9247f0f0ad86c1ebd"
      },
      "oldCommittedFixtureSha256": "7d9144175a3967dc1072628c36e34ba114406f29cbc46b91bb449dbc36e2d5d6"
    },
    {
      "method": "interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost",
      "capturedAt": "2026-10-20T07:25:58Z",
      "hashes": {
        "batch-01/interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost.json": "763c22b0f8228746e77216a1998a60e7f5b4e2e9508a44d588d3d7273ba72bab",
        "batch-01/interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost.capture.json": "92441225cf1dfb37f5565d28d7acf039afae9b778370f0462f63f0587c82b3c6",
        "batch-01/interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost.comparison-private.json": "f390d27c51a134b92bbcc91e1d62090f34013795da9248c6a10e7f8372e56de7",
        "batch-01/interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost.evidence.json": "9371aad61b88023958d2da0dd64ff781163dfe4cbcb960bcbb11b67ff70a8ad5"
      },
      "oldCommittedFixtureSha256": "64b2514f2cfd2cc61f50469d5f252155f84ee49493bedd4be72156fafd339db4"
    },
    {
      "method": "interactiveTurn_peerStartedTurn_continuesOnPhone",
      "capturedAt": "2026-10-20T07:27:00Z",
      "hashes": {
        "batch-01/interactiveTurn_peerStartedTurn_continuesOnPhone.json": "d1ddae12af090ab0f2170f0770d5a5ddcd479aa50975e47f98c582b5945ad7df",
        "batch-01/interactiveTurn_peerStartedTurn_continuesOnPhone.capture.json": "683d8649a49cf33d6a28e32f29de526796962cb410ce86f6209492032f75723e",
        "batch-01/interactiveTurn_peerStartedTurn_continuesOnPhone.comparison-private.json": "0a24f51718eaaa091305d2c7f7bde1f73068e845b0b2d6509c55d236d292136d",
        "batch-01/interactiveTurn_peerStartedTurn_continuesOnPhone.evidence.json": "197de2d469afb674bd0133ffb36b15cfb4f8b50088ac555693e7dcfc67ff9da7"
      },
      "oldCommittedFixtureSha256": "23f2ebe9a48adc1ca95fb9d1859e8182318ffd857b438b3038f32f165848b7e3"
    },
    {
      "method": "interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation",
      "capturedAt": "2026-10-20T07:30:16Z",
      "hashes": {
        "batch-01/interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation.json": "824698ba4a98ded06b030b777d56b15b0c19549a164298136c62d7c78eadd7c0",
        "batch-01/interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation.capture.json": "d1486d5cd8017fd2c4c7fe5707d38ca08af45808d147d03cc4db91f655c3035a",
        "batch-01/interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation.comparison-private.json": "d32e3848398f6faa1c3bfdad446b1c7fc31f7886ce36674482e6978870968a7e",
        "batch-01/interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation.evidence.json": "9fc6374ce8a768c8d7a61e0885e3e4edd2f43ecb1eecd89f7a6d04dfe96de4d5"
      },
      "oldCommittedFixtureSha256": "96ae0291634a5dcd9fb87da35f533710b464b7c070a8c46eb3449e0e6c509225"
    },
    {
      "method": "interactiveTurn_pingPrompt_statusSheetShowsRunningModel",
      "capturedAt": "2026-10-20T07:29:23Z",
      "hashes": {
        "batch-01/interactiveTurn_pingPrompt_statusSheetShowsRunningModel.json": "1dcacc4d3c921cee324dc71531cf39bf3c4b91c149343470d0f3be0c9e52d808",
        "batch-01/interactiveTurn_pingPrompt_statusSheetShowsRunningModel.capture.json": "23d24a79ae648efe5d3a484b3734730ada14a28e2d38d8710cf43470a5f6f9ce",
        "batch-01/interactiveTurn_pingPrompt_statusSheetShowsRunningModel.comparison-private.json": "5f5ad2f07ceae2d4408124fb22aaecb4bc16251441816bef6e66ee0bb9626113",
        "batch-01/interactiveTurn_pingPrompt_statusSheetShowsRunningModel.evidence.json": "bb43dab8134c67e3dacacb30fb70f94260a4cc8ab6a9001ee4b7dbf2976a6f3e"
      },
      "oldCommittedFixtureSha256": "51c24a8d4b7fa7ca470806ed3e1ff2781e44af23e7f0ade1362e9f378ba4d33f"
    },
    {
      "method": "interactiveTurn_pingPrompt_streamsPingReplyIntoThread",
      "capturedAt": "2026-10-20T07:26:39Z",
      "hashes": {
        "batch-01/interactiveTurn_pingPrompt_streamsPingReplyIntoThread.json": "44b2c7799c8f4ed910fc0dd565a5a4316e34ebeb3ab1ec0b6653fceaf5f92f62",
        "batch-01/interactiveTurn_pingPrompt_streamsPingReplyIntoThread.capture.json": "3ceb3fb82172ec4aeea3c9670a79a0716be1fa26b198a121e9c291725521732b",
        "batch-01/interactiveTurn_pingPrompt_streamsPingReplyIntoThread.comparison-private.json": "3627d353e865543b2e9e262acc0166c22b4550ce07c90e526f863b058b1c060a",
        "batch-01/interactiveTurn_pingPrompt_streamsPingReplyIntoThread.evidence.json": "304ee21bf9ac7a8dc0de4bc6ec065f350528874c3a8e1727217b9de37abd2b56"
      },
      "oldCommittedFixtureSha256": "612743526469e70f2644a289c4d01fdc011c37b069ac78438b7a5e03b83f1b68"
    },
    {
      "method": "interactiveTurn_reconnect_slashCommandsAndCompactStillWork",
      "capturedAt": "2026-10-20T07:31:06Z",
      "hashes": {
        "batch-01/interactiveTurn_reconnect_slashCommandsAndCompactStillWork.json": "053f7e6bb836f71e35476b269b96eb3128b4cd7db48b368821a1309ffc65eeb8",
        "batch-01/interactiveTurn_reconnect_slashCommandsAndCompactStillWork.capture.json": "409227b9e1d1a2df7f98d96d87de1142e1d42f924203492957c6135e26e32690",
        "batch-01/interactiveTurn_reconnect_slashCommandsAndCompactStillWork.comparison-private.json": "895b4b9f251f7f21ced348b9ccd44f9879de4cf2df2b74f6ec54d16281de102c",
        "batch-01/interactiveTurn_reconnect_slashCommandsAndCompactStillWork.evidence.json": "3ec00c71a3a4506889425460d07b7ef2292812fcaa2634a82177b141327e5194"
      },
      "oldCommittedFixtureSha256": "f9f29c8aab66e28d9a24cbbeabdab9323458a63fd952dc0bfdfd560c86740ca5"
    },
    {
      "method": "interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage",
      "capturedAt": "2026-10-20T07:26:18Z",
      "hashes": {
        "batch-01/interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage.json": "d45ff07454b30956c0afaaf2a5c40b61dca47801ceac12bdbb403714ddd5fcc3",
        "batch-01/interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage.capture.json": "d249cf4eba76d5b87c230a317c0b020ac91c58eb248ccd928cf4b5d3a69adf72",
        "batch-01/interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage.comparison-private.json": "6e2c53df98ec39a022bc8fa0ec0a8ee74771c2c036f2a47eeb5d1bf6534e3098",
        "batch-01/interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage.evidence.json": "5328fb5e3963661152c9af767827246fe720925ec1b00343242222fe32ecede6"
      },
      "oldCommittedFixtureSha256": "9717424b8d2639717b41709bac8ac5e38d4efa0762acfb3474f85cb08944accf"
    },
    {
      "method": "interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn",
      "capturedAt": "2026-10-20T07:29:41Z",
      "hashes": {
        "batch-01/interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn.json": "fe19b104a86aae11681b75bb6e4486e12454a64b47017e89539a3694a7c16474",
        "batch-01/interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn.capture.json": "4faa7a1e9a62f069a358732ea79a09c8b15b8674a8cb0f8d2beb609fee0b240e",
        "batch-01/interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn.comparison-private.json": "c7fa07f9a97f3fb2d991a7b95afb4b9fd9c8ddcf05036b6eb46b1a6b4ae8d20e",
        "batch-01/interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn.evidence.json": "4e6c45c05d59444a0dce9fffebf12d88e435581695232f19ff62ad1aa826ebc3"
      },
      "oldCommittedFixtureSha256": "6b6538a6576acb671685462c385ef9b7c5f9b933babb87a3cab5b76656b8f414"
    },
    {
      "method": "interactiveTurn_stopBackgroundTask_completesThenRepliesAgain",
      "capturedAt": "2026-10-20T07:27:31Z",
      "hashes": {
        "batch-01/interactiveTurn_stopBackgroundTask_completesThenRepliesAgain.json": "6e6df67df784cebbd457303910e19122ff3880c8429d9e904bf9a3e79e964a08",
        "batch-01/interactiveTurn_stopBackgroundTask_completesThenRepliesAgain.capture.json": "062e8ae5624260ec01c74946ba24af055c1920200c134c795f25e45e46fa2cfe",
        "batch-01/interactiveTurn_stopBackgroundTask_completesThenRepliesAgain.comparison-private.json": "b590b22cf90b49ec00075ddee4a2a3c4048b8ebcf5b6e693ca082d54a47dbd74",
        "batch-01/interactiveTurn_stopBackgroundTask_completesThenRepliesAgain.failed-replay.json": "06f1756a008cbb75c733dad2e4ad2fc78adcdcfc5c6def31fbf47bd16089c2a8"
      },
      "oldCommittedFixtureSha256": "31098e432c0d149a386facc4e788178c1bc257a7c25f082bc3b3a7c70106da87"
    },
    {
      "method": "interactiveTurn_chosenEffort_appliesFromTheFirstTurn",
      "capturedAt": "2026-10-20T07:34:49Z",
      "hashes": {
        "batch-02/interactiveTurn_chosenEffort_appliesFromTheFirstTurn.json": "9f8fb79ffebce725d703e895e6dd0328fb7e0c331836df6285987d1ff01b05b8",
        "batch-02/interactiveTurn_chosenEffort_appliesFromTheFirstTurn.capture.json": "74d31b795c8e9cf6c17c3fb40dc80de4d8b3f12f6f011d1343a6a2f2c84617b2",
        "batch-02/interactiveTurn_chosenEffort_appliesFromTheFirstTurn.comparison-private.json": "a996e1ebe60f96c03af02890dc3f56d2abbd64f6182d73683336adcf33ef9fdc",
        "batch-02/interactiveTurn_chosenEffort_appliesFromTheFirstTurn.evidence.json": "2af79c100331a7f8429218ca90792037a774a21085d2dba4ab6f8aa53204aa94"
      },
      "oldCommittedFixtureSha256": "e91ee50bc0a711d1ab4d6b95e9fbffd6c9dacef4525b1ab5fe784af2fe02262b"
    },
    {
      "method": "interactiveTurn_modelChange_roundTripsAndStaysPerConversation",
      "capturedAt": "2026-10-20T07:37:49Z",
      "hashes": {
        "batch-02/interactiveTurn_modelChange_roundTripsAndStaysPerConversation.json": "e0266388cd83ee404c4147751b1168c44521e433ea1c87c863681f6e26b9a6e3",
        "batch-02/interactiveTurn_modelChange_roundTripsAndStaysPerConversation.capture.json": "9a9248e197e0ab1effcd45a33ae93faebe8f7a80f4cc08619202770ba5907dea",
        "batch-02/interactiveTurn_modelChange_roundTripsAndStaysPerConversation.comparison-private.json": "19e90c5fde03d4ad8c71fd31ab4257ffb20d58ab36a1d76af7ae259e60a0d49e",
        "batch-02/interactiveTurn_modelChange_roundTripsAndStaysPerConversation.evidence.json": "be2911ef112a7c7048fc6a6904611548ee9093012ae007ed6fcc54a25a158464"
      },
      "oldCommittedFixtureSha256": "6feab68a493fe73ed9030cd4e7c182bb4cd0eb8b07e69a9794547184b1d8394c"
    },
    {
      "method": "interactiveTurn_peerQueue_staysConsistentAcrossClients",
      "capturedAt": "2026-10-20T07:39:33Z",
      "hashes": {
        "batch-02/interactiveTurn_peerQueue_staysConsistentAcrossClients.json": "a6b7de2a59e45b43743c77804f89566ab4dac2d32377d5c0affc7e95b19e3260",
        "batch-02/interactiveTurn_peerQueue_staysConsistentAcrossClients.capture.json": "a18f397aad66d5b71ac67fa6884b8ec92c3c47abe4393ae6e86bc8c942eed482",
        "batch-02/interactiveTurn_peerQueue_staysConsistentAcrossClients.comparison-private.json": "e31110f6c8ba5eb52e3b056fccfe79189a8306a3a7d1da1d70e9c8b7f86c1318",
        "batch-02/interactiveTurn_peerQueue_staysConsistentAcrossClients.failed-replay.json": "4ee0b11e4597c4c4c33e4bd55e8aabcf26a5c42669fc1d8b03f2fae021299d24"
      },
      "oldCommittedFixtureSha256": "100c092a0024301031a9a8daff2cc4b49fd5110d2652e39a39c29e37155af651"
    },
    {
      "method": "interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive",
      "capturedAt": "2026-10-20T07:40:57Z",
      "hashes": {
        "batch-02/interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive.json": "f792a81395e5ffaa4bde441a724682a5b57b47fcb7e10caa55b78608ff95c4ce",
        "batch-02/interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive.capture.json": "ba2ab8db587ca343225f1954ab8de68819825213dda69a28ebbd2ba3818ebbd7",
        "batch-02/interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive.comparison-private.json": "68f946f76eb237662f372fee59a6796a32913f4bb572ec8e69c2eb1167941d2e",
        "batch-02/interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive.failed-replay.json": "185ef24856628a961697c1ef8066e4a119749ced8d0a201d1d6e3bf47f0beb1e"
      },
      "oldCommittedFixtureSha256": "65d0728dc12684443824e85ace1314d985e4c4314b7d45db471247da020ce2b2"
    },
    {
      "method": "interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel",
      "capturedAt": "2026-10-20T07:42:27Z",
      "hashes": {
        "batch-02/interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel.json": "74917b6cef8039f3df13e9174b20159577979c5143eae58fc0a1c0b806ca1669",
        "batch-02/interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel.capture.json": "5d3543b8140a3e41f4164d18b59633dbf3700f064fe6ddbdb01210145a88981a",
        "batch-02/interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel.comparison-private.json": "666f6dd0518a6f6e4fe1d64b1906109e9b481ee316e843f4913b3ea13eb4400c",
        "batch-02/interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel.evidence.json": "15a3adbbae9baffc67b2bfbd85ce22473a599e7cf94e651b8f636ac775e0af32"
      },
      "oldCommittedFixtureSha256": "d2f6d982c0fb66aa95ad5df556226d0e76dfee367f009d6a39be70bd7223837e"
    },
    {
      "method": "interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain",
      "capturedAt": "2026-10-20T07:42:57Z",
      "hashes": {
        "batch-02/interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain.json": "436d696ddd56363ba40b33e408f0eee0a4114c735ac98d69061b3f461eb16006",
        "batch-02/interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain.capture.json": "75953ae73fbd6a0385f55e2d9132058150c27591312d316aba366a66e606a2f2",
        "batch-02/interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain.comparison-private.json": "983a4c2a54c0ad65cb07d813ada0053a96b40974f32361770b0fa80789eb2e2b",
        "batch-02/interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain.failed-replay.json": "4adb670ac0638a6ac6a2260f6f63f5bc7a8cd2d853a95c636bfb11aa96e6225b"
      },
      "oldCommittedFixtureSha256": "9e45f0f389a1ec1667065a562aa09c90c934b67b1647b5b30e21579d5ea59490"
    },
    {
      "method": "interactiveTurn_archiveRestore_roundTripsListMembership",
      "capturedAt": "2026-10-20T07:46:28Z",
      "hashes": {
        "batch-03/interactiveTurn_archiveRestore_roundTripsListMembership.json": "c69289a517f49d072a580777b40c20bd4fc98f2617bbf4160b8b9552de577918",
        "batch-03/interactiveTurn_archiveRestore_roundTripsListMembership.capture.json": "a08bc7f0d678b248191451357d825ca9647286e09bcf71a3164f8241a0a5bc45",
        "batch-03/interactiveTurn_archiveRestore_roundTripsListMembership.comparison-private.json": "fa26233e35343085434ecb3a8ce3d7a294c759efab3a4fc94951dc8f1818dd9d",
        "batch-03/interactiveTurn_archiveRestore_roundTripsListMembership.evidence.json": "db5618beee4f1f496fd1056ae3a37290332547bd23ebc05e1714eb619f8b24d2"
      },
      "oldCommittedFixtureSha256": "058fdbb02e8d8fd88956bad4ed3d940918646e4b76a3b197f297dc8dd8d74593"
    },
    {
      "method": "interactiveTurn_archiveTwoChats_listsSecondArchivedFirst",
      "capturedAt": "2026-10-20T07:46:43Z",
      "hashes": {
        "batch-03/interactiveTurn_archiveTwoChats_listsSecondArchivedFirst.json": "582a12adadaeddba187eedbe9d729aea8c614cd833c9c1f36f1e59227eea2e0a",
        "batch-03/interactiveTurn_archiveTwoChats_listsSecondArchivedFirst.capture.json": "3865e71a65b989ac3b6e2b22097daf63461dcb9336825bb3dc7472195fec26cb",
        "batch-03/interactiveTurn_archiveTwoChats_listsSecondArchivedFirst.comparison-private.json": "d5cf60f140694e79a0dbf9c94703a43461f99af1d03cffd1be36bfe2b0778d9d",
        "batch-03/interactiveTurn_archiveTwoChats_listsSecondArchivedFirst.evidence.json": "68ab5f258d47ff167c8e368ab7c797ab8fe9a41984849aa6f9f06be3f001d5d1"
      },
      "oldCommittedFixtureSha256": "013a0a425fa6b4611178dae7424693fb428e9eedd7ce2a7c9a63363d334f855d"
    },
    {
      "method": "interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes",
      "capturedAt": "2026-10-20T07:47:02Z",
      "hashes": {
        "batch-03/interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes.json": "727410dde9f02ef82cc3929b63f24a3cae6db30a69dc43595e961a55953fdc43",
        "batch-03/interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes.capture.json": "22aca2fe9fbef82c3415b7c14c9c399de9a7eece272a0a24bb7c12e876239fc7",
        "batch-03/interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes.comparison-private.json": "cb4fc804d053d2dba27fba94f0b3d329a3c48d3e0cfd8b7ac72af943973c2d04",
        "batch-03/interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes.evidence.json": "398537e836d2357b9a4a4d2acff7742371f97d78ede6a158c8fb95e1d9ad99a3"
      },
      "oldCommittedFixtureSha256": "502ba5d36dc82d2d2a01b9bf1bc7c085508915a44b7a950f5c640e144c6ff135"
    },
    {
      "method": "interactiveTurn_attentionDot_followsARealTurn",
      "capturedAt": "2026-10-20T07:47:29Z",
      "hashes": {
        "batch-03/interactiveTurn_attentionDot_followsARealTurn.json": "0a41564bcadb2460dc1c2bd2432e80cc9429cfc265cd5cf23bc63c5b20638d38",
        "batch-03/interactiveTurn_attentionDot_followsARealTurn.capture.json": "02d6c127af7219986cf21dd74135c4ad3c4a42103f250c0d3c0c8493ff296fb2",
        "batch-03/interactiveTurn_attentionDot_followsARealTurn.comparison-private.json": "05772956989092ad3e46804b727a2994c21117a4206bfbea2996aad162122a7b",
        "batch-03/interactiveTurn_attentionDot_followsARealTurn.evidence.json": "409c9ec1709ff85a58dc0923945275630db9bb41bc103be1a3e0efc078b88c8f"
      },
      "oldCommittedFixtureSha256": "33ab1d26db69bc39ffaf3f9495224b7a4d9497375a613d1c1c97a60af825464f"
    },
    {
      "method": "interactiveTurn_backgroundAgentProgress_showsOnRunningCard",
      "capturedAt": "2026-10-20T07:47:57Z",
      "hashes": {
        "batch-03/interactiveTurn_backgroundAgentProgress_showsOnRunningCard.json": "f6e3f3f2fd15064d8ace05d998d6f8d2c4f71de3314f8f0c43c0d98ca2919c02",
        "batch-03/interactiveTurn_backgroundAgentProgress_showsOnRunningCard.capture.json": "b014bcd9888d314f3476198042b7e423bc5ec6956c9ba68498e29f8e8be22a36",
        "batch-03/interactiveTurn_backgroundAgentProgress_showsOnRunningCard.comparison-private.json": "b5aaa6303b11eba6a34a33cae340d1aad81c0c91ec136f267e3f03dfa6868087",
        "batch-03/interactiveTurn_backgroundAgentProgress_showsOnRunningCard.evidence.json": "6389390da527c971bd8fd501f0149e052b3fce00eb76401746a2b65e4f179a2b"
      },
      "oldCommittedFixtureSha256": "5232cbfb530d4f41bee189acaa6138c8dafabe22e66bbca47af6ed8881b9c1df"
    },
    {
      "method": "interactiveTurn_backgroundAgent_followsBottomUntilFinished",
      "capturedAt": "2026-10-20T07:48:35Z",
      "hashes": {
        "batch-03/interactiveTurn_backgroundAgent_followsBottomUntilFinished.json": "bd25b9e193443063a3a323d906e083eb2cb2b80ba016f778b17efb4072031e05",
        "batch-03/interactiveTurn_backgroundAgent_followsBottomUntilFinished.capture.json": "541ddec63126a5d908163ba8235544ade0917c65a4a183687ccb521bc30e5a18",
        "batch-03/interactiveTurn_backgroundAgent_followsBottomUntilFinished.comparison-private.json": "0691ca5a8cd1d5e447ed573972efec475f3aae91169d6a1f5225fd46b06424e0",
        "batch-03/interactiveTurn_backgroundAgent_followsBottomUntilFinished.evidence.json": "ee2a247cf21271dcfc4efe2c8a6112b244e890ea9fb702d66f34f181ab632cbd"
      },
      "oldCommittedFixtureSha256": "2b950379d8f35f4edcb5d4611544c147074dc083f880af74e50895d2551bea01"
    },
    {
      "method": "interactiveTurn_backgroundAgent_replyStaysUnderAgent",
      "capturedAt": "2026-10-20T07:49:19Z",
      "hashes": {
        "batch-03/interactiveTurn_backgroundAgent_replyStaysUnderAgent.json": "e413073531a342aff71978fc05256df5f8c6ed5bc1285c67fa7806a25db912dd",
        "batch-03/interactiveTurn_backgroundAgent_replyStaysUnderAgent.capture.json": "1d7301110cf229ee33ab8743d0985599579cd1c3e5430a1d13bef3140506d64e",
        "batch-03/interactiveTurn_backgroundAgent_replyStaysUnderAgent.comparison-private.json": "c953dde0f0760cc5a0077d0ebeabf423649feebc9459e8ea0d7e473ff21d2a93",
        "batch-03/interactiveTurn_backgroundAgent_replyStaysUnderAgent.evidence.json": "1d9095bf57640c254c5bf064c81fb0b22a5774c2669a7dafe6ff7fe557733702"
      },
      "oldCommittedFixtureSha256": "512312982d31442631bfcdba3101b610817363cf179a40efe85b99b8f1fe6a17"
    },
    {
      "method": "interactiveTurn_markdownReply_rendersFormattedBody",
      "capturedAt": "2026-10-20T07:45:13Z",
      "hashes": {
        "batch-03/interactiveTurn_markdownReply_rendersFormattedBody.json": "0cd8707239f03c0cef7dc18678381087a6e3c15ec277dd1c4c6658fd9ac0bfb5",
        "batch-03/interactiveTurn_markdownReply_rendersFormattedBody.capture.json": "98bef8f9843a84966ac51fea770ec2a5ced51b273ca2579405e84d660203f00a",
        "batch-03/interactiveTurn_markdownReply_rendersFormattedBody.comparison-private.json": "8c2754e73240c1fc6ee21794cb99aa5d43a22f02de459df3cdfac2640ea0b747",
        "batch-03/interactiveTurn_markdownReply_rendersFormattedBody.failed-replay.json": "a3088f8b5c9713edc72366b514f145813555cd6ca24e3692bc978c72dff3f178"
      },
      "oldCommittedFixtureSha256": "acc2a2881071b178501ab7f0cc5b50216df0b5a15863f3def8cec7bbcd993356"
    },
    {
      "method": "interactiveTurn_permissionPrompts_heldPerConversation",
      "capturedAt": "2026-10-20T07:45:41Z",
      "hashes": {
        "batch-03/interactiveTurn_permissionPrompts_heldPerConversation.json": "8a210181b68a1f238a203c7f2cd23c1fcdbdb19cb9b5af965972b1773d99ae77",
        "batch-03/interactiveTurn_permissionPrompts_heldPerConversation.capture.json": "e5737b3518e2a05db760a8762ed510672c6cf8b2db70cdcff17fd3d46d363669",
        "batch-03/interactiveTurn_permissionPrompts_heldPerConversation.comparison-private.json": "d6d42bcbf5b296515047292a02036949ffb02d4df529b14815500c53b95eb5ee",
        "batch-03/interactiveTurn_permissionPrompts_heldPerConversation.evidence.json": "8682903355f846fc97bd186e99466d9d2657b3e769735a6519161030826b606e"
      },
      "oldCommittedFixtureSha256": "54a87ca12243c1e1a8f4f6d89a2f89bfbade228d731e92ffd64ed2e72bd543a9"
    },
    {
      "method": "interactiveTurn_questionAnswer_reachesTheAskingConversation",
      "capturedAt": "2026-10-20T07:46:11Z",
      "hashes": {
        "batch-03/interactiveTurn_questionAnswer_reachesTheAskingConversation.json": "a7ea720fc573561db779a0b885aa18b6b217752171c88a5cc530132aff7272dc",
        "batch-03/interactiveTurn_questionAnswer_reachesTheAskingConversation.capture.json": "be1ac88191ef336631a31ab7152f30545b13c6e6de1530a457a477659f79dae7",
        "batch-03/interactiveTurn_questionAnswer_reachesTheAskingConversation.comparison-private.json": "ce0409cbbe9db044be2226bbccabc0472956630b8461e1d694216d1ced00f02f",
        "batch-03/interactiveTurn_questionAnswer_reachesTheAskingConversation.evidence.json": "6780e0058a2aa8ef7436ff3d87ff6b47abb604f1ffbd3d4317d45d17e287d54d"
      },
      "oldCommittedFixtureSha256": "50a8c48496d60ed8af02ceb3496953bc0a25ce072b0298d5bbfdfec401ff0425"
    },
    {
      "method": "interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect",
      "capturedAt": "2026-10-20T07:50:46Z",
      "hashes": {
        "batch-04/interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect.json": "994404320d45a5a4a5a99f8443693a485ee6626a3e7eddc8828943c4bb7b37e1",
        "batch-04/interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect.capture.json": "bdc19db55c5559b69d9daba0fe0c387a1c771e377928c70c97a9511bafe8119d",
        "batch-04/interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect.comparison-private.json": "b93564b28a6d1e0ddc84888795a03fcc887844a7640f055f12ec87fd11d34bab",
        "batch-04/interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect.evidence.json": "6f44395efefa98474d31be29d7c020aacf1c0b5928691ad14f77eb79321a080f"
      },
      "oldCommittedFixtureSha256": "a4d62755ce45ab3e75f035995e3ba6e476ffa9406efe19a87a945aa86929569d"
    },
    {
      "method": "interactiveTurn_backgroundTask_countsInActionsMenuAndPanel",
      "capturedAt": "2026-10-20T07:52:21Z",
      "hashes": {
        "batch-04/interactiveTurn_backgroundTask_countsInActionsMenuAndPanel.json": "a3300df8ed166710c01909857c589688f5d0c85c5ad28846075b395f7d91533d",
        "batch-04/interactiveTurn_backgroundTask_countsInActionsMenuAndPanel.capture.json": "731aa6f8d03723df4fc0e321d5a6f99b622ec3218eadc783e36f0a7e72ebc04d",
        "batch-04/interactiveTurn_backgroundTask_countsInActionsMenuAndPanel.comparison-private.json": "4a6af8b0d44e9c407187c0f78e0d6d1b2ba781b466f8792e731fcd3bb86a70f6",
        "batch-04/interactiveTurn_backgroundTask_countsInActionsMenuAndPanel.evidence.json": "0961424d5e013492e12ef5f4c3c136a016f8fc81e6834c46c2cfc6958a85fa04"
      },
      "oldCommittedFixtureSha256": "f36d41bacefe3ff83ff399da02aac1dd4b401ee32e510c0f99c6936d6d9baf0c"
    },
    {
      "method": "interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread",
      "capturedAt": "2026-10-20T07:53:14Z",
      "hashes": {
        "batch-04/interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread.json": "7448127cbf6c3afb212583cfc482d2b56deeb2315fe674432866dd53626526a2",
        "batch-04/interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread.capture.json": "7e7095f13729a129592352a7e1a629f1697c111e08ae3bd587bd674dcbb47d2c",
        "batch-04/interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread.comparison-private.json": "b823e91f9f4af1f1098c14b8b23bf2416a645b9b836ccbd285816ea780ffa27d",
        "batch-04/interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread.evidence.json": "e48ab758961ee0e08874d726fcbeffd648ac34f5a24e2233a27456fb06ee2409"
      },
      "oldCommittedFixtureSha256": "1eee90b1bfc6ff635e8f4a0b84288444240d429e713dac0d3d5b9eeb7de3a523"
    },
    {
      "method": "interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn",
      "capturedAt": "2026-10-20T07:54:03Z",
      "hashes": {
        "batch-04/interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn.json": "d3bc5ae2ec55edefbe650f10c01b7297d5544d4df5f15a3c4f203be1bc55b52c",
        "batch-04/interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn.capture.json": "b5faf1a9ac2072a6fa905f9a885b95e546c30a33c775d07cfc38b6589ea2b619",
        "batch-04/interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn.comparison-private.json": "5f5ad2f07ceae2d4408124fb22aaecb4bc16251441816bef6e66ee0bb9626113",
        "batch-04/interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn.evidence.json": "2751dbc7fbcc3fe58ba6bdb6aa43fa342ad943bc8c7a643df0982ba6e03e19ba"
      },
      "oldCommittedFixtureSha256": "c64b397326c8734d5d8988818151eadc7d1dd9d707d57ee129ac26eb8e687f37"
    },
    {
      "method": "interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip",
      "capturedAt": "2026-10-20T07:54:50Z",
      "hashes": {
        "batch-04/interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip.json": "6719ca33da59a44300b726ef3161055a852738f038ad19a8bd0d583abc4fd774",
        "batch-04/interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip.capture.json": "1e83ff46c7bc95b44edd4aecd99271b980d821b1f00ee890ce4e1980f55699c7",
        "batch-04/interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip.comparison-private.json": "8e6b93c173bdbabf74426cde41e2bbbc73195715f3c7f48fc747c5b4514a09fa",
        "batch-04/interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip.evidence.json": "ae7c7afad88543d7237862b2e6ce6634f0311d95c92f04b9cb2de2a79f172bf6"
      },
      "oldCommittedFixtureSha256": "e18490a27d4ea20d7c1cca3f6fed2e27bdab443f0596ee35654c53168146a869"
    },
    {
      "method": "interactiveTurn_deleteConversation_removesFromListAndClosesThread",
      "capturedAt": "2026-10-20T07:55:08Z",
      "hashes": {
        "batch-04/interactiveTurn_deleteConversation_removesFromListAndClosesThread.json": "d9bba28635db46c93d96858e22c96044b9fef2bb20d8ee35ac145964345a80fd",
        "batch-04/interactiveTurn_deleteConversation_removesFromListAndClosesThread.capture.json": "5ee4f31159484a67f1ccbfea6072a7c483f3fc87dcc79745bebca175db8c2268",
        "batch-04/interactiveTurn_deleteConversation_removesFromListAndClosesThread.comparison-private.json": "52243f2b4bfa8ed9bb5f9b36d9d497b01668240f2b75e28f27feb7fd6f1cbef3",
        "batch-04/interactiveTurn_deleteConversation_removesFromListAndClosesThread.evidence.json": "9aeb5fb435a4066d51b940cca89638ac7ddc57ee5a57ac111bf7b3937571e38a"
      },
      "oldCommittedFixtureSha256": "0dba0fab7a64a5a9308e90af231f4e0aeb0159d8e15e62a33900e68de547ad07"
    },
    {
      "method": "interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts",
      "capturedAt": "2026-10-20T07:55:22Z",
      "hashes": {
        "batch-04/interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts.json": "6716b1f50f59e1bc15025f3a163e1276f345ad892522354baebfacbe8b63c505",
        "batch-04/interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts.capture.json": "798d0de00a7e02ce3195df1c1815899abb985016f790e96ed40d28c1df60bbe2",
        "batch-04/interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts.comparison-private.json": "834a57c77df877637c2c1a78b413b04fd02614a556d617dc007f36682a6e351a",
        "batch-04/interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts.evidence.json": "d0118fb8c0d9255152f8fd3a768f21d658c4bc28b171f44bf41ac23ce65fbee5"
      },
      "oldCommittedFixtureSha256": "7b8b4a5ca714ba151e77240d05108fd943ad1b2ccea65e2e78bb610e27ba3a28"
    },
    {
      "method": "interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes",
      "capturedAt": "2026-10-20T07:55:39Z",
      "hashes": {
        "batch-04/interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes.json": "4d218db4ebd712663c7bb185fee4affe50406766964261ed356a1bc2ecde628f",
        "batch-04/interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes.capture.json": "dd6c8deafebc6bb28527115c723ddf2536e62e20a845b47c5dad2b879d58dc30",
        "batch-04/interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes.comparison-private.json": "ccefb1e51d6de606bbbd6cefe0e38bc1845cd00a63e2620f9e3c698072ad3502",
        "batch-04/interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes.evidence.json": "ed6643e25b51fce37468af8da9964ff2f7442bda0a2ae3dc8a266ef7434bb0c2"
      },
      "oldCommittedFixtureSha256": "2b409f8f8bd246233971c7148c997a3265c125c4dbdb397e73b6d2d8fb791165"
    },
    {
      "method": "interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend",
      "capturedAt": "2026-10-20T07:55:52Z",
      "hashes": {
        "batch-04/interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend.json": "abe0c30efcea7c36232db7b4f5ecd2b42bd971fb5a7e18f7608f4693bd23d1a2",
        "batch-04/interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend.capture.json": "a055806e728c8f810dcc54f1af7028505daecd9b0ad7fa52c49fa07ca9e2472d",
        "batch-04/interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend.comparison-private.json": "2acf3c5874586bafd6f9e16eaab504cc9ddc2decbdb6398417589173e0aeb392",
        "batch-04/interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend.evidence.json": "45532b826ab18ed631d015a4c128ef4b2154e88e87d9984c1170d47c97b9b88d"
      },
      "oldCommittedFixtureSha256": "2fd9df43785698b7053f030eef39f83db47881cff44f45326f796d8fc5a2ac46"
    },
    {
      "method": "interactiveTurn_hostSystemPrompt_editsResetsAndCancels",
      "capturedAt": "2026-10-20T07:56:05Z",
      "hashes": {
        "batch-04/interactiveTurn_hostSystemPrompt_editsResetsAndCancels.json": "200c648acc3cfe15d1302a095c31f827a3c850b74c74d1c39ef1397e74647560",
        "batch-04/interactiveTurn_hostSystemPrompt_editsResetsAndCancels.capture.json": "bcb56cfa577d82f5ea515890a274b2cdab99c9d5b507f9d8fa00744262859466",
        "batch-04/interactiveTurn_hostSystemPrompt_editsResetsAndCancels.comparison-private.json": "53852c8e877642ede2f656d1b35d916a3930e7e3f08dd97fd3ee94446935f746",
        "batch-04/interactiveTurn_hostSystemPrompt_editsResetsAndCancels.evidence.json": "f8b77a4abf66081a0a6b34aa2fed2d90339e20b63a8f2bbb368c210b1c8e3e55"
      },
      "oldCommittedFixtureSha256": "0f468b3c5f45469bec69d257d65b3de23b77f8cf235d466e3813dd14e0efee23"
    },
    {
      "method": "interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile",
      "capturedAt": "2026-10-20T07:57:08Z",
      "hashes": {
        "batch-05/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.json": "d9e425235f5654f6cf17ca18b3d8ef918abe6bcf6a8c465e6ee4df47ef937ab5",
        "batch-05/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.capture.json": "d088639b586cee49aed91427b4b9bd519036ac1f9e0663eb56d7ca554eb17b41",
        "batch-05/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.comparison-private.json": "4a75cf161bdd95ec3bbb9a03f27af26e671ab90beabd4def612133f8ebe7c80c",
        "batch-05/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.evidence.json": "fe440bcb112d8c48368e9f1073efae47a899ea5ca7682cad224d561d4a2a7dbe"
      },
      "oldCommittedFixtureSha256": "665118409da012222d66a0c163b3aec635f413292f8dbfcfe36109094aaa78fc"
    },
    {
      "method": "interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes",
      "capturedAt": "2026-10-20T07:57:26Z",
      "hashes": {
        "batch-05/interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes.json": "d5f4ddb04b23017d9ec44cb95190e128e89d01118feb1a09ce518d2b02329e31",
        "batch-05/interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes.capture.json": "63dc05c34478b7be9f2a2a3985e77794710937fc47204a693a7fe0c25c3a199e",
        "batch-05/interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes.comparison-private.json": "95e30bdd13e9ed9fdb73d560efe90aac821e2ca1ca4045f4fd69e059d2377827",
        "batch-05/interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes.failed-replay.json": "e5f901ae028c3d176f23dd65d35ad413846f458f8ac073d44801d6246a3a4646"
      },
      "oldCommittedFixtureSha256": "79f21174d8b92fcde8a101c8a96d6202228fb5ee10a7b223cbc846dc1d62917f"
    },
    {
      "method": "interactiveTurn_listArchiveEntry_opensArchived",
      "capturedAt": "2026-10-20T08:02:10Z",
      "hashes": {
        "batch-05/interactiveTurn_listArchiveEntry_opensArchived.json": "023a8739ab47b4c1e5e3a1e6652eb2f78e73b87b72837bedca1992ce096f6097",
        "batch-05/interactiveTurn_listArchiveEntry_opensArchived.capture.json": "8ee0a951824f49284ead9f47d12f1c4ae783fdef4a1ff580aeda1402bef358cc",
        "batch-05/interactiveTurn_listArchiveEntry_opensArchived.comparison-private.json": "42e4703776cd4ba6f018763ef98b7c5cf04e5e3eff85438c0d79d33259bee2c5",
        "batch-05/interactiveTurn_listArchiveEntry_opensArchived.evidence.json": "b6fef56c7fa9f380365999f75ff74ecacdc2ec0afd98a2c38d991b2efa697443"
      },
      "oldCommittedFixtureSha256": "683f89b418cef49e4c709e4d612b7a4cefe6694db3fb895f53a2c6ff5a3121a3"
    },
    {
      "method": "interactiveTurn_markdownLink_opensLiveNoteInReader",
      "capturedAt": "2026-10-20T08:02:38Z",
      "hashes": {
        "batch-05/interactiveTurn_markdownLink_opensLiveNoteInReader.json": "b489d340f23f5ac9123849c1647d21667110f92dbebc72afa01f0cf648fab7ac",
        "batch-05/interactiveTurn_markdownLink_opensLiveNoteInReader.capture.json": "4e447b73c20c6b44360644848740ffc5e9ec3e4c079a834ef941c14997f0ac5e",
        "batch-05/interactiveTurn_markdownLink_opensLiveNoteInReader.comparison-private.json": "22439c1e4e3902e8c0feb48f40558535fffb973bb7f08169c9d1304e33d0b095",
        "batch-05/interactiveTurn_markdownLink_opensLiveNoteInReader.evidence.json": "65e4bc3f18a130c5e058248ce9edd4dc0f1a46a7a1628c09a54ac0e07dd927d9"
      },
      "oldCommittedFixtureSha256": "07d52355862964654f016128f211eae9d2ba9b2563a3933cbc3ce4aeb36ea8f7"
    },
    {
      "method": "interactiveTurn_muteChannel_roundTripsThroughTheHost",
      "capturedAt": "2026-10-20T08:02:59Z",
      "hashes": {
        "batch-05/interactiveTurn_muteChannel_roundTripsThroughTheHost.json": "d128f20e0ee82a07a9c0211beb2abc882859d2d88d2b331ece40a6de25ee1eb9",
        "batch-05/interactiveTurn_muteChannel_roundTripsThroughTheHost.capture.json": "29be6c593624728ca3f0dba6b3ef3e5074a4c41cea38b86adfffc9857fef65fc",
        "batch-05/interactiveTurn_muteChannel_roundTripsThroughTheHost.comparison-private.json": "2f1925f4ad6caf3f6093128e9365102165350e4190e3e8707441e17514479e89",
        "batch-05/interactiveTurn_muteChannel_roundTripsThroughTheHost.evidence.json": "dcc148cfa3c488beeb18f6e4e51c2284868cc735018a6b5a17c9a98a126f91bd"
      },
      "oldCommittedFixtureSha256": "89f4d243a627691bdec27c14e85879093b158182a46546e3c7544e563cb51dd3"
    },
    {
      "method": "interactiveTurn_newSession_rendersSessionBoundaryDelimiter",
      "capturedAt": "2026-10-20T08:03:24Z",
      "hashes": {
        "batch-05/interactiveTurn_newSession_rendersSessionBoundaryDelimiter.json": "2090d44f1c67421cf1dcb3f6033cef46a3fb8f0306685a3b0a3c139cd5c008a5",
        "batch-05/interactiveTurn_newSession_rendersSessionBoundaryDelimiter.capture.json": "a55748936059047b5e8b9b2307df5cd635516a1ab3516d3c1e3d81320a6861d8",
        "batch-05/interactiveTurn_newSession_rendersSessionBoundaryDelimiter.comparison-private.json": "7eddd52802621c2db8ba7361bf2213f6cb90409a767c37c64da3bdd363aacc06",
        "batch-05/interactiveTurn_newSession_rendersSessionBoundaryDelimiter.evidence.json": "29a294d06b4b580b804304a418a365282ae129ba206437e93a0326ae024e4263"
      },
      "oldCommittedFixtureSha256": "eaea566efaba99b6c2f26978274efe3d3c7a00dcd1bb0bdb05e948de9966cf5a"
    },
    {
      "method": "interactiveTurn_offlineRetry_reconnectsSameHostAndReplies",
      "capturedAt": "2026-10-20T08:04:58Z",
      "hashes": {
        "batch-05/interactiveTurn_offlineRetry_reconnectsSameHostAndReplies.json": "c4896642661c4ca8769c80767f69b114c33c8670eb3824ef5003c6f206f1ed73",
        "batch-05/interactiveTurn_offlineRetry_reconnectsSameHostAndReplies.capture.json": "50a9cffe4bd0213d404f2e993b661538330579d49c5fd08ac02c87cf5e622efa",
        "batch-05/interactiveTurn_offlineRetry_reconnectsSameHostAndReplies.comparison-private.json": "9a96e15c9bc14c312e3bb8d3338b29c271f097bf57ce3033420ea9da2e0eaba5",
        "batch-05/interactiveTurn_offlineRetry_reconnectsSameHostAndReplies.evidence.json": "ee960f6965a86bbe818ef8f883972a9721de9097efbed4bf41a9c03427cb8e7a"
      },
      "oldCommittedFixtureSha256": "a7f54dd40ad1b408bb82fea992d002ae18ee3984ac3c4f04ea2a5944aa55cecc"
    },
    {
      "method": "interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild",
      "capturedAt": "2026-10-20T08:06:11Z",
      "hashes": {
        "batch-05/interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.json": "5d8169642b094077b0d473af56612d55dc0f1b1f14b88ba23bcef23c647666fa",
        "batch-05/interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.capture.json": "7dd912722de7a73e78607e62e9e9caaaa9e5ee69f37466f39fe835cdef4d6d9f",
        "batch-05/interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.comparison-private.json": "902934ee662703e6c48447866b129272012f9d03e2ff5dc2c9321c2ae8b63204",
        "batch-05/interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild.evidence.json": "772dd0ba3bb5faa23690bd43a042a84e2070117ff33ef589d3680a88865cafbc"
      },
      "oldCommittedFixtureSha256": "843c3b00efab6fd911751ec29ade7a2ee5d7a657cb61cda88b29a07cea8ddf19"
    },
    {
      "method": "interactiveTurn_otherConversationAttentionPills_waitingAndFinished",
      "capturedAt": "2026-10-20T08:06:54Z",
      "hashes": {
        "batch-05/interactiveTurn_otherConversationAttentionPills_waitingAndFinished.json": "0d64113330a074c34d55cb37fa251fdcc7aa651902817fc6aad786116cb70c3c",
        "batch-05/interactiveTurn_otherConversationAttentionPills_waitingAndFinished.capture.json": "c9ff5a9333b95924df50ade8f699240b780a96de1a79874cec8845d476bc88a6",
        "batch-05/interactiveTurn_otherConversationAttentionPills_waitingAndFinished.comparison-private.json": "7b5e88fe1f4b7d3c15ec7a59ac38f5130c88cf422f6d33ae11318f0915421700",
        "batch-05/interactiveTurn_otherConversationAttentionPills_waitingAndFinished.evidence.json": "babf19f24be10348e1f8e61cd5620be98da76c1c8e5ef39c2d3b5dbb24edbd04"
      },
      "oldCommittedFixtureSha256": "ac5958c0d08cedfe31141c583d9caef0e4b0ad6938c21ba3ca38fc0546591b7d"
    },
    {
      "method": "interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload",
      "capturedAt": "2026-10-20T08:07:42Z",
      "hashes": {
        "batch-06/interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload.json": "1a80e4b7b383043f4684542b71cadc3c23249bd332574149745af40484ac711b",
        "batch-06/interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload.capture.json": "dba7ab566825ce9fdf5b0cc08764eaf9cc6c2fc040dc883f3af3c891e9fcf23e",
        "batch-06/interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload.comparison-private.json": "5c593b4dd3afb0471e93f85034af59208407c2cc38e6fea54c7b137934c641cc",
        "batch-06/interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload.evidence.json": "b61550a4f99c0bb6f650912ea918e7e924a69d548ed3662c761a44eb7cef9040"
      },
      "oldCommittedFixtureSha256": "0ee174ba8d85574edcbc7df6bfaab43f971e85820b4b06758adcf664948cfcdb"
    },
    {
      "method": "interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool",
      "capturedAt": "2026-10-20T08:08:12Z",
      "hashes": {
        "batch-06/interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool.json": "5a2da8a24df62fab794cc224d86e5223efc14a2ee0b6940eb100e6c210c68fd3",
        "batch-06/interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool.capture.json": "101b75455b24fa8da6943e63838900d2e1ef7abb48b4890dab244d7830fa4414",
        "batch-06/interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool.comparison-private.json": "d75e0df93cb59ffcb0af3d00d072283839e0ad27e55b45827b1e0fdbf0b8f0e6",
        "batch-06/interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool.evidence.json": "cdc2fa97cd529ae4726d66e98a22de997147f535ea9fcd0cdfc6ba6e7eaa316b"
      },
      "oldCommittedFixtureSha256": "06cf10fdb53b040b6524b79291f04c48960c5122609fbb8165c9a8c99e689b93"
    },
    {
      "method": "interactiveTurn_pingPrompt_footerShowsContextUsage",
      "capturedAt": "2026-10-20T08:08:30Z",
      "hashes": {
        "batch-06/interactiveTurn_pingPrompt_footerShowsContextUsage.json": "4df8b01c98d3ac3d35670955d145ec560b2fe3dca622eabf09f93639c0c1e661",
        "batch-06/interactiveTurn_pingPrompt_footerShowsContextUsage.capture.json": "f44ecf2f0759ede22d7d11554cb250d150c13944b30b0ac3a1e5b3dceb572302",
        "batch-06/interactiveTurn_pingPrompt_footerShowsContextUsage.comparison-private.json": "fa325fe4a21f9c2d1885853320d60bf7a792049e34e8671d0257d3433d546f07",
        "batch-06/interactiveTurn_pingPrompt_footerShowsContextUsage.evidence.json": "a75a1ccf6b35c454ccc7664d8b269a5d2b190e62fd1c8cf2da20c95e1e1ed2e1"
      },
      "oldCommittedFixtureSha256": "262aaf339577607cbc432493f55fb0842ea35f736be01a94d84cf30f48040292"
    },
    {
      "method": "interactiveTurn_renameConversation_relabelsTopBarAndListRow",
      "capturedAt": "2026-10-20T08:08:44Z",
      "hashes": {
        "batch-06/interactiveTurn_renameConversation_relabelsTopBarAndListRow.json": "efeb080463ed3e668b174274be0f885dd2d50c288ef3d4a1da1df8d2ddae088c",
        "batch-06/interactiveTurn_renameConversation_relabelsTopBarAndListRow.capture.json": "93102a0ba0c61c30e207b0ba2df75875cc22f6a25dbe96ccc71bfc641ec3d7f1",
        "batch-06/interactiveTurn_renameConversation_relabelsTopBarAndListRow.comparison-private.json": "b8fe3b46ccfbf1ee038448c9dcd3335dc3d9f7f3e3fd4e0e4b7764bdc6c1eb33",
        "batch-06/interactiveTurn_renameConversation_relabelsTopBarAndListRow.evidence.json": "98c19fd539f678158f10157762f40b7b3a89056b128dc880edafa7b9f6062b95"
      },
      "oldCommittedFixtureSha256": "264dd4ba5cecb516ba0b58ed3bf8ad025271cfa3ccc6650c3bf77d576eb66c98"
    },
    {
      "method": "interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately",
      "capturedAt": "2026-10-20T08:09:04Z",
      "hashes": {
        "batch-06/interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately.json": "a3557ae9fab6aa30f725983026256f9cf50ab82f903b8762fc8f94533d482eb7",
        "batch-06/interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately.capture.json": "75d5b7a36989460f3ae2c11e578fa460b9c22c2ff79136a2ef987d068473f9cf",
        "batch-06/interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately.comparison-private.json": "ba3d11d166f2ae018db3bfdb3856e0bfb747b77ae532353ad57c483c24677746",
        "batch-06/interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately.evidence.json": "dd8d36fe477a962c95dc018cc21f5c63de9c0d3b40b3a8e7d06997b64cc47100"
      },
      "oldCommittedFixtureSha256": "08796ded1d745c8efd481744efb350e3b3fc17fe1c85441389fb033f6de43b2f"
    },
    {
      "method": "interactiveTurn_saveAsChannel_promotesToChannelTier",
      "capturedAt": "2026-10-20T08:09:20Z",
      "hashes": {
        "batch-06/interactiveTurn_saveAsChannel_promotesToChannelTier.json": "3ec155c1b3f917f761582392630394363dc58076c41608d80dc290b8faefd784",
        "batch-06/interactiveTurn_saveAsChannel_promotesToChannelTier.capture.json": "184d881d1805c0871859ec4c0cd98bc42c30545c513f3cc0e0e080be24ff5867",
        "batch-06/interactiveTurn_saveAsChannel_promotesToChannelTier.comparison-private.json": "1c2c32873eab7a5f5facdaac46b1d08a9e4306e0a82e672ad82e714e2e2a6d46",
        "batch-06/interactiveTurn_saveAsChannel_promotesToChannelTier.evidence.json": "1a1e747aec744aa20041a09c3a3d0a05c005558e09775380eccdf6ca077e939e"
      },
      "oldCommittedFixtureSha256": "fb47a4413e2f54ae942d571890e91361d1266039ded5538f2be86989aaceaaf9"
    },
    {
      "method": "interactiveTurn_scannerConfirm_waitsForHostThenOpensList",
      "capturedAt": "2026-10-20T08:09:37Z",
      "hashes": {
        "batch-06/interactiveTurn_scannerConfirm_waitsForHostThenOpensList.json": "c59b03b897090a136951d1f93e3a8ba43a0467e8a75b98526a6aba99c57ef2a4",
        "batch-06/interactiveTurn_scannerConfirm_waitsForHostThenOpensList.capture.json": "091cba9d545e7e6de9153346a2f1c9c0b6338a59d88fe2ad87faa59e293a55da",
        "batch-06/interactiveTurn_scannerConfirm_waitsForHostThenOpensList.comparison-private.json": "f5075c68a4573e33917e4327d48dc53b8834921a33d2f46567cd87e6f9dcb4de",
        "batch-06/interactiveTurn_scannerConfirm_waitsForHostThenOpensList.evidence.json": "8e2d6db0c305a879ddd2072054c8b2bd8ba370a5414eed58ed9bce2a828a2110"
      },
      "oldCommittedFixtureSha256": "7fdda5382e4c13c02c75127cc41d02a11092e6dc728ee5e106f099530a1fb306"
    },
    {
      "method": "interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched",
      "capturedAt": "2026-10-20T08:09:58Z",
      "hashes": {
        "batch-06/interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched.json": "c1485d79d2be032545bd17c6033c97e04b3fa0b35cf5e3d5756bc36f44a34428",
        "batch-06/interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched.capture.json": "cbd0abdb9983b817e67c000db5e6bd8c06eea81e74f335ce31b33960371b6213",
        "batch-06/interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched.comparison-private.json": "28a2b7751dbe9213406070f17d2c4adda98e41025cca09a6ce11a414335151c2",
        "batch-06/interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched.evidence.json": "921d48f3d0d9f9b2fdbed4aba33c7a200ebc6c8ec8c7ba622efa732e588c2d67"
      },
      "oldCommittedFixtureSha256": "3bde1472c5143cee8bcfb891c58802387e87e972070ae152be6deb4202215972"
    },
    {
      "method": "interactiveTurn_sendQueuedNow_reachesRunningTurn",
      "capturedAt": "2026-10-20T08:11:03Z",
      "hashes": {
        "batch-06/interactiveTurn_sendQueuedNow_reachesRunningTurn.json": "62a19d53a65dc5b95eb71f478c32c591b8b3391316145e68bece63a535941ea1",
        "batch-06/interactiveTurn_sendQueuedNow_reachesRunningTurn.capture.json": "2e045827f5554d5df41630bbaea5fb007aaf736fa07b7b34b0c5f0bbb6651a0c",
        "batch-06/interactiveTurn_sendQueuedNow_reachesRunningTurn.comparison-private.json": "a82f121d4269713ac97a8844ab2283fd2a3e6bc7a9a487cf9f36cf0a6bf627c3",
        "batch-06/interactiveTurn_sendQueuedNow_reachesRunningTurn.evidence.json": "d6642e81b8670020d1f56cd3e7d55a73153edafbd2be2fac34cf843e581739da"
      },
      "oldCommittedFixtureSha256": "f71758f832d25cf60a60c831c3f6eb689c6bd42f4c4635d17e3a9de3f35ede11"
    },
    {
      "method": "interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes",
      "capturedAt": "2026-10-20T08:11:32Z",
      "hashes": {
        "batch-06/interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes.json": "bbb6c76f6fc91c92eb0c1d000fd25827697851ad50de6061baa8b409ef64142f",
        "batch-06/interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes.capture.json": "c876c7c344d68e33556cce4f4ef531f9f515bce1497c1648804f33486b7482c8",
        "batch-06/interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes.comparison-private.json": "a32e0bac2d8b558b78a3ce6f4aade37beb7b8951398ef55052626bdc14ca004e",
        "batch-06/interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes.evidence.json": "b67a48acc113f70a916d0d63c10fbae4fc22a2cb739bc1ae89f0c7299fa37326"
      },
      "oldCommittedFixtureSha256": "e8913e69ef7e059b2e7c095fcd06990b3fba6fb7d0c3dcf0b754a682838bf378"
    },
    {
      "method": "interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn",
      "capturedAt": "2026-10-20T08:12:38Z",
      "hashes": {
        "batch-07/interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn.json": "965e5aa7cf711f98ddc07da19fb7580471ec45904d9f6e45d57baf88f3eed214",
        "batch-07/interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn.capture.json": "d538b5f84dbb91a532ff1e6a52125b927043562b95c59c6bce6ac81870d0e94e",
        "batch-07/interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn.comparison-private.json": "6f4cac9e7c9cac3dcb44eebefef708f70954d890108c00221620060febe6fe46",
        "batch-07/interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn.evidence.json": "40066ac68a2e9d3b5a8a73c835b32f896c0bfdf4ad4f5b7dd0af1845339c5e62"
      },
      "oldCommittedFixtureSha256": "7e635a7e965d243c1bb6a1c296ea80094aabce18b9f58e2d7d4577c40ac609b9"
    },
    {
      "method": "interactiveTurn_toolPrompt_rendersToolStepInThread",
      "capturedAt": "2026-10-20T08:12:59Z",
      "hashes": {
        "batch-07/interactiveTurn_toolPrompt_rendersToolStepInThread.json": "32a01a04adc72c4b47c1c6d651fe51608efd7bd61770acb004ea27e39dccdb87",
        "batch-07/interactiveTurn_toolPrompt_rendersToolStepInThread.capture.json": "b4bca2c624e2a79b0491beadfe1c0f41cb01661a13975606fe85399329a56be8",
        "batch-07/interactiveTurn_toolPrompt_rendersToolStepInThread.comparison-private.json": "45e59b0f720e720b85535b73052abc0b4ea86f5c883e21631ce5f103dbd2b9a2",
        "batch-07/interactiveTurn_toolPrompt_rendersToolStepInThread.evidence.json": "f4d8d69f01fa814bc24d990ac88c5cfd92713678bc4e5be40973f50053f16c8a"
      },
      "oldCommittedFixtureSha256": "73a8dad3ef4fe9231fb936be0cc9ee7330d9e8789a7acf915cfd3c9234e3b62e"
    },
    {
      "method": "interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy",
      "capturedAt": "2026-10-20T08:13:21Z",
      "hashes": {
        "batch-07/interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy.json": "bfd014791761b56b1f0cdf08594750a8e193bd0c050fbfbcc7d6724497686c2d",
        "batch-07/interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy.capture.json": "ee074569275f3f9bc315c158ff00d69ea151861c5fc5693b5cb6e08290ca1a1f",
        "batch-07/interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy.comparison-private.json": "574985e170d23fbe713e6ac345a58b1306f25a88bee68d5445e5c366303c1b34",
        "batch-07/interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy.evidence.json": "1fdec301b870fd30d533a96d19abc6bd03ca6e1e85edd61b8c71e7639e09018b"
      },
      "oldCommittedFixtureSha256": "0a5719b07639dfe9e3253c2fef4add680f335f56e425e7314b5378c25877d94b"
    },
    {
      "method": "interactiveTurn_twoHostsArchive_staysPerHost",
      "capturedAt": "2026-10-20T08:13:41Z",
      "hashes": {
        "batch-07/interactiveTurn_twoHostsArchive_staysPerHost.json": "a08aec0cb268e24627728a64e5baf1a73d39883209848b8b5ad7a6bc32c4d19c",
        "batch-07/interactiveTurn_twoHostsArchive_staysPerHost.capture.json": "92b05696d73b6615a3e897cb9296baf6ef83bf7b4323820eac9a392cf7fed89c",
        "batch-07/interactiveTurn_twoHostsArchive_staysPerHost.comparison-private.json": "8bcbef61f586f061dbdf6ae5893ed3d128688286f18333c60a39f09ba28402cc",
        "batch-07/interactiveTurn_twoHostsArchive_staysPerHost.evidence.json": "41e541e9f77a9edb665e1beec0ec25c5b6525075f576662db4f10b0212f15a94"
      },
      "oldCommittedFixtureSha256": "e563ab9de09b8e60fac233d87015d6b341abb3424be97df87f91e3ed39430aa6"
    },
    {
      "method": "interactiveTurn_twoHostsCollidingConversationId_stayPerHost",
      "capturedAt": "2026-10-20T08:14:08Z",
      "hashes": {
        "batch-07/interactiveTurn_twoHostsCollidingConversationId_stayPerHost.json": "9340d313afa634ac6cf47909ecd7e11b38a50db80503250aeb7dbe1fb54ddfd2",
        "batch-07/interactiveTurn_twoHostsCollidingConversationId_stayPerHost.capture.json": "e5ef6e8d2cec93b1cf63cd4db1aa9ec4b30f1f4880b459d1ccbcd4e97202684d",
        "batch-07/interactiveTurn_twoHostsCollidingConversationId_stayPerHost.comparison-private.json": "d15b4602c7a92eaec5a114cbcdedc09a7c1f4d3451bcf82c6624f26daa405c95",
        "batch-07/interactiveTurn_twoHostsCollidingConversationId_stayPerHost.evidence.json": "fd49bde2885e3ae5b75dd6de8432f0d3af9e85da50497b384a6d793ac6d6b89c"
      },
      "oldCommittedFixtureSha256": "309d800fd338babfcf5c5f8732a34c490eb30ec116515bf1eec63a85c024e559"
    },
    {
      "method": "negativeControl_toolClaudeNeverUses_isNeverDisplayed",
      "capturedAt": "2026-10-20T08:15:59Z",
      "hashes": {
        "batch-07/negativeControl_toolClaudeNeverUses_isNeverDisplayed.json": "552e0b38aa5878cc901ded04808db8ad96e54f4308d5972cd96f9fe9a8c52fa6",
        "batch-07/negativeControl_toolClaudeNeverUses_isNeverDisplayed.capture.json": "1efe1f9274a90d519f82d72a18ca6be61b46959ba0ee6db07ac106b840efa9fc",
        "batch-07/negativeControl_toolClaudeNeverUses_isNeverDisplayed.comparison-private.json": "3b9e6df568d2cef6da48fbcbbdfc5f2fa9f26a5bf32ceeda7e38a979cfab96c6",
        "batch-07/negativeControl_toolClaudeNeverUses_isNeverDisplayed.evidence.json": "187f706a09dad01e946642382be840ad72a8f6f6c829c4deecc8c03346e51cfd"
      },
      "oldCommittedFixtureSha256": "dbc5048d24465d24c5d6b2e91d9e55d5a25193bd3eabe88e3a3df4d74a5c716e"
    },
    {
      "method": "negativeControl_wordClaudeNeverSays_isNeverDisplayed",
      "capturedAt": "2026-10-20T08:17:47Z",
      "hashes": {
        "batch-07/negativeControl_wordClaudeNeverSays_isNeverDisplayed.json": "5dd0f56910e2604e618f201a885cf815ddd22dc2d362c22eb2da71e60f11937a",
        "batch-07/negativeControl_wordClaudeNeverSays_isNeverDisplayed.capture.json": "fe4deeb079f3b360a53cee32dbfc7d58a1ccbcc772e2c8b55ffd630ed6d502c9",
        "batch-07/negativeControl_wordClaudeNeverSays_isNeverDisplayed.comparison-private.json": "39823ce2f2ee0dc5b21609adc3f99fda8bb831a53a175125acd0bc05d42a89ee",
        "batch-07/negativeControl_wordClaudeNeverSays_isNeverDisplayed.evidence.json": "f8dc027b45275b5e3ffe2b7e281e67caf4ad549bdd7940419c32a43ef62a2dc9"
      },
      "oldCommittedFixtureSha256": "6c28a10b87115f9b5f41a143bd451ceb91cc3c619a9995e9fabd1a0b11170c1f"
    }
  ],
  "errors": []
}
```
