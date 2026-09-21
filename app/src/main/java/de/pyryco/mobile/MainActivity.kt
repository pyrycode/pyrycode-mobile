package de.pyryco.mobile

import android.Manifest
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
import de.pyryco.mobile.ui.conversations.thread.LiteralScreenSurface
import de.pyryco.mobile.ui.conversations.thread.LiteralScreenViewModel
import de.pyryco.mobile.ui.conversations.thread.ThreadNavigation
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
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
import de.pyryco.mobile.ui.settings.SettingsScreen
import de.pyryco.mobile.ui.settings.SettingsViewModel
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
) {
    val destinations = koinInject<ThreadDestinationFactory>()
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
        composable(Routes.PAIR_CODE) {
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
            LaunchedEffect(vm) {
                vm.hostNavigationEvents.collect { navController.openThread(it) }
            }
            HostWorkspaceRepository(hostState.workspacePickerServerId, destinations) {
                ChannelListScreen(
                    hostState = hostState,
                    onEvent = { event ->
                        when (event) {
                            // The row carries its own host: the tree draws rows from every host, so the
                            // selected-host adapter would open the wrong one (#731).
                            is ChannelListEvent.TreeRowTapped -> vm.onHostRowTapped(event.target)
                            is ChannelListEvent.TreeFoldToggled -> vm.onFoldToggled(event.key)
                            ChannelListEvent.SettingsTapped ->
                                navController.navigate(Routes.SETTINGS)
                            // One destination, two doors: Settings' archived-discussions row opens the same
                            // route (#737). Rebinding it to a specific host is #715.
                            ChannelListEvent.ArchiveTapped ->
                                navController.navigate(Routes.ARCHIVED_DISCUSSIONS)
                            // Pairing's existing entry, reused rather than a second flow (#738): both of its
                            // completions already land back here — the camera path pops SCANNER inclusive
                            // onto this very entry, the paste-code path pops the graph.
                            ChannelListEvent.PairHostTapped ->
                                navController.navigate(Routes.SCANNER)
                            // Same rule as a row tap, now for creation: the control's own host, never the
                            // selected-host adapter the retired button resolved through (#738).
                            is ChannelListEvent.TreeHostAddTapped -> vm.createHostDiscussion(event.serverId)
                            is ChannelListEvent.TreeHostAddLongPressed -> vm.openHostWorkspacePicker(event.serverId)
                            // Same rule again for editing (#744): the control's own host. The view model
                            // reads that host's stored record and owns the modal's target and flags.
                            is ChannelListEvent.TreeHostEditTapped -> vm.openHostEditor(event.serverId)
                            is ChannelListEvent.HostEditNameSubmitted -> vm.submitHostName(event.name)
                            ChannelListEvent.HostEditDismissed -> vm.dismissHostEditor()
                            is ChannelListEvent.WorkspacePicked -> vm.pickHostWorkspace(event.workspace)
                            ChannelListEvent.WorkspacePickerDismissed -> vm.dismissHostWorkspacePicker()
                        }
                    },
                )
            }
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
                val isStalled by vm.isStalled.collectAsStateWithLifecycle()
                val apiRetry by vm.apiRetry.collectAsStateWithLifecycle()
                val isCompacting by vm.isCompacting.collectAsStateWithLifecycle()
                val isBusy by vm.isBusy.collectAsStateWithLifecycle()
                val modalState by vm.currentModal.collectAsStateWithLifecycle()
                val armedOptionId by vm.armedOptionId.collectAsStateWithLifecycle()
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
                    isStalled = isStalled,
                    apiRetry = apiRetry,
                    isCompacting = isCompacting,
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
                    onDropQueued = vm::onDropQueued,
                    onOverflowEvent = vm::onOverflowEvent,
                    onShowLiteralScreen = { navController.navigate(Routes.literal(target)) },
                    onModelSelected = vm::onModelSelected,
                    onEffortSelected = vm::onEffortSelected,
                    onYoloToggled = vm::onYoloToggled,
                    onWorkspaceChipTapped = vm::onWorkspaceChipTapped,
                    onWorkspacePicked = vm::onWorkspacePicked,
                    onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
                )
            }
        }
        composable(
            route = Routes.LITERAL_SCREEN,
            arguments = Routes.hostArguments(),
        ) { backStackEntry ->
            HostDestination(Routes.target(backStackEntry.arguments).serverId, destinations, navController) {
                val vm = koinViewModel<LiteralScreenViewModel>()
                val state by vm.state.collectAsStateWithLifecycle()
                LiteralScreenSurface(
                    state = state,
                    onEvent = vm::onEvent,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.SETTINGS) {
            val vm = koinViewModel<SettingsViewModel>()
            val pairedServerStore = koinInject<PairedServerStore>()
            var serverLabel by remember { mutableStateOf("…") }
            LaunchedEffect(Unit) {
                serverLabel =
                    pairedServerStore.load()?.let { "${it.serverId.take(12)} · ${it.relayUrl}" }
                        ?: "Not paired"
            }
            val connectionStatus by vm.connectionStatus.collectAsStateWithLifecycle()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
            val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
            val defaultModel by vm.defaultModel.collectAsStateWithLifecycle()
            val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
            val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()
            val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()
            val defaultWorkspace by vm.defaultWorkspace.collectAsStateWithLifecycle()
            val workspacePickerVisible by vm.workspacePickerVisible.collectAsStateWithLifecycle()
            SettingsScreen(
                connectionStatus = connectionStatus,
                serverLabel = serverLabel,
                themeMode = themeMode,
                useWallpaperColors = useWallpaperColors,
                archivedDiscussionCount = archivedDiscussionCount,
                defaultModel = defaultModel,
                defaultEffort = defaultEffort,
                defaultYolo = defaultYolo,
                pushNotifications = pushNotifications,
                defaultWorkspace = defaultWorkspace,
                workspacePickerVisible = workspacePickerVisible,
                onSelectTheme = vm::onSelectTheme,
                onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
                onSelectDefaultModel = vm::onSelectDefaultModel,
                onSelectDefaultEffort = vm::onSelectDefaultEffort,
                onToggleDefaultYolo = vm::onToggleDefaultYolo,
                onTogglePushNotifications = vm::onTogglePushNotifications,
                onDefaultWorkspaceTapped = vm::onDefaultWorkspaceTapped,
                onSelectDefaultWorkspace = vm::onSelectDefaultWorkspace,
                onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
                onBack = { navController.popBackStack() },
                onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ARCHIVED_DISCUSSIONS) {
            val vm = koinViewModel<ArchivedDiscussionsViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()
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
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
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
    const val CHANNEL_LIST = "channel_list"
    const val DISCUSSION_LIST = "discussions"
    const val CONVERSATION_THREAD = "conversation_thread/{serverId}/{conversationId}"
    const val LITERAL_SCREEN = "literal_screen/{serverId}/{conversationId}"
    const val SETTINGS = "settings"
    const val ARCHIVED_DISCUSSIONS = "archived_discussions"
    const val ABOUT = "about"

    fun thread(target: HostConversationTarget) = "conversation_thread/${Uri.encode(target.serverId)}/${Uri.encode(target.conversationId)}"

    fun literal(target: HostConversationTarget) = "literal_screen/${Uri.encode(target.serverId)}/${Uri.encode(target.conversationId)}"

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
