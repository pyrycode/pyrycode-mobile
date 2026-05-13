package de.pyryco.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.ui.conversations.list.ChannelListEvent
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.onboarding.ScannerScreen
import de.pyryco.mobile.ui.onboarding.WelcomeScreen
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PyrycodeMobileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val appPreferences = koinInject<AppPreferences>()
                    val paired: Boolean? by produceState<Boolean?>(
                        initialValue = null,
                        appPreferences,
                    ) {
                        value = appPreferences.pairedServerExists.first()
                    }
                    when (val v = paired) {
                        null -> Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {}
                        else -> PyryNavHost(
                            startDestination = if (v) Routes.ChannelList else Routes.Welcome,
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
        composable(Routes.Welcome) {
            val context = LocalContext.current
            WelcomeScreen(
                onPaired = {
                    navController.navigate(Routes.Scanner)
                },
                onSetup = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SetupUrl)),
                    )
                },
            )
        }
        composable(Routes.Scanner) {
            val appPreferences = koinInject<AppPreferences>()
            val scope = rememberCoroutineScope()
            ScannerScreen(
                onTap = {
                    scope.launch {
                        appPreferences.setPairedServerExists(true)
                        navController.navigate(Routes.ChannelList) {
                            popUpTo(Routes.Scanner) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(Routes.ChannelList) {
            val vm = koinViewModel<ChannelListViewModel>()
            val state by vm.state.collectAsStateWithLifecycle()
            ChannelListScreen(
                state = state,
                onEvent = { event ->
                    when (event) {
                        is ChannelListEvent.RowTapped ->
                            navController.navigate("conversation_thread/${event.conversationId}")
                        ChannelListEvent.SettingsTapped ->
                            navController.navigate(Routes.Settings)
                    }
                },
            )
        }
        composable(
            route = Routes.ConversationThread,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
            Text("Conversation thread placeholder: $conversationId")
        }
        composable(Routes.Settings) {
            SettingsPlaceholder(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun SettingsPlaceholder(onBack: () -> Unit) {
    Column {
        Text("Settings placeholder")
        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

private const val SetupUrl = "https://pyryco.de/setup"

private object Routes {
    const val Welcome = "welcome"
    const val Scanner = "scanner"
    const val ChannelList = "channel_list"
    const val ConversationThread = "conversation_thread/{conversationId}"
    const val Settings = "settings"
}
