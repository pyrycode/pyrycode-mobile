package de.pyryco.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import de.pyryco.mobile.ui.onboarding.ScannerScreen
import de.pyryco.mobile.ui.onboarding.WelcomeScreen
import de.pyryco.mobile.ui.settings.LicenseScreen
import de.pyryco.mobile.ui.settings.SettingsScreen
import de.pyryco.mobile.ui.settings.SettingsViewModel
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
        setContent {
            val appPreferences = koinInject<AppPreferences>()
            val themeMode by appPreferences.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            PyrycodeMobileTheme(darkTheme = darkTheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val paired: Boolean? by produceState<Boolean?>(
                        initialValue = null,
                        appPreferences,
                    ) {
                        value = appPreferences.pairedServerExists.first()
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
            val appPreferences = koinInject<AppPreferences>()
            val scope = rememberCoroutineScope()
            ScannerScreen(
                onTap = {
                    scope.launch {
                        appPreferences.setPairedServerExists(true)
                        navController.navigate(Routes.CHANNEL_LIST) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                            launchSingleTop = true
                        }
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
                        ChannelListEvent.CreateDiscussionTapped ->
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
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
            Text("Conversation thread placeholder: $conversationId")
        }
        composable(Routes.SETTINGS) {
            val vm = koinViewModel<SettingsViewModel>()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            SettingsScreen(
                themeMode = themeMode,
                onSelectTheme = vm::onSelectTheme,
                onBack = { navController.popBackStack() },
                onOpenLicense = { navController.navigate(Routes.LICENSE) },
            )
        }
        composable(Routes.LICENSE) {
            LicenseScreen(onBack = { navController.popBackStack() })
        }
    }
}

private const val SETUP_URL = "https://pyryco.de/setup"

private object Routes {
    const val WELCOME = "welcome"
    const val SCANNER = "scanner"
    const val CHANNEL_LIST = "channel_list"
    const val DISCUSSION_LIST = "discussions"
    const val CONVERSATION_THREAD = "conversation_thread/{conversationId}"
    const val SETTINGS = "settings"
    const val LICENSE = "license"
}
