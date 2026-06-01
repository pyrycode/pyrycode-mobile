package de.pyryco.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.network.PairingParseResult
import de.pyryco.mobile.data.network.parsePairingPayload
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.ui.conversations.list.ChannelListEvent
import de.pyryco.mobile.ui.conversations.list.ChannelListNavigation
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.conversations.list.DiscussionListEvent
import de.pyryco.mobile.ui.conversations.list.DiscussionListNavigation
import de.pyryco.mobile.ui.conversations.list.DiscussionListScreen
import de.pyryco.mobile.ui.conversations.list.DiscussionListViewModel
import de.pyryco.mobile.ui.conversations.thread.ThreadNavigation
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.onboarding.CameraPreview
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
            val pairedServerStore = koinInject<PairedServerStore>()
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
                    ) {
                        value = pairedServerStore.load() != null
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
private fun PyryNavHost(
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
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
            val scope = rememberCoroutineScope()
            val vm = koinViewModel<ScannerViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()

            // Stub-pair-and-navigate, unchanged from the Phase-0 stub: persist the throwaway
            // PairedServer and advance to the channel list; on store failure stay put so the user
            // can re-tap. Not routed through the VM Error state (preserves #295 behavior; the
            // QR-scanning ticket owns real PairedServer handling).
            val stubPairAndNavigate: () -> Unit = {
                scope.launch {
                    try {
                        pairedServerStore.save(STUB_PAIRED_SERVER)
                        navController.navigate(Routes.CHANNEL_LIST) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                            launchSingleTop = true
                        }
                    } catch (e: PairedServerStoreException) {
                        Log.w(TAG, "stub paired-server save failed: ${e.javaClass.simpleName}")
                    }
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

            // A successful decode parses + validates the payload into a real PairedServer (#320),
            // persists it, then advances to the channel list — replacing the former stub write at
            // this binding. On Success the navigate's popUpTo(inclusive) pops the scanner, so the
            // effect cannot re-fire; on a parse/persist failure the VM flips Decoded -> Error and
            // this effect re-runs as a no-op (state is no longer Decoded). The parse is microsecond
            // CPU work on a small string, so it runs inline on this Main coroutine.
            LaunchedEffect(state) {
                val decoded = state as? ScannerUiState.Decoded ?: return@LaunchedEffect
                when (val result = parsePairingPayload(decoded.payload)) {
                    is PairingParseResult.Success ->
                        try {
                            pairedServerStore.save(result.server)
                            navController.navigate(Routes.CHANNEL_LIST) {
                                popUpTo(Routes.SCANNER) { inclusive = true }
                                launchSingleTop = true
                            }
                        } catch (e: PairedServerStoreException) {
                            Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}")
                            vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG))
                        }
                    is PairingParseResult.Failure -> {
                        Log.w(TAG, "pairing parse failed: ${result.reason}")
                        vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))
                    }
                }
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
                onPasteCode = stubPairAndNavigate,
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
        composable(Routes.CHANNEL_LIST) {
            val vm = koinViewModel<ChannelListViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.navigationEvents.collect { event ->
                    when (event) {
                        is ChannelListNavigation.ToThread ->
                            navController.navigate("conversation_thread/${event.conversationId}")
                    }
                }
            }
            ChannelListScreen(
                state = state,
                onEvent = { event ->
                    when (event) {
                        is ChannelListEvent.RowTapped ->
                            navController.navigate("conversation_thread/${event.conversationId}")
                        ChannelListEvent.SettingsTapped ->
                            navController.navigate(Routes.SETTINGS)
                        ChannelListEvent.RecentDiscussionsTapped ->
                            navController.navigate(Routes.DISCUSSION_LIST)
                        ChannelListEvent.CreateDiscussionTapped,
                        ChannelListEvent.LongPressFab,
                        is ChannelListEvent.WorkspacePicked,
                        ChannelListEvent.WorkspacePickerDismissed,
                        ->
                            vm.onEvent(event)
                    }
                },
            )
        }
        composable(Routes.DISCUSSION_LIST) {
            val vm = koinViewModel<DiscussionListViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.navigationEvents.collect { event ->
                    when (event) {
                        is DiscussionListNavigation.ToThread ->
                            navController.navigate("conversation_thread/${event.conversationId}")
                    }
                }
            }
            DiscussionListScreen(
                state = state,
                onEvent = { event ->
                    when (event) {
                        is DiscussionListEvent.RowTapped ->
                            navController.navigate("conversation_thread/${event.conversationId}")
                        is DiscussionListEvent.SaveAsChannelRequested ->
                            vm.onEvent(event)
                        DiscussionListEvent.PromoteConfirmed ->
                            vm.onEvent(event)
                        DiscussionListEvent.PromoteCancelled ->
                            vm.onEvent(event)
                        DiscussionListEvent.BackTapped ->
                            navController.popBackStack()
                    }
                },
            )
        }
        composable(
            route = Routes.CONVERSATION_THREAD,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            val vm = koinViewModel<ThreadViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()
            val connectionState by vm.connectionState.collectAsStateWithLifecycle()
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
                onOverflowEvent = vm::onOverflowEvent,
                onModelSelected = vm::onModelSelected,
                onEffortSelected = vm::onEffortSelected,
                onYoloToggled = vm::onYoloToggled,
                onWorkspaceChipTapped = vm::onWorkspaceChipTapped,
                onWorkspacePicked = vm::onWorkspacePicked,
                onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
            )
        }
        composable(Routes.SETTINGS) {
            val vm = koinViewModel<SettingsViewModel>()
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

// Placeholder paired-server record persisted by the Scanner stub tap until QR pairing lands; the
// downstream QR-scanning ticket overwrites it with the scanned record (last-writer-wins). These are
// wire-shaped throwaways, not real credentials: the relay is non-routable (`.invalid`, RFC 2606)
// and the token / static pubkey are public constants that grant no access. The store does no
// field-shape validation, so the exact bytes are non-load-bearing — they only keep the persisted
// record structurally indistinguishable from a real one for the dormant #275/#276 consumers.
private val STUB_PAIRED_SERVER =
    PairedServer(
        serverId = "placeholder-server",
        token = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        relayUrl = "wss://relay.invalid/v1/client",
        serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    )

private object Routes {
    const val WELCOME = "welcome"
    const val SCANNER = "scanner"
    const val CHANNEL_LIST = "channel_list"
    const val DISCUSSION_LIST = "discussions"
    const val CONVERSATION_THREAD = "conversation_thread/{conversationId}"
    const val SETTINGS = "settings"
    const val ARCHIVED_DISCUSSIONS = "archived_discussions"
    const val ABOUT = "about"
}
