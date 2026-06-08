package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the route template + arg-name contract that [LiteralScreenViewModel] depends on: a
 * [NavType.StringType] argument named exactly `conversationId` must survive `navigate(...)` so the
 * destination's back-stack-entry [androidx.lifecycle.SavedStateHandle] seeds the VM. This guards the
 * AC#2/#3 routing half without depending on `MainActivity` (its `PyryNavHost` is private + Koin-bound);
 * it tests a copy of the route string under a minimal NavHost, matching the repo's existing inline-route
 * idiom. The per-back-stack-entry VM-freshness guarantee itself is a framework property covered by code
 * review, not asserted here.
 */
@RunWith(AndroidJUnit4::class)
class LiteralScreenNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun navigating_to_literal_screen_carries_conversation_id_on_a_dedicated_destination() {
        lateinit var navController: NavHostController
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "start") {
                composable("start") { Text("start") }
                composable(
                    route = "literal_screen/{conversationId}",
                    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
                ) {
                    Text("literal")
                }
            }
        }

        composeTestRule.runOnUiThread {
            navController.navigate("literal_screen/conv-42")
        }
        composeTestRule.waitForIdle()

        val entry = navController.currentBackStackEntry
        assertEquals("literal_screen/{conversationId}", entry?.destination?.route)
        assertEquals("conv-42", entry?.arguments?.getString("conversationId"))
    }
}
