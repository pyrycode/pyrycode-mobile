package de.pyryco.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import java.util.Base64

/** Two activity lifetimes in one process expose the native fixture's persistent Koin cleanup. */
@RunWith(AndroidJUnit4::class)
class AnswerHostSetupCleanupTest {
    @Test
    fun targetedPairingAfterFixtureTeardownKeepsItsHostGuard() {
        val description = Description.createTestDescription(javaClass, "targetedPairingAfterFixtureTeardownKeepsItsHostGuard")
        val setup = AnswerHostSetupTest()
        RuleChain
            .outerRule(setup.fixture)
            .around(setup.compose)
            .apply(
                object : Statement() {
                    override fun evaluate() = setup.offlinePrecedingHostDoesNotBlockAnswerHostPairing()
                },
                description,
            ).evaluate()

        // Both the fixture and its Activity have finished; use the restored graph without overriding it.
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val before = runBlocking { store.list() }
        val compose = createAndroidComposeRule<ComponentActivity>()
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val code =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"server":"different-host-1899","token":"fixture-token","relay":"wss://relay.example","server_static_pubkey":"$key"}"""
                    .toByteArray(),
            )
        compose
            .apply(
                object : Statement() {
                    override fun evaluate() {
                        lateinit var nav: NavHostController
                        compose.setContent {
                            PyrycodeMobileTheme {
                                nav = rememberNavController()
                                PyryNavHost(Routes.WELCOME, navController = nav)
                            }
                        }
                        compose.runOnIdle { nav.navigate(Routes.pairCode("unsaved/host id")) }
                        compose.onNodeWithContentDescription("Host name").assertTextContains("unsaved/host id").assertIsNotEnabled()
                        compose.onNodeWithContentDescription("Clear host name").assertDoesNotExist()
                        compose.onNodeWithContentDescription("Pairing code").performTextInput(code)
                        compose.onNodeWithText("Pair").performScrollTo().performClick()
                        compose.onNodeWithText(WRONG_HOST_ERROR).assertIsDisplayed()
                        compose.onNodeWithText("Confirm pairing").assertDoesNotExist()
                        assertEquals(before, runBlocking { store.list() })
                        compose.onNodeWithContentDescription("Back").performClick()
                        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.WELCOME }

                        compose.runOnIdle { nav.navigate(Routes.pairCode("")) }
                        compose.onNodeWithContentDescription("Host name").assertIsEnabled().performTextInput("New host")
                        compose.onNodeWithContentDescription("Host name").assertTextContains("New host")
                        compose.onNodeWithContentDescription("Pairing code").performTextInput(code)
                        compose.onNodeWithText("Pair").performScrollTo().performClick()
                        compose.onNodeWithText("Confirm pairing").assertIsDisplayed()
                        Espresso.pressBack()
                        compose.onNodeWithContentDescription("Back").performClick()
                        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.WELCOME }
                    }
                },
                description,
            ).evaluate()
        assertEquals("target rejection and cancellation save nothing", before, runBlocking { store.list() })
    }
}
