package de.pyryco.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import de.pyryco.mobile.data.crypto.KeystorePairedServerStoreTest
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.util.Base64
import java.util.UUID

/** Two activity lifetimes in one process expose the native fixture's persistent Koin cleanup. */
@RunWith(AndroidJUnit4::class)
class AnswerHostSetupCleanupTest {
    @Test
    fun targetedPairingAfterFixtureTeardownKeepsItsHostGuard() = withPreservedAppPairings { assertRestoredTargetGuard() }

    @Test
    fun failedFixtureRestoresBindingsAndPreservesAppPairings() = withPreservedAppPairings { assertRestoredTargetGuard(failedBody = true) }

    @Test
    fun storageTestKeyLossAndTeardownPreserveAppPairings() =
        withPreservedAppPairings {
            val store = GlobalContext.get().get<PairedServerCollectionStore>()
            val before = runBlocking { store.readSnapshot().getOrThrow() }
            val storageTest = KeystorePairedServerStoreTest()
            storageTest.setUp()
            try {
                RelayLog.d { "event=answer_host_key_probe stage=before_lost_key_test" }
                storageTest.load_returnsNullWhenWrapKeyLost()
                RelayLog.d { "event=answer_host_key_probe stage=after_lost_key_test" }
            } finally {
                storageTest.tearDown()
                RelayLog.d { "event=answer_host_key_probe stage=after_storage_teardown" }
            }
            assertEquals("private storage test preserves readable app pairings", before, runBlocking { store.readSnapshot().getOrThrow() })
            assertRestoredTargetGuard()
        }

    @Test
    fun failedFixtureSavePreservesOriginalErrorAndDoesNotRemoveUnownedPairings() =
        withPreservedAppPairings {
            val koin = GlobalContext.get()
            val store = koin.get<PairedServerCollectionStore>()
            val source = koin.get<ConnectionStateSource>()
            val before = runBlocking { store.readSnapshot().getOrThrow() }
            val originalError = PairedServerStoreException("paired server save failed: io")
            var removals = 0
            val faultStore =
                object : PairedServerCollectionStore by store {
                    override suspend fun save(record: PairedServer) = throw originalError

                    override suspend fun remove(serverId: String) {
                        removals++
                        throw PairedServerStoreException("paired server remove failed: io")
                    }
                }
            loadKoinModules(module { single<PairedServerCollectionStore> { faultStore } })
            try {
                val setup = AnswerHostSetupTest()
                val outcome =
                    runCatching {
                        setup.fixture
                            .apply(
                                object : Statement() {
                                    override fun evaluate() = error("failed save must not reach the test body")
                                },
                                Description.createTestDescription(javaClass, "failedFixtureSave"),
                            ).evaluate()
                    }
                assertSame("cleanup retains the original setup error", originalError, outcome.exceptionOrNull())
                assertEquals("no successfully saved fixture pairing is owned", 0, removals)
                assertSame(source, koin.get<ConnectionStateSource>())
                assertEquals(before, runBlocking { store.readSnapshot().getOrThrow() })
            } finally {
                loadKoinModules(module { single<PairedServerCollectionStore> { store } })
            }
        }

    private fun assertRestoredTargetGuard(failedBody: Boolean = false) {
        val description = Description.createTestDescription(javaClass, "targetedPairingAfterFixtureTeardownKeepsItsHostGuard")
        val originalSource = GlobalContext.get().get<ConnectionStateSource>()
        val setup = AnswerHostSetupTest()
        val bodyError = IllegalStateException("controlled fixture body failure")
        val outcome =
            runCatching {
                RuleChain
                    .outerRule(setup.fixture)
                    .around(setup.compose)
                    .apply(
                        object : Statement() {
                            override fun evaluate() {
                                if (failedBody) throw bodyError
                                setup.offlinePrecedingHostDoesNotBlockAnswerHostPairing()
                            }
                        },
                        description,
                    ).evaluate()
            }
        assertSame(if (failedBody) bodyError else null, outcome.exceptionOrNull())
        assertSame(originalSource, GlobalContext.get().get<ConnectionStateSource>())

        // Both the fixture and its Activity have finished; use the restored graph without overriding it.
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val before = runBlocking { store.readSnapshot().getOrThrow() }
        val target = before.last()
        val targetName = requireNotNull(target.displayName)
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
                        compose.runOnIdle { nav.navigate(Routes.pairCode(target.record.serverId)) }
                        compose.waitUntil(5_000) { compose.onAllNodesWithText(targetName).fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithContentDescription("Host name").assertTextContains(targetName).assertIsNotEnabled()
                        compose.onNodeWithContentDescription("Clear host name").assertDoesNotExist()
                        compose.onNodeWithContentDescription("Pairing code").performTextInput(code)
                        compose.onNodeWithText("Pair").performScrollTo().performClick()
                        compose.onNodeWithText(WRONG_HOST_ERROR).assertIsDisplayed()
                        compose.onNodeWithText("Confirm pairing").assertDoesNotExist()
                        assertEquals(before, runBlocking { store.readSnapshot().getOrThrow() })
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
        assertEquals("target rejection and cancellation save nothing", before, runBlocking { store.readSnapshot().getOrThrow() })
    }

    private fun withPreservedAppPairings(body: () -> Unit) {
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val before = runBlocking { store.readSnapshot().getOrThrow() }
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val records =
            (1..2).map {
                PairedServer("preserved-2036-${UUID.randomUUID()}", "fixture-token", "wss://relay.example", key)
            }
        val owned = mutableListOf<String>()
        var primary: Throwable? = null
        try {
            runBlocking {
                records.forEachIndexed { index, record ->
                    store.save(record)
                    owned.add(record.serverId)
                    store.setDisplayName(record.serverId, "Preserved host $index")
                }
            }
            val seeded = runBlocking { store.readSnapshot().getOrThrow() }
            body()
            assertEquals("existing records, names and order survive", seeded, runBlocking { store.readSnapshot().getOrThrow() })
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                runBlocking {
                    owned.forEach { store.remove(it) }
                    assertEquals(before, store.readSnapshot().getOrThrow())
                }
            } catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                failure.addSuppressed(cleanup)
            }
        }
    }
}
