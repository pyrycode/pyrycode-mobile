package de.pyryco.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.PairingParseResult
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.parsePairingPayload
import de.pyryco.mobile.data.network.serverKeyFingerprint
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
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
import de.pyryco.mobile.ui.conversations.list.PendingPromotion
import de.pyryco.mobile.ui.conversations.thread.QuestionBatchModal
import de.pyryco.mobile.ui.conversations.thread.ThreadNavigation
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.UsageLimitDismissals
import de.pyryco.mobile.ui.onboarding.CameraPreview
import de.pyryco.mobile.ui.onboarding.PairCodePhase
import de.pyryco.mobile.ui.onboarding.PairCodeScreen
import de.pyryco.mobile.ui.onboarding.PairCodeViewModel
import de.pyryco.mobile.ui.onboarding.ScannerEvent
import de.pyryco.mobile.ui.onboarding.ScannerScreen
import de.pyryco.mobile.ui.onboarding.ScannerUiState
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import de.pyryco.mobile.ui.onboarding.WelcomeScreen
import de.pyryco.mobile.ui.onboarding.confirmPairingAndConnect
import de.pyryco.mobile.ui.settings.AboutScreen
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsEvent
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsScreen
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsViewModel
import de.pyryco.mobile.ui.settings.DEBUG_BUNDLE_FILE_NAME
import de.pyryco.mobile.ui.settings.DEBUG_BUNDLE_MEDIA_TYPE
import de.pyryco.mobile.ui.settings.SettingsScreen
import de.pyryco.mobile.ui.settings.SettingsViewModel
import de.pyryco.mobile.ui.settings.documentArchiveDestination
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // #685: a notification tap's target, read once. A recreated activity keeps its intent, so reading
        // it again after a rotation would re-open the thread over wherever the operator went since.
        val openTarget = if (savedInstanceState == null) NotificationTap.target(intent) else null
        setContent {
            val appPreferences = koinInject<AppPreferences>()
            val pairedServerStore = koinInject<PairedServerCollectionStore>()
            val themeMode by appPreferences.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val useWallpaperColors by appPreferences.useWallpaperColors
                .collectAsStateWithLifecycle(initialValue = false)
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            PyrycodeMobileTheme(darkTheme = darkTheme, dynamicColor = useWallpaperColors) {
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
                                modifier = Modifier.padding(innerPadding),
                                openTarget = openTarget.takeIf { v },
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
) {
    val destinations = koinInject<ThreadDestinationFactory>()
    val appPreferences = koinInject<AppPreferences>()
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
            val pairedServerStore = koinInject<PairedServerStore>()
            val connectionController = koinInject<RelayConnectionController>()
            val scope = rememberCoroutineScope()
            val vm = koinViewModel<ScannerViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()

            // Camera confirmation retains its existing save/connect path.
            val confirmPairAndNavigate: (PairedServer) -> Unit = { server ->
                scope.launch {
                    confirmPairingAndConnect(
                        server = server,
                        store = pairedServerStore,
                        controller = connectionController,
                        onPersisted = {
                            navController.navigate(Routes.CHANNEL_LIST) {
                                popUpTo(Routes.SCANNER) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onFailed = { e ->
                            Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}")
                            vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG))
                        },
                    )
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
            // (confirmPairAndNavigate); this effect never touches the store. A parse failure, or a
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
                // Confirm reads the CURRENT collected state: if back/decline already moved it off
                // AwaitingConfirm, the cast is null and confirm is a no-op — a save cannot fire after
                // the gate closed. Persists exactly the parsed record the displayed fingerprint was
                // derived from (no re-parse / re-derive).
                onConfirmPairing = {
                    (state as? ScannerUiState.AwaitingConfirm)?.let { confirmPairAndNavigate(it.server) }
                },
                onDeclinePairing = { vm.onEvent(ScannerEvent.DeclinePairing) },
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
                        // Same rule as a row tap, now for creation: the control's own host, never the
                        // selected-host adapter the retired button resolved through (#738).
                        is ChannelListEvent.TreeHostAddTapped -> vm.createHostDiscussion(event.serverId)
                        // Held, the same control opens Add workspace on its own host (#904).
                        is ChannelListEvent.TreeHostAddLongPressed -> vm.openAddWorkspace(event.serverId)
                        // Same rule again for editing (#744): the control's own host. The view model
                        // reads that host's stored record and owns the modal's target and flags.
                        is ChannelListEvent.TreeHostEditTapped -> vm.openHostEditor(event.serverId)
                        // And for reconnecting (#840): the control's own host, retried alone.
                        is ChannelListEvent.TreeHostReconnectTapped -> vm.reconnectHost(event.serverId)
                        // Unless its pairing was rejected (#842): then the control re-pairs that host alone.
                        is ChannelListEvent.TreeHostRePairTapped ->
                            navController.navigate(Routes.pairCode(event.serverId))
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
                        is ChannelListEvent.TreeWorkspaceEditTapped -> vm.openWorkspaceEditor(event.serverId, event.cwd)
                        is ChannelListEvent.WorkspaceEditNameSubmitted -> vm.submitWorkspaceName(event.name)
                        ChannelListEvent.WorkspaceEditDismissed -> vm.dismissWorkspaceEditor()
                        ChannelListEvent.WorkspaceArchiveRequested -> vm.requestWorkspaceArchive()
                        ChannelListEvent.WorkspaceArchiveConfirmed -> vm.confirmWorkspaceArchive()
                        ChannelListEvent.WorkspaceArchiveDeclined -> vm.declineWorkspaceArchive()
                        // And for creating a channel (#958): the plus's own host and exact cwd.
                        is ChannelListEvent.TreeWorkspaceAddTapped -> vm.openCreateChannel(event.serverId, event.cwd)
                        is ChannelListEvent.CreateChannelSubmitted -> vm.submitCreateChannel(event.name, event.systemPrompt)
                        ChannelListEvent.CreateChannelDismissed -> vm.dismissCreateChannel()
                        // And for editing a channel (#667): the pen's own host and conversation.
                        is ChannelListEvent.TreeChannelEditTapped -> vm.openChannelEditor(event.target)
                        is ChannelListEvent.ChannelEditSubmitted -> vm.submitChannelEdit(event.name, event.systemPrompt)
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
                val modalState by vm.currentModal.collectAsStateWithLifecycle()
                val armedOptionId by vm.armedOptionId.collectAsStateWithLifecycle()
                val alwaysAllowAccepted by vm.alwaysAllowAccepted.collectAsStateWithLifecycle()
                val draft by vm.draft.collectAsStateWithLifecycle()
                val pendingAttachments by vm.pendingAttachments.collectAsStateWithLifecycle()
                val attachmentsSending by vm.attachmentsSending.collectAsStateWithLifecycle()
                val attachmentStates by vm.attachmentStates.collectAsStateWithLifecycle()
                val rePairAvailable by vm.rePairAvailable.collectAsStateWithLifecycle()
                val usageLimitDismissals = koinInject<UsageLimitDismissals>()
                val dismissedUsageLimits by usageLimitDismissals.dismissed.collectAsStateWithLifecycle()
                LaunchedEffect(vm) {
                    vm.navigationEvents.collect { event ->
                        when (event) {
                            ThreadNavigation.PopBack -> navController.popBackStack()
                        }
                    }
                }
                ThreadScreen(
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
                    onInterrupt = vm::onInterrupt,
                    modalState = modalState,
                    armedOptionId = armedOptionId,
                    modalSendErrors = vm.modalSendErrors,
                    newSessionErrors = vm.newSessionErrors,
                    archiveErrors = vm.archiveErrors,
                    changeWorkspaceErrors = vm.changeWorkspaceErrors,
                    sessionSettingsErrors = vm.sessionSettingsErrors,
                    onModalOption = vm::onModalOption,
                    onModalCancel = vm::onModalCancel,
                    alwaysAllowAccepted = alwaysAllowAccepted,
                    onAlwaysAllowChanged = vm::onAlwaysAllowChanged,
                    onDropQueued = vm::onDropQueued,
                    onOverflowEvent = vm::onOverflowEvent,
                    onModelSelected = vm::onModelSelected,
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
                    // #933: the composer's attachment picker and strip, over the same per-chat draft store.
                    attachments = pendingAttachments,
                    attachmentsSending = attachmentsSending,
                    onAttachmentsPicked = vm::addPickedAttachments,
                    onRemoveAttachment = vm::removeAttachment,
                    attachmentRefusals = vm.attachmentRefusals,
                    // #984: the thread's message attachments, loaded as their rows come on screen.
                    attachmentStates = attachmentStates,
                    onAttachmentShown = vm::onAttachmentShown,
                    onRetryAttachment = vm::onRetryAttachment,
                    // #843: the tree row's re-pair route (#842), keyed by this destination's own host. The
                    // thread stays on the back stack beneath it, so Cancel returns to the cached history.
                    showRePair = rePairAvailable,
                    onRePair = { navController.navigate(Routes.pairCode(target.serverId)) },
                    // #1002: app-scoped, so a usage reading hidden here stays hidden in every thread.
                    dismissedUsageLimits = dismissedUsageLimits,
                    onDismissUsageLimit = usageLimitDismissals::dismiss,
                )
                // #661: its own gate window, so it is drawn beside the screen rather than threaded through it.
                val questionModal by vm.questionModal.collectAsStateWithLifecycle()
                questionModal?.let { QuestionBatchModal(state = it, onEvent = vm::onQuestionEvent) }
            }
        }
        // Deliberately not wrapped in HostDestination: that guard returns an unknown host to the
        // channel list, and this destination has to stay open for one instead — Settings is where an
        // unpaired or newly-unpaired phone goes to pair (#749).
        composable(
            route = Routes.SETTINGS,
            arguments = Routes.settingsArguments(),
        ) { backStackEntry ->
            // Archive inherits this destination's own captured owner (#715), read back from the
            // route rather than from selection, so the archive opened is the one this screen's
            // count describes. A destination owning no host passes null, which draws the row inert
            // rather than offering a tap that could only be rejected — and keeps a blank id, which
            // matches no destination, out of `Routes.archive`.
            val settingsOwner = Routes.settingsOwner(backStackEntry.arguments)
            val vm = koinViewModel<SettingsViewModel>()
            val connection by vm.connection.collectAsStateWithLifecycle()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
            val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
            val defaultModel by vm.defaultModel.collectAsStateWithLifecycle()
            val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
            val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()
            val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()
            val defaultWorkspace by vm.defaultWorkspace.collectAsStateWithLifecycle()
            // Resolved against this destination's own host's conversations (#723); the row renders
            // the two through the shared display rule, and the path above stays the stored one.
            val defaultWorkspaceLabel by vm.defaultWorkspaceLabel.collectAsStateWithLifecycle()
            // One value drives both the picker's repository and whether it is on screen at all
            // (#714), the way the flat list already drives its own picker: the sheet cannot be
            // visible without a host bound, so it can never fall back to the compatibility
            // repository and show — or create a folder on — whichever host was selected last.
            val workspacePickerOwner by vm.workspacePickerServerId.collectAsStateWithLifecycle()
            // The editor this destination opens on its own host (#751), driven by the same machine
            // the channel list drives — the view model holds its own instance of it, not a shared one.
            val hostEditor by vm.hostEditor.collectAsStateWithLifecycle()
            // The Log data download (#683). The picker is a document-creation contract rather than a
            // path: the operator names the destination, the app never builds one, and the suggested
            // name and media type are fixed constants no daemon field can influence.
            val logData by vm.logDataDownload.collectAsStateWithLifecycle()
            val resolver = LocalContext.current.contentResolver
            val requestNotifications = rememberNotificationPermissionRequest(appPreferences)
            val archiveLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(DEBUG_BUNDLE_MEDIA_TYPE),
                ) { uri ->
                    // A null Uri is a cancelled picker, which the controller reports without
                    // touching the archive it is still holding for the retry.
                    vm.onLogArchiveDestination(uri?.let { documentArchiveDestination(resolver, it) })
                }
            HostWorkspaceRepository(workspacePickerOwner, destinations) {
                SettingsScreen(
                    connection = connection,
                    themeMode = themeMode,
                    useWallpaperColors = useWallpaperColors,
                    archivedDiscussionCount = archivedDiscussionCount,
                    defaultModel = defaultModel,
                    defaultEffort = defaultEffort,
                    defaultYolo = defaultYolo,
                    pushNotifications = pushNotifications,
                    defaultWorkspace = defaultWorkspace,
                    defaultWorkspaceLabel = defaultWorkspaceLabel,
                    // Read off the picker's own target, as the flat channel screen reads off its.
                    workspacePickerVisible = workspacePickerOwner != null,
                    onSelectTheme = vm::onSelectTheme,
                    onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
                    onSelectDefaultModel = vm::onSelectDefaultModel,
                    onSelectDefaultEffort = vm::onSelectDefaultEffort,
                    onToggleDefaultYolo = vm::onToggleDefaultYolo,
                    onTogglePushNotifications = { enabled ->
                        vm.onTogglePushNotifications(enabled)
                        if (enabled) requestNotifications()
                    },
                    onDefaultWorkspaceTapped = vm::onDefaultWorkspaceTapped,
                    onSelectDefaultWorkspace = vm::onSelectDefaultWorkspace,
                    onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
                    // Host-to-host is lateral movement between two instances of one destination, not
                    // descent, so the hop replaces this entry instead of stacking on it (#750): Back
                    // from any host's Settings returns to the list it was opened from, and hopping
                    // between two hosts cannot grow the stack a tap at a time. Returning to the host
                    // left behind costs one tap on a row that is still on screen.
                    //
                    // Not launchSingleTop: that reuses this NavBackStackEntry, so the ViewModel — and
                    // the owner it captured at creation — would survive while the arguments changed
                    // underneath it. popUpTo-inclusive destroys the entry, which is what makes the new
                    // capture real.
                    onOpenHost = { serverId ->
                        navController.navigate(Routes.settings(serverId)) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    },
                    hostEditor = hostEditor,
                    // The owner's row is the only caller, and the view model opens on the id this
                    // destination captured — never on a row's own id and never on selection (#751).
                    // Nothing here reacts to the removal that follows a confirmation: the host list
                    // re-emits without it, this destination stays put and falls back to the copy #750
                    // already ships for an owner that is no longer paired.
                    onEditHost = vm::openOwnerHostEditor,
                    onEditHostNameSubmitted = vm::submitHostName,
                    onHostUnpairRequested = vm::requestHostUnpair,
                    onHostUnpairConfirmed = vm::confirmHostUnpair,
                    onHostUnpairDeclined = vm::declineHostUnpair,
                    onEditHostDismissed = vm::dismissHostEditor,
                    // The same destination the channel list's own pairing entry opens (#738), so an
                    // unpaired phone has a working way out of this screen's no-host state.
                    onPairServer = { navController.navigate(Routes.SCANNER) },
                    onBack = { navController.popBackStack() },
                    onOpenArchivedDiscussions =
                        settingsOwner.takeIf { it.isNotEmpty() }?.let { owner ->
                            { navController.navigate(Routes.archive(owner)) }
                        },
                    // Null for a destination owning no host, exactly as the Archive row above is
                    // (#715): there is no host to ask, so the row offers no tap rather than one
                    // the view model could only reject. The view model keeps its own guard anyway.
                    onOpenLogData = settingsOwner.takeIf { it.isNotEmpty() }?.let { { vm.openLogData() } },
                    logData = logData,
                    onLogDataRequested = vm::requestLogArchive,
                    onLogDataSaveRequested = { archiveLauncher.launch(DEBUG_BUNDLE_FILE_NAME) },
                    onLogDataDismissed = vm::dismissLogData,
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
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
    // #685: the tap opens the thread above the channel list, so a conversation deleted since the alert
    // still ends one Back away from a usable list. Only a saved host is accepted: the activity is
    // exported, and anything can start it with these extras. Navigating is all a tap ever does.
    LaunchedEffect(openTarget) {
        val target = openTarget ?: return@LaunchedEffect
        if (destinations.isSavedHost(target.serverId)) {
            RelayLog.d { "event=notification_tap_accepted" }
            navController.openThread(target)
        } else {
            RelayLog.d { "event=notification_tap_rejected code=unknown_host" }
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

private const val TAG = "MainActivity"

// User-facing recovery copy for the Decoded -> Error path (#320). The UI layer owns the copy; the
// parser only emits byte-safe category labels. Generic by design — never interpolates a field value.
private const val PARSE_FAILED_MSG =
    "That QR code isn't a valid pyrycode pairing code. Scan the code shown by `pyry pair`."

private const val SAVE_FAILED_MSG = "Couldn't save the pairing. Please try again."

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

private fun NavHostController.openThread(target: HostConversationTarget) {
    if (currentDestination?.route == Routes.CONVERSATION_THREAD && Routes.target(currentBackStackEntry?.arguments) == target) return
    navigate(Routes.thread(target))
}
