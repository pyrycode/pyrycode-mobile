package de.pyryco.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.pyryco.mobile.ui.onboarding.WelcomeScreen
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PyrycodeMobileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PyryNavHost(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun PyryNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.Welcome,
        modifier = modifier,
    ) {
        composable(Routes.Welcome) {
            WelcomeScreen(
                onPaired = {
                    navController.navigate(Routes.ChannelList)
                },
                onSetup = {
                    // TODO(#14): launch external browser intent to pyrycode install docs.
                },
            )
        }
        composable(Routes.ChannelList) {
            Text("Channel list placeholder")
        }
    }
}

private object Routes {
    const val Welcome = "welcome"
    const val ChannelList = "channel_list"
}
