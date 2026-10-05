package de.pyryco.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.network.PairingParseResult
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.parsePairingPayload
import de.pyryco.mobile.data.network.serverKeyFingerprint
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.ThreadDestinationFactory
import de.pyryco.mobile.notifications.NotificationTap
import de.pyryco.mobile.ui.conversations.components.LocalWorkspacePickerRepository
import de.pyryco.mobile.ui.conversations.list.ChannelListEvent
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.conversations.list.DiscussionListEvent
import de.pyryco.mobile.ui.conversations.list.DiscussionListScreen
import de.pyryco.mobile.ui.conversations.list.DiscussionListUiState
import de.pyryco.mobile.ui.conversations.list.DiscussionListViewModel
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.list.PLAY_STORE_URL
import de.pyryco.mobile.ui.conversations.list.PendingPromotion
import de.pyryco.mobile.ui.conversations.thread.LinkedMarkdownReaderDestination
import de.pyryco.mobile.ui.conversations.thread.MarkdownReaderDestination
import de.pyryco.mobile.ui.conversations.thread.ThreadAttentionNotice
import de.pyryco.mobile.ui.conversations.thread.ThreadNavigation
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.UsageLimitDismissals
import de.pyryco.mobile.ui.conversations.thread.readLinkedMarkdown
import de.pyryco.mobile.ui.conversations.thread.rememberThreadAttention
import de.pyryco.mobile.ui.onboarding.CameraPreview
import de.pyryco.mobile.ui.onboarding.PairCodeEvent
import de.pyryco.mobile.ui.onboarding.PairCodePhase
import de.pyryco.mobile.ui.onboarding.PairCodeScreen
import de.pyryco.mobile.ui.onboarding.PairCodeViewModel
import de.pyryco.mobile.ui.onboarding.PairingPrefill
import de.pyryco.mobile.ui.onboarding.ScannerEvent
import de.pyryco.mobile.ui.onboarding.ScannerScreen
import de.pyryco.mobile.ui.onboarding.ScannerUiState
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import de.pyryco.mobile.ui.onboarding.WelcomeScreen
import de.pyryco.mobile.ui.settings.AboutScreen
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsEvent
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsScreen
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsViewModel
import de.pyryco.mobile.ui.settings.SettingsScreen
import de.pyryco.mobile.ui.settings.SettingsViewModel
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // #1510: the app always draws its static dark theme, so the bar icons stay light whatever the
        // phone's own night mode; the default SystemBarStyle.auto would follow the phone instead.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // #685: a notification tap's target, read once. A recreated activity keeps its intent, so reading
        // it again after a rotation would re-open the thread over wherever the operator went since.
        val openTarget = if (savedInstanceState == null) NotificationTap.target(intent) else null
        // Test builds only: a hands-on check's pairing code, read once for the same reason.
        val pairingPrefill = if (savedInstanceState == null) PairingPrefill.from(intent, BuildConfig.DEBUG) else null
        setContent {
            val appPreferences = koinInject<AppPreferences>()
            val pairedServerStore = koinInject<PairedServerCollectionStore>()
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val paired: Boolean? by produceState<Boolean?>(
                        initialValue = null,
                        pairedServerStore,
                        appPreferences,
                    ) {
                        RelayLog.d { "event=workspace_startup_started" }
                        val hosts = pairedServerStore.list()
                        val migration = appPreferences.migrateDefaultWorkspace(hosts.map { it.record.serverId }.toSet())
                        if (migration.isSuccess) {
                            value = hosts.isNotEmpty()
                            RelayLog.d { "event=workspace_startup_ready" }
                        } else {
                            RelayLog.w { "event=workspace_startup_blocked code=migration_failed" }
                        }
                    }
                    when (val v = paired) {
                        null ->
                            Surface(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                            ) {}
                        else ->
                            PyryNavHost(
                                startDestination = if (v) Routes.CHANNEL_LIST else Routes.WELCOME,
                                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                                openTarget = openTarget.takeIf { v },
                                pairingPrefill = pairingPrefill,
                            )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PyryNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    openTarget: HostConversationTarget? = null,
    pairingPrefill: PairingPrefill? = null,
) {
    val destinations = koinInject<ThreadDestinationFactory>()
    val appPreferences = koinInject<AppPreferences>()
    val conversations = koinInject<HostConversationSource>()
    // Held in memory only, never saved state: it carries the pairing token. Cleared once the screen has it.
    var pendingPrefill by remember { mutableStateOf(pairingPrefill) }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.WELCOME) {
            val context = LocalContext.current
            WelcomeScreen(
                onPaired = {
                    navController.navigate(Routes.SCANNER)
                },
                onSetup = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_URL)),
                    )
                },
            )
        }
        composable(Routes.SCANNER) {
            val context = LocalContext.current
            val vm = koinViewModel<ScannerViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()

            // #1386: Confirm saves and then waits in the VM; the channel list opens only once the host
            // answered, and Cancel pops the scanner with the host still saved.
            LaunchedEffect(state) {
                when (state) {
                    ScannerUiState.Paired ->
                        navController.navigate(Routes.CHANNEL_LIST) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                            launchSingleTop = true
                        }
                    ScannerUiState.Cancelled -> navController.popBackStack()
                    else -> Unit
                }
            }

            val permissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    vm.onEvent(
                        if (granted) ScannerEvent.PermissionGranted else ScannerEvent.PermissionDenied,
                    )
                }
            // Guard against re-prompting after a config change while Denied; the VM (survives
            // rotation) already retains the resolved ReadyToScan/Denied state.
            var requested by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val alreadyGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                when {
                    alreadyGranted -> vm.onEvent(ScannerEvent.PermissionGranted)
                    !requested -> {
                        requested = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            // The security gate (#343): a successful decode parses + validates the payload into a
            // real PairedServer (#320) and derives its static-key fingerprint (#342), then parks in
            // AwaitingConfirm — it does NOT persist. The persist moves behind the Confirm button
            // (ScannerEvent.ConfirmPairing); this effect never touches the store. A parse failure, or a
            // derive that returns null (structurally unreachable for a Success — the stored key was
            // already proven base64-std-of-32-bytes — but handled so staticKeyFingerprint's require
            // can't throw into this coroutine), routes to the Error surface. On the AwaitingConfirm
            // transition the state is no longer Decoded, so this effect re-runs as a no-op. The
            // parse + derive are microsecond CPU work, so they run inline on this Main coroutine.
            LaunchedEffect(state) {
                val decoded = state as? ScannerUiState.Decoded ?: return@LaunchedEffect
                when (val result = parsePairingPayload(decoded.payload)) {
                    is PairingParseResult.Success -> {
                        val fingerprint = serverKeyFingerprint(result.server.serverStaticPublicKey)
                        if (fingerprint == null) {
                            Log.w(TAG, "fingerprint derive failed: bad-stored-key")
                            vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))
                        } else {
                            vm.onEvent(ScannerEvent.PairingPrepared(fingerprint, result.server))
                        }
                    }
                    is PairingParseResult.Failure -> {
                        Log.w(TAG, "pairing parse failed: ${result.reason}")
                        vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))
                    }
                }
            }

            // While confirming, system Back behaves as Decline (return to scanner, persist nothing)
            // rather than popping the whole scanner route back to Welcome (AC #3). Disabled
            // otherwise, so Back pops normally.
            BackHandler(enabled = state is ScannerUiState.AwaitingConfirm) {
                vm.onEvent(ScannerEvent.DeclinePairing)
            }
            BackHandler(enabled = state is ScannerUiState.Verifying || state is ScannerUiState.VerificationFailed) {
                vm.onEvent(ScannerEvent.CancelVerification)
            }

            ScannerScreen(
                state = state,
                onNavigateBack = { navController.popBackStack() },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                onPasteCode = { navController.navigate(Routes.PAIR_CODE) },
                // The VM confirms only from AwaitingConfirm, saving exactly the record whose fingerprint
                // is shown; once back/decline moved it off, a late tap is a no-op.
                onConfirmPairing = { vm.onEvent(ScannerEvent.ConfirmPairing) },
                onDeclinePairing = { vm.onEvent(ScannerEvent.DeclinePairing) },
                onRetryPairing = { vm.onEvent(ScannerEvent.RetryVerification) },
                onCancelPairing = { vm.onEvent(ScannerEvent.CancelVerification) },
                cameraPreview = {
                    if (state is ScannerUiState.ReadyToScan) {
                        CameraPreview(
                            onQrDecoded = { vm.onEvent(ScannerEvent.QrDecoded(it)) },
                            onCameraError = { vm.onEvent(ScannerEvent.CameraError(it)) },
                        )
                    }
                },
            )
        }
        composable(Routes.PAIR_CODE_ROUTE, arguments = Routes.pairCodeArguments()) {
            val vm = koinViewModel<PairCodeViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                val prefill = pendingPrefill ?: return@LaunchedEffect
                pendingPrefill = null
                vm.onEvent(PairCodeEvent.Name(prefill.name))
                vm.onEvent(PairCodeEvent.Code(prefill.code))
            }
            LaunchedEffect(state.phase) {
                when (state.phase) {
                    PairCodePhase.Cancelled -> navController.popBackStack()
                    PairCodePhase.Complete ->
                        navController.navigate(Routes.CHANNEL_LIST) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    else -> Unit
                }
            }
            PairCodeScreen(state, vm::onEvent)
        }
        composable(Routes.CHANNEL_LIST) {
            val vm = koinViewModel<ChannelListViewModel>()
            val hostState by vm.hostState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val requestNotifications = rememberNotificationPermissionRequest(appPreferences)
            // #685: asked at most once from here, and only while the Settings switch is on.
            LaunchedEffect(Unit) {
                if (shouldAskNotificationPermission(
                        enabled = appPreferences.notificationsEnabled.first(),
                        granted = notificationsPermitted(context),
                        asked = appPreferences.notificationPermissionAsked.first(),
                    )
                ) {
                    requestNotifications()
                }
            }
            LaunchedEffect(vm) {
                vm.hostNavigationEvents.collect { navController.openThread(it) }
            }
            LaunchedEffect(vm) {
                vm.lastHostUnpaired.collect { navController.returnToWelcome() }
            }
            ChannelListScreen(
                hostState = hostState,
                onEvent = { event ->
                    when (event) {
                        // The row carries its own host: the tree draws rows from every host, so the
                        // selected-host adapter would open the wrong one (#731).
                        is ChannelListEvent.TreeRowTapped -> vm.onHostRowTapped(event.target)
                        is ChannelListEvent.TreeFoldToggled -> vm.onFoldToggled(event.key)
                        // The gear captures the current host once, here, the way a row tap
                        // captures its own (#749). The destination owns that exact id from then
                        // on; a later selection change cannot re-aim what it describes.
                        ChannelListEvent.SettingsTapped ->
                            navController.navigate(Routes.settings(destinations.selectedServerId()))
                        // One destination, two doors (#737), and since #715 both must open on an
                        // owner: this one captures the current host exactly as the gear above it
                        // does, where Settings' own row instead inherits the owner its destination
                        // already holds. `Routes.ARCHIVED_DISCUSSIONS` is the route *pattern* now,
                        // never a navigable route — navigating to it binds the literal text
                        // `{serverId}` as the owner, which `HostDestination` then rejects as an
                        // unknown host and bounces straight back here.
                        //
                        // No selected host means no archive to open, so the tap does nothing rather
                        // than reaching for a blank id: `archived_discussions/` matches no
                        // destination and Navigation throws on it.
                        ChannelListEvent.ArchiveTapped ->
                            destinations.selectedServerId()?.let {
                                navController.navigate(Routes.archive(it))
                            }
                        // Pairing's existing entry, reused rather than a second flow (#738): both of its
                        // completions already land back here — the camera path pops SCANNER inclusive
                        // onto this very entry, the paste-code path pops the graph.
                        ChannelListEvent.PairHostTapped ->
                            navController.navigate(Routes.SCANNER)
                        is ChannelListEvent.TreeHostChatAddTapped -> vm.createChat(event.serverId)
                        // Same rule again for editing (#744): the control's own host. The view model
                        // reads that host's stored record and owns the modal's target and flags.
                        is ChannelListEvent.TreeHostEditTapped -> vm.openHostEditor(event.serverId)
                        // And for reconnecting (#840): the control's own host, retried alone.
                        is ChannelListEvent.TreeHostReconnectTapped -> vm.reconnectHost(event.serverId)
                        // Unless its pairing was rejected (#842): then the control re-pairs that host alone.
                        is ChannelListEvent.TreeHostRePairTapped ->
                            navController.navigate(Routes.pairCode(event.serverId))
                        // Or if it refused this app build as too old (#1009): only an update recovers, so
                        // the control opens the store listing and never redials.
                        ChannelListEvent.TreeHostUpdateTapped -> uriHandler.openUri(PLAY_STORE_URL)
                        is ChannelListEvent.HostEditNameSubmitted -> vm.submitHostName(event.name)
                        ChannelListEvent.HostEditDismissed -> vm.dismissHostEditor()
                        ChannelListEvent.HostUnpairRequested -> vm.requestHostUnpair()
                        ChannelListEvent.HostUnpairConfirmed -> vm.confirmHostUnpair()
                        ChannelListEvent.HostUnpairDeclined -> vm.declineHostUnpair()
                        // And for renaming a chat (#827): the pencil's own host and conversation.
                        is ChannelListEvent.TreeChatEditTapped -> vm.openChatEditor(event.target)
                        is ChannelListEvent.ChatEditNameSubmitted -> vm.submitChatName(event.name)
                        ChannelListEvent.ChatEditDismissed -> vm.dismissChatEditor()
                        ChannelListEvent.ChatArchiveRequested -> vm.archiveChat()
                        is ChannelListEvent.AddWorkspaceSelected -> vm.selectAddWorkspaceFolder(event.path)
                        is ChannelListEvent.AddWorkspaceFolderCreateRequested -> vm.createAddWorkspaceFolder(event.name)
                        ChannelListEvent.AddWorkspaceSubmitted -> vm.submitAddWorkspace()
                        ChannelListEvent.AddWorkspaceDismissed -> vm.dismissAddWorkspace()
                        is ChannelListEvent.WorkspaceEditNameSubmitted -> vm.submitWorkspaceName(event.name)
                        ChannelListEvent.WorkspaceEditDismissed -> vm.dismissWorkspaceEditor()
                        ChannelListEvent.WorkspaceArchiveRequested -> vm.requestWorkspaceArchive()
                        ChannelListEvent.WorkspaceArchiveConfirmed -> vm.confirmWorkspaceArchive()
                        ChannelListEvent.WorkspaceArchiveDeclined -> vm.declineWorkspaceArchive()
                        // And for creating a channel (#958): the plus's own host and exact cwd.
                        is ChannelListEvent.TreeHostChannelAddTapped -> vm.openCreateChannel(event.serverId)
                        is ChannelListEvent.CreateChannelSubmitted -> vm.submitCreateChannel(event.name, event.systemPrompt)
                        ChannelListEvent.CreateChannelDismissed -> vm.dismissCreateChannel()
                        // And for editing a channel (#667): the pen's own host and conversation.
                        is ChannelListEvent.TreeChannelEditTapped -> vm.openChannelEditor(event.target)
                        is ChannelListEvent.ChannelEditSubmitted -> vm.submitChannelEdit(event.name, event.systemPrompt, event.muted)
                        ChannelListEvent.ChannelArchiveRequested -> vm.archiveChannel()
                        ChannelListEvent.ChannelEditDismissed -> vm.dismissChannelEditor()
                    }
                },
            )
        }
        composable(Routes.DISCUSSION_LIST) {
            val vm = koinViewModel<DiscussionListViewModel>()
            val flatState by vm.state.collectAsStateWithLifecycle()
            val hostState by vm.hostState.collectAsStateWithLifecycle()
            val state =
                (flatState as? DiscussionListUiState.Loaded)?.copy(
                    pendingPromotion = hostState.pendingPromotion?.let { PendingPromotion(it.target.conversationId, it.sourceName) },
                ) ?: flatState
            LaunchedEffect(vm) {
                vm.hostNavigationEvents.collect { navController.openThread(it) }
            }
            DiscussionListScreen(
                state = state,
                onEvent = { event ->
                    when (event) {
                        is DiscussionListEvent.RowTapped ->
                            destinations.selectedServerId()?.let { vm.onHostRowTapped(HostConversationTarget(it, event.conversationId)) }
                        is DiscussionListEvent.SaveAsChannelRequested ->
                            destinations.selectedServerId()?.let {
                                vm.requestHostPromotion(
                                    HostConversationTarget(it, event.conversationId),
                                )
                            }
                        DiscussionListEvent.PromoteConfirmed -> vm.confirmHostPromotion()
                        DiscussionListEvent.PromoteCancelled -> vm.cancelHostPromotion()
                        DiscussionListEvent.BackTapped ->
                            navController.popBackStack()
                    }
                },
            )
        }
        composable(
            route = Routes.CONVERSATION_THREAD,
            arguments = Routes.hostArguments(),
        ) { backStackEntry ->
            val target = Routes.target(backStackEntry.arguments)
            HostDestination(target.serverId, destinations, navController) {
                val vm = koinViewModel<ThreadViewModel>()
                val state by vm.state.collectAsStateWithLifecycle()
                val connectionState by vm.connectionState.collectAsStateWithLifecycle()
                val isThinking by vm.isThinking.collectAsStateWithLifecycle()
                val apiRetry by vm.apiRetry.collectAsStateWithLifecycle()
                val usageLimit by vm.usageLimit.collectAsStateWithLifecycle()
                val resetting by vm.resetting.collectAsStateWithLifecycle()
                val isCompacting by vm.isCompacting.collectAsStateWithLifecycle()
                val turnOutcome by vm.turnOutcome.collectAsStateWithLifecycle()
                val thinkingProgress by vm.thinkingProgress.collectAsStateWithLifecycle()
                val isBusy by vm.isBusy.collectAsStateWithLifecycle()
                // #1311: the band's stall arm and its local-send window.
                val isStalled by vm.isStalled.collectAsStateWithLifecycle()
                val sessionError by vm.sessionError.collectAsStateWithLifecycle()
                val localSendStage by vm.localSendStage.collectAsStateWithLifecycle()
                val modalState by vm.currentModal.collectAsStateWithLifecycle()
                val armedOptionId by vm.armedOptionId.collectAsStateWithLifecycle()
                val alwaysAllowAccepted by vm.alwaysAllowAccepted.collectAsStateWithLifecycle()
                val answerRejected by vm.answerRejected.collectAsStateWithLifecycle()
                val draft by vm.draft.collectAsStateWithLifecycle()
                val systemPrompt by vm.systemPrompt.collectAsStateWithLifecycle()
                val pendingAttachments by vm.pendingAttachments.collectAsStateWithLifecycle()
                val attachmentsSending by vm.attachmentsSending.collectAsStateWithLifecycle()
                val attachmentUploadProgress by vm.attachmentUploadProgress.collectAsStateWithLifecycle()
                val attachmentStates by vm.attachmentStates.collectAsStateWithLifecycle()
                val rePairAvailable by vm.rePairAvailable.collectAsStateWithLifecycle()
                // #1360: the refusal row's way back to the refused model.
                val switchBackOffer by vm.switchBackOffer.collectAsStateWithLifecycle()
                val usageLimitDismissals = koinInject<UsageLimitDismissals>()
                val dismissedUsageLimits by usageLimitDismissals.dismissed.collectAsStateWithLifecycle()
                val mcpFailure by vm.mcpFailure.collectAsStateWithLifecycle()
                // #1635: read live, so turning the setting on or off redraws an open thread.
                val collapseToolUses by appPreferences.collapseToolUses.collectAsStateWithLifecycle(initialValue = true)
                // #1050: composed again means the operator is back on the thread, so a linked note's reader has
                // closed. Its reader remembered the note, so dropping it here cannot empty that reader.
                LaunchedEffect(vm) { vm.releaseLinkedMarkdown() }
                // #1306: leaving this screen, by Back or by opening another thread on top, drops a half-made
                // allow; the ViewModel keeps the session-grant draft for the same request.
                DisposableEffect(vm) { onDispose { vm.onConversationLeft() } }
                LaunchedEffect(vm) {
                    vm.navigationEvents.collect { event ->
                        when (event) {
                            ThreadNavigation.PopBack -> navController.popBackStack()
                            is ThreadNavigation.OpenMarkdown ->
                                navController.navigate(Routes.markdownReader(target, event.attachmentId))
                            ThreadNavigation.OpenLinkedMarkdown -> navController.navigate(Routes.markdownLink(target))
                        }
                    }
                }
                val attention by rememberThreadAttention(
                    target,
                    conversations.snapshots,
                    conversations.attention,
                    conversations.alerts,
                    backStackEntry.lifecycle,
                )
                val questionModal by vm.questionModal.collectAsStateWithLifecycle()
                ThreadScreen(
                    attentionPill = attention?.let { reading -> { ThreadAttentionNotice(reading, navController::openAttentionTarget) } },
                    collapseToolUses = collapseToolUses,
                    questionState = questionModal,
                    onQuestionEvent = { event, generation -> vm.onQuestionEvent(event, generation) },
                    state = state,
                    onBack = { navController.popBackStack() },
                    onSendMessage = vm::sendMessage,
                    connectionState = connectionState,
                    onRetry = vm::retry,
                    isThinking = isThinking,
                    apiRetry = apiRetry,
                    usageLimit = usageLimit,
                    resetting = resetting,
                    isCompacting = isCompacting,
                    turnOutcome = turnOutcome,
                    thinkingProgress = thinkingProgress,
                    isBusy = isBusy,
                    isStalled = isStalled,
                    sessionError = sessionError,
                    localSendStage = localSendStage,
                    onInterrupt = vm::onInterrupt,
                    modalState = modalState,
                    armedOptionId = armedOptionId,
                    newSessionErrors = vm.newSessionErrors,
                    archiveErrors = vm.archiveErrors,
                    changeWorkspaceErrors = vm.changeWorkspaceErrors,
                    sessionSettingsErrors = vm.sessionSettingsErrors,
                    onModalOption = { modalId, optionId -> vm.onModalOption(optionId, modalId) },
                    onModalCancel = { modalId -> vm.onModalCancel(modalId) },
                    alwaysAllowAccepted = alwaysAllowAccepted,
                    onAlwaysAllowChanged = vm::onAlwaysAllowChanged,
                    answerRejected = answerRejected,
                    onDismissAnswerRejection = vm::onAnswerRejectionDismissed,
                    onDropQueued = vm::onDropQueued,
                    onSendQueuedNow = vm::onSendQueuedNow,
                    onOverflowEvent = vm::onOverflowEvent,
                    onModelSelected = vm::onModelSelected,
                    switchBackOffer = switchBackOffer,
                    onSwitchBack = vm::onSwitchBack,
                    onEffortSelected = vm::onEffortSelected,
                    onPermissionModeSelected = vm::onPermissionModeSelected,
                    onComposerCommand = vm::onComposerCommand,
                    onWorkspaceChipTapped = vm::onWorkspaceChipTapped,
                    onWorkspacePicked = vm::onWorkspacePicked,
                    onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
                    onDemandOlderHistory = vm::onDemandOlderHistory,
                    onRetryOlderHistory = vm::onRetryOlderHistory,
                    draft = draft,
                    onDraftChange = vm::onDraftChange,
                    systemPrompt = systemPrompt,
                    // #933: the composer's attachment picker and strip, over the same per-chat draft store.
                    attachments = pendingAttachments,
                    attachmentsSending = attachmentsSending,
                    attachmentUploadProgress = attachmentUploadProgress,
                    onAttachmentsPicked = vm::addPickedAttachments,
                    onRemoveAttachment = vm::removeAttachment,
                    attachmentRefusals = vm.attachmentRefusals,
                    attachmentSendFailures = vm.attachmentSendFailures,
                    // #1314: an accepted send follows the thread's newest end again.
                    sentMessages = vm.sentMessages,
                    // #984: the thread's message attachments, loaded as their rows come on screen.
                    attachmentStates = attachmentStates,
                    onAttachmentShown = vm::onAttachmentShown,
                    onRetryAttachment = vm::onRetryAttachment,
                    // #1329: a file other than an image loads only when it is tapped, then opens or saves.
                    onRequestAttachment = vm::onAttachmentRequested,
                    attachmentLoads = vm.attachmentLoads,
                    // #843: the tree row's re-pair route (#842), keyed by this destination's own host. The
                    // thread stays on the back stack beneath it, so Cancel returns to the cached history.
                    showRePair = rePairAvailable,
                    onRePair = { navController.navigate(Routes.pairCode(target.serverId)) },
                    // #1002: app-scoped, so a usage reading hidden here stays hidden in every thread.
                    dismissedUsageLimits = dismissedUsageLimits,
                    onDismissUsageLimit = usageLimitDismissals::dismiss,
                    // #1345: a failed MCP server's notice; its tap acknowledges and opens Channel info.
                    mcpFailure = mcpFailure,
                    onOpenMcpFailure = vm::onMcpFailureTapped,
                    // #1027: a markdown attachment opens in the in-app reader, or says it could not be read.
                    onOpenMarkdownAttachment = vm::onOpenMarkdownAttachment,
                    markdownOpenFailures = vm.markdownOpenFailures,
                    // #1050: a markdown link in an assistant reply, read live from the workspace.
                    onOpenMarkdownLink = vm::onOpenMarkdownLink,
                )
            }
        }
        // #1027: one markdown attachment of a thread, read in-app. The route carries ids only; the file is
        // resolved through the host's own repository, from the store the thread just read it from.
        composable(
            route = Routes.MARKDOWN_READER,
            arguments = Routes.markdownReaderArguments(),
        ) { backStackEntry ->
            val target = Routes.target(backStackEntry.arguments)
            val attachmentId = Routes.attachmentId(backStackEntry.arguments)
            HostDestination(target.serverId, destinations, navController) {
                val repository = remember(target.serverId) { destinations.repository(target.serverId) }
                MarkdownReaderDestination(
                    repository = repository,
                    conversationId = target.conversationId,
                    attachmentId = attachmentId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        // #1050: a linked workspace note, read live by the thread beneath just before navigating. The route
        // carries the thread's ids only; the note and its path come from that thread's ViewModel, in memory,
        // never in saved state. Only the reader's Refresh (#1067) reads the path again, through this host.
        composable(
            route = Routes.MARKDOWN_LINK,
            arguments = Routes.hostArguments(),
        ) { backStackEntry ->
            val target = Routes.target(backStackEntry.arguments)
            HostDestination(target.serverId, destinations, navController) {
                val threadEntry =
                    remember(backStackEntry) {
                        runCatching { navController.getBackStackEntry(Routes.CONVERSATION_THREAD) }.getOrNull()
                    }
                val threadVm = threadEntry?.let { koinViewModel<ThreadViewModel>(viewModelStoreOwner = it) }
                // Read once: the thread releases its copy when it recomposes during the pop.
                val note = remember(backStackEntry) { threadVm?.linkedMarkdown() }
                val repository = remember(target.serverId) { destinations.repository(target.serverId) }
                LinkedMarkdownReaderDestination(
                    note = note,
                    reread = { path -> readLinkedMarkdown(repository, target.conversationId, path) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        // Keep the optional route for existing entry points; the modal itself has no host content.
        composable(
            route = Routes.SETTINGS,
            arguments = Routes.settingsArguments(),
        ) {
            val vm = koinViewModel<SettingsViewModel>()
            val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()
            val collapseToolUses by vm.collapseToolUses.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.lastHostUnpaired.collect { navController.returnToWelcome() }
            }
            val requestNotifications = rememberNotificationPermissionRequest(appPreferences)
            SettingsScreen(
                pushNotifications = pushNotifications,
                onTogglePushNotifications = { enabled ->
                    vm.onTogglePushNotifications(enabled)
                    if (enabled) requestNotifications()
                },
                collapseToolUses = collapseToolUses,
                onToggleCollapseToolUses = vm::onToggleCollapseToolUses,
                onDismissRequest = { navController.popBackStack() },
            )
        }
        // Wrapped in HostDestination, unlike Settings and like the thread (#715): returning an
        // unknown or newly-unpaired host to the channel list is exactly what keeps one host's
        // Archive from falling back to another's, and the guard already exists. Its check resolves
        // off the registry's saved-host map, so a merely disconnected owner keeps the screen.
        composable(
            route = Routes.ARCHIVED_DISCUSSIONS,
            arguments = Routes.archiveArguments(),
        ) { backStackEntry ->
            HostDestination(Routes.archiveOwner(backStackEntry.arguments), destinations, navController) {
                ArchiveDestination(navController)
            }
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
    // A test build launched with a pairing code opens the pair-code screen over the start screen, so Back
    // returns where a normal launch would have landed.
    LaunchedEffect(pairingPrefill) {
        if (pairingPrefill != null) navController.navigate(Routes.PAIR_CODE)
    }
    // #685: the tap opens the thread above the channel list, so the list is always one Back away. Only a
    // saved host is accepted: the activity is exported, and anything can start it with these extras.
    // Navigating is all a tap ever does.
    // #1400: and only a conversation the host's snapshot holds active. A snapshot cannot tell rows not
    // loaded yet from rows without the target, so the tap waits a bounded time for the row to appear and
    // otherwise stays on the list, never opening a conversation it could not check. A row that arrives
    // after the user has left the list opens nothing.
    LaunchedEffect(openTarget) {
        val target = openTarget ?: return@LaunchedEffect
        if (!destinations.isSavedHost(target.serverId)) {
            RelayLog.d { "event=notification_tap_rejected code=unknown_host" }
            return@LaunchedEffect
        }
        val active = withTimeoutOrNull(NOTIFICATION_TAP_ROW_WAIT) { conversations.snapshots.first { it.holdsActive(target) } }
        when {
            active == null -> RelayLog.d { "event=notification_tap_rejected code=inactive_conversation" }
            // The user moved on during the wait; a late row must not push a thread over where they went.
            navController.currentDestination?.route != Routes.CHANNEL_LIST ->
                RelayLog.d { "event=notification_tap_rejected code=navigated_away" }
            else -> {
                RelayLog.d { "event=notification_tap_accepted" }
                navController.openThread(target)
            }
        }
    }
}

/** Ask only while alerts are on, only without the permission, and only if the app never asked (#685). */
internal fun shouldAskNotificationPermission(
    enabled: Boolean,
    granted: Boolean,
    asked: Boolean,
): Boolean = enabled && !granted && !asked

private fun notificationsPermitted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Shows Android's notification-permission prompt unless it is already granted, recording that the app
 * asked. The answer changes nothing: a denial keeps the saved switch, and foreground use is the same.
 */
@Composable
internal fun rememberNotificationPermissionRequest(preferences: AppPreferences): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            RelayLog.d { "event=notification_permission_answered granted=$granted" }
        }
    return {
        if (!notificationsPermitted(context)) {
            scope.launch { preferences.setNotificationPermissionAsked() }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ArchiveDestination(navController: NavHostController) {
    val vm = koinViewModel<ArchivedDiscussionsViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val hostName by vm.host.collectAsStateWithLifecycle()
    ArchivedDiscussionsScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ArchivedDiscussionsEvent.BackTapped ->
                    navController.popBackStack()
                is ArchivedDiscussionsEvent.RestoreRequested ->
                    vm.onEvent(event)
                is ArchivedDiscussionsEvent.TabSelected ->
                    vm.onEvent(event)
            }
        },
        effects = vm.effects,
        hostName = hostName,
    )
}

private const val SETUP_URL = "https://pyryco.de/setup"

/**
 * How long a notification tap waits for its conversation to appear active in the host's snapshot (#1400).
 * Cached rows land at once, so this bounds only a cold start with nothing cached.
 */
internal val NOTIFICATION_TAP_ROW_WAIT = 5.seconds

private const val TAG = "MainActivity"

// User-facing recovery copy for the Decoded -> Error path (#320). The UI layer owns the copy; the
// parser only emits byte-safe category labels. Generic by design — never interpolates a field value.
private const val PARSE_FAILED_MSG =
    "That QR code isn't a valid pyrycode pairing code. Scan the code shown by `pyry pair`."

internal object Routes {
    const val WELCOME = "welcome"
    const val SCANNER = "scanner"
    const val PAIR_CODE = "pair_code"

    /**
     * The pair-code destination's pattern, with an **optional** target host (#842), shaped like [SETTINGS]:
     * plain [PAIR_CODE] still navigates here with the empty default and pairs whichever host the code names.
     */
    const val PAIR_CODE_ROUTE = "pair_code?serverId={serverId}"
    const val CHANNEL_LIST = "channel_list"
    const val DISCUSSION_LIST = "discussions"
    const val CONVERSATION_THREAD = "conversation_thread/{serverId}/{conversationId}"

    /** A thread's markdown attachment in the reader (#1027): the thread's two ids plus the attachment's. */
    const val MARKDOWN_READER = "markdown_reader/{serverId}/{conversationId}/{attachmentId}"

    /**
     * Owned by a server id alone, and — unlike the two routes above — by an **optional** one (#749):
     * a path segment cannot carry the absent owner an unpaired phone opens Settings with, and this
     * destination must stay open for that case rather than bounce to the list.
     */
    const val SETTINGS = "settings?serverId={serverId}"

    /**
     * Owned by a server id too (#715), but by a **required** path segment rather than the optional
     * query argument above: Settings has to stay open for an unpaired phone, and Archive has no such
     * case. A route that cannot express "no owner" is the cheapest way to keep one being invented.
     */
    const val ARCHIVED_DISCUSSIONS = "archived_discussions/{serverId}"
    const val ABOUT = "about"

    fun thread(target: HostConversationTarget) = "conversation_thread/${Uri.encode(target.serverId)}/${Uri.encode(target.conversationId)}"

    /**
     * A linked workspace note in the reader (#1050): the thread's two ids only. The note's path never travels
     * in the route; the thread's ViewModel holds the note it read.
     */
    const val MARKDOWN_LINK = "markdown_link/{serverId}/{conversationId}"

    /** Per-component encoding, as [thread] uses. Ids only: never a file name, path or URI. */
    fun markdownReader(
        target: HostConversationTarget,
        attachmentId: String,
    ) = "markdown_reader/${Uri.encode(target.serverId)}/${Uri.encode(target.conversationId)}/${Uri.encode(attachmentId)}"

    fun markdownLink(target: HostConversationTarget) = "markdown_link/${Uri.encode(target.serverId)}/${Uri.encode(target.conversationId)}"

    fun markdownReaderArguments() = hostArguments() + navArgument("attachmentId") { type = NavType.StringType }

    fun attachmentId(arguments: Bundle?) = arguments?.getString("attachmentId").orEmpty()

    /** No host to capture yields the bare route, so the argument falls to its empty default. */
    fun settings(serverId: String?) = if (serverId.isNullOrEmpty()) "settings" else "settings?serverId=${Uri.encode(serverId)}"

    fun settingsArguments() =
        listOf(
            navArgument("serverId") {
                type = NavType.StringType
                defaultValue = ""
            },
        )

    /** Re-pairing one host: carries its server id only, never its daemon-authored name. */
    fun pairCode(serverId: String) = "pair_code?serverId=${Uri.encode(serverId)}"

    fun pairCodeArguments() = settingsArguments()

    fun settingsOwner(arguments: Bundle?) = arguments?.getString("serverId").orEmpty()

    /**
     * Per-component encoding, as [thread] uses: a reserved character in a server id has
     * to stay inside its one segment rather than becoming route syntax that could match elsewhere.
     * Callers must hold a non-blank id — a blank one yields a route no destination matches.
     */
    fun archive(serverId: String) = "archived_discussions/${Uri.encode(serverId)}"

    fun archiveArguments() = listOf(navArgument("serverId") { type = NavType.StringType })

    fun archiveOwner(arguments: Bundle?) = arguments?.getString("serverId").orEmpty()

    fun hostArguments() = listOf("serverId", "conversationId").map { name -> navArgument(name) { type = NavType.StringType } }

    fun target(arguments: Bundle?) =
        HostConversationTarget(arguments?.getString("serverId").orEmpty(), arguments?.getString("conversationId").orEmpty())
}

@Composable
private fun HostDestination(
    serverId: String,
    factory: ThreadDestinationFactory,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    val hosts by factory.hostConnections.collectAsStateWithLifecycle()
    val available = remember(serverId, hosts) { factory.hasHost(serverId) }
    LaunchedEffect(serverId, hosts) {
        if (!factory.hasHost(serverId) && !factory.isSavedHost(serverId)) {
            RelayLog.d { "event=host_destination_rejected code=unknown_host" }
            navController.navigate(Routes.CHANNEL_LIST) {
                popUpTo(Routes.CHANNEL_LIST) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    if (available) HostWorkspaceRepository(serverId, factory, content)
}

@Composable
private fun HostWorkspaceRepository(
    serverId: String?,
    factory: ThreadDestinationFactory,
    content: @Composable () -> Unit,
) {
    val repository = remember(factory, serverId) { serverId?.let { factory.repository(it) } }
    CompositionLocalProvider(LocalWorkspacePickerRepository provides repository, content = content)
}

/**
 * Welcome as the only entry, after an unpair left no saved host (#1323): nothing paired stays behind it,
 * so Back leaves the app, as a launch with no host starts there.
 */
private fun NavHostController.returnToWelcome() {
    navigate(Routes.WELCOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Whether [target]'s host holds it among its channels or chats, both of which exclude archived rows. */
private fun List<HostConversationSnapshot>.holdsActive(target: HostConversationTarget): Boolean =
    any { host ->
        host.serverId == target.serverId &&
            (host.channels.any { it.id == target.conversationId } || host.chats.any { it.id == target.conversationId })
    }

private fun NavHostController.openThread(target: HostConversationTarget) {
    if (currentDestination?.route == Routes.CONVERSATION_THREAD && Routes.target(currentBackStackEntry?.arguments) == target) return
    navigate(Routes.thread(target))
}

/** A pill opens an existing route only; permission and command actions remain on their own controls. */
internal fun NavHostController.openAttentionTarget(target: HostConversationTarget?) {
    if (target != null) {
        openThread(target)
    } else {
        navigate(Routes.CHANNEL_LIST) {
            popUpTo(Routes.CHANNEL_LIST)
            launchSingleTop = true
        }
    }
}
