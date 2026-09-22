package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.base64StdEncode
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.components.EDIT_HOST_NAME_FIELD_TAG
import de.pyryco.mobile.ui.conversations.thread.NavigationPeer
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The Settings destination on the production graph, bindings and `Routes` (#749).
 *
 * Mounts `PyryNavHost` directly rather than the Activity, so — like `LiteralScreenNavigationTest`,
 * whose harness this copies — it proves route ownership and cannot prove the startup gate.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var store: ObservablePairedServerStore
    private lateinit var nav: NavHostController
    private lateinit var preferences: AppPreferences

    private val peers = mutableMapOf<String, NavigationPeer>()
    private val stored = MutableStateFlow(emptyPreferences() as Preferences)

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    /**
     * The captured owner outlives a compatibility-selection change, a saved-state restoration and a
     * Back; reopening captures afresh. Bravo's supervisor is closed first, so a screen that followed
     * selection could not keep showing a connected relay after the flip — and since #750 both hosts
     * are on screen throughout, so what identifies the owner is the badge on its row, not the
     * absence of the other.
     */
    @Test fun settingsKeepsItsCapturedOwnerAcrossSelectionChangeAndRestoration() {
        val restoration = start(live = true)
        select(ALPHA_ID)
        openSettings()
        assertOwner(ALPHA_ID)
        assertShowsAlphaConnected()
        assertBadgedRowIs(ALPHA_NAME, other = BRAVO_NAME)

        compose.runOnIdle { registry.connectionFor(BRAVO_ID)!!.supervisor.close() }
        compose.waitForIdle()
        select(BRAVO_ID)
        assertOwner(ALPHA_ID)
        assertShowsAlphaConnected()
        assertBadgedRowIs(ALPHA_NAME, other = BRAVO_NAME)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertOwner(ALPHA_ID)
        assertShowsAlphaConnected()
        assertBadgedRowIs(ALPHA_NAME, other = BRAVO_NAME)

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        openSettings()
        assertOwner(BRAVO_ID)
        assertBadgedRowIs(BRAVO_NAME, other = ALPHA_NAME)
    }

    /**
     * Every saved host is drawn with its own identity and its own status, a rename and a status
     * change both land without leaving the screen, and the owner's row is the badged one (#750).
     */
    @Test fun settingsListsEverySavedHostAndFollowsTheirLiveIdentityAndStatus() {
        start(live = true)
        // Only Alpha's supervisor stays up, so the two rows must hold visibly different statuses.
        compose.runOnIdle { registry.connectionFor(BRAVO_ID)!!.supervisor.close() }
        select(ALPHA_ID)
        openSettings()
        assertShowsAlphaConnected()
        compose.onNodeWithText(BRAVO_ID).assertIsDisplayed()
        compose.onNodeWithText(BRAVO_RELAY).assertIsDisplayed()
        // The owner is already here, so its row is the badged one; since #751 it opens the editor
        // rather than navigating, and every other row still goes to that host's own Settings.
        assertBadgedRowIs(ALPHA_NAME, other = BRAVO_NAME)

        // A rename and a status change, both arriving while Settings stays open.
        compose.runOnIdle { runBlocking { store.setDisplayName(BRAVO_ID, RENAMED) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(RENAMED).fetchSemanticsNodes().isNotEmpty() }
        // Exact match, so the renamed "Bravo renamed" does not satisfy the old name.
        compose.onNodeWithText(BRAVO_NAME).assertDoesNotExist()
        compose.runOnIdle { registry.connectionFor(ALPHA_ID)!!.supervisor.close() }
        compose.waitUntil(5_000) { connectedRelayLegs() == 0 }
        assertOwner(ALPHA_ID)
    }

    /**
     * Tapping another host's row opens that host's Settings by its exact id — Alpha's carries
     * reserved characters, so the hop is proven encoded rather than assumed — and the hop replaces
     * this entry rather than stacking on it, so Back reaches the list the gear was tapped from.
     */
    @Test fun tappingAnotherHostsRowOpensItsSettingsAndDoesNotStackOnTheOne() {
        start()
        select(BRAVO_ID)
        openSettings()
        assertOwner(BRAVO_ID)
        assertBadgedRowIs(BRAVO_NAME, other = ALPHA_NAME)

        compose.onNode(hasAnyDescendant(hasText(ALPHA_NAME)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertOwner(ALPHA_ID)
        assertBadgedRowIs(ALPHA_NAME, other = BRAVO_NAME)

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route) }
    }

    /**
     * The owner's row opens the editor on the host this destination captured, and goes nowhere (#751).
     *
     * The join between `SettingsScreen`'s `onEditHost` and the view model's `openOwnerHostEditor`
     * lives in `PyryNavHost` and is invisible to both screen-level and view-model-level tests, which
     * is why it is proven here on the production graph. Bravo is the selected host for the tap, so a
     * destination that opened on selection rather than on its captured owner would put Bravo's name
     * in the field — and the field is the only place the two hosts differ once the modal is up.
     */
    @Test fun tappingTheOwnersRowOpensTheEditorOnTheCapturedHostNotTheSelectedOne() {
        start()
        select(ALPHA_ID)
        openSettings()
        assertOwner(ALPHA_ID)
        select(BRAVO_ID)

        compose.onNode(hasAnyDescendant(hasText(ALPHA_NAME)) and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(EDIT_HOST_TITLE).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertTextContains(ALPHA_NAME)
        // Opening an editor is not navigating: this destination stays exactly where it was, which is
        // also what AC4 relies on after the removal the editor can reach from here.
        assertOwner(ALPHA_ID)
        compose.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
    }

    /**
     * Neither copy state borrows a host's identity as its own, and neither closes the screen — the
     * `HostDestination` guard the thread routes use would have returned both of these to the list.
     */
    @Test fun unknownAndAbsentOwnersKeepSettingsOpenWithoutClaimingAHost() {
        start()
        compose.runOnIdle { nav.navigate(Routes.settings("ghost")) }
        awaitSettings()
        assertOwner("ghost")
        compose.onNodeWithText("This host is no longer paired").assertIsDisplayed()
        // Both saved hosts are still listed; what is absent is any claim that one of them is this one.
        compose.onNodeWithText(ALPHA_NAME).assertIsDisplayed()
        compose.onNodeWithText(BRAVO_NAME).assertIsDisplayed()
        compose.onNodeWithText(OWNER_BADGE).assertDoesNotExist()
        // Still Settings, and the app-wide sections below it still draw.
        compose.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
        compose.onNodeWithText("Use Material You dynamic color").performScrollTo().assertIsDisplayed()

        compose.runOnIdle {
            nav.popBackStack()
            runBlocking {
                store.remove(ALPHA_ID)
                store.remove(BRAVO_ID)
            }
        }
        compose.waitForIdle()
        openSettings()
        assertOwner("")
        compose.onNodeWithText("No host is paired").assertIsDisplayed()
        assertNoHostIdentityOnScreen()
        // Usability of the pairing entry is asserted here and its target in the diff: clicking it
        // would compose the camera destination and raise a system permission dialog, and
        // `SettingsScreenTest` already pins that the click reaches the callback.
        compose.onNodeWithText("Pair another server").assertIsDisplayed().assertHasClickAction()
    }

    /**
     * The Default workspace picker reads, creates and stores for the host whose Settings this is,
     * and a compatibility-selection change while the sheet is open cannot retarget it (#714).
     *
     * Mounted on the production graph on purpose: binding only the view model leaves the sheet
     * resolving `LocalWorkspacePickerRepository` through the compatibility binding, which the seam
     * tests in `WorkspacePickerTest` cannot see because they pass their repository in directly.
     * Bravo is the selected host for the whole second half, so a picker that followed selection
     * would show Bravo's recent folder and send Bravo's peer a create request.
     */
    @Test fun settingsWorkspacePickerReadsCreatesAndStoresOnlyForItsOwnHost() {
        start(live = true)
        select(ALPHA_ID)
        openSettings()
        assertOwner(ALPHA_ID)
        openWorkspacePicker()
        assertOwnerPicker()

        select(BRAVO_ID)
        assertOwnerPicker()
        createFolder()

        // The created path is what the owner's peer returned, stored under the owner's own key.
        compose.waitUntil(5_000) { workspace(ALPHA_ID) == "/$ALPHA_ID/created" }
        compose.runOnIdle {
            assertEquals(DEFAULT_SCRATCH_CWD, workspace(BRAVO_ID))
            assertNoOtherPickerCalls()
            assertEquals(1, peers.getValue(ALPHA_ID).outbound.count { it.type == "create_workspace_folder" })
        }
        // …and the row under the owner's Settings is the one that now shows it.
        compose.onNodeWithText("created").performScrollTo().assertIsDisplayed()
    }

    /**
     * A destination that captured no host cannot open the picker at all. Were it to open, the sheet
     * would find no provider and fall back to the compatibility repository — whichever host was
     * selected last — which is the substitution the ticket forbids.
     */
    @Test fun settingsWithNoOwnerCannotOpenTheWorkspacePicker() {
        start(live = true)
        compose.runOnIdle { nav.navigate(Routes.settings(null)) }
        awaitSettings()
        assertOwner("")
        openWorkspacePicker()
        compose.onNodeWithText("/$ALPHA_ID/recent").assertDoesNotExist()
        compose.onNodeWithText("/$BRAVO_ID/recent").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(DEFAULT_SCRATCH_CWD, workspace(ALPHA_ID))
            listOf(ALPHA_ID, BRAVO_ID).forEach { id ->
                assertEquals(
                    false,
                    peers.getValue(id).outbound.any { it.type in PICKER_VERBS },
                )
            }
        }
    }

    private fun openWorkspacePicker() {
        compose.onNodeWithText("Default workspace").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Alpha's recent folder is on screen, Bravo's is not, and only Alpha's peer was ever asked. */
    private fun assertOwnerPicker() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("/$ALPHA_ID/recent").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("/$BRAVO_ID/recent").assertDoesNotExist()
        compose.runOnIdle { assertNoOtherPickerCalls() }
    }

    private fun createFolder() {
        compose.onNodeWithText("Create new folder under pyry-workspace…").performClick()
        compose.onNodeWithText("What should this workspace be called?").performTextInput("owned-folder")
        compose.onNodeWithText("Create", ignoreCase = false).performClick()
    }

    private fun assertNoOtherPickerCalls() {
        assertEquals(false, peers.getValue(BRAVO_ID).outbound.any { it.type in PICKER_VERBS })
    }

    private fun workspace(serverId: String) = runBlocking { preferences.defaultWorkspace(serverId).first() }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        awaitSettings()
    }

    private fun awaitSettings() {
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.SETTINGS }
        compose.waitForIdle()
    }

    private fun assertOwner(serverId: String) {
        compose.runOnIdle {
            assertEquals(serverId, Routes.settingsOwner(nav.currentBackStackEntry?.arguments))
        }
    }

    /**
     * Alpha's identity, and a connected status leg on screen.
     *
     * Both legs are asserted by presence rather than uniqueness: `ConnectionStatusLine`'s legs are
     * `clearAndSetSemantics`, so since #750 each drawn host contributes its own `"Relay: …"` and
     * `"Pyrycode: …"` node, and a bare `onNodeWithContentDescription` fails on *ambiguity* — two
     * matches — whenever both hosts happen to be connected. That reads as "the status is missing"
     * and is not; what identifies Alpha here is its three text lines and its badge, asserted by the
     * callers, not the count of connected dots.
     */
    private fun assertShowsAlphaConnected() {
        compose.waitUntil(5_000) { connectedRelayLegs() > 0 }
        compose.onNodeWithText(ALPHA_NAME).assertIsDisplayed()
        compose.onNodeWithText(ALPHA_ID).assertIsDisplayed()
        compose.onNodeWithText(ALPHA_RELAY).assertIsDisplayed()
        assertEquals(
            true,
            compose.onAllNodesWithContentDescription("Pyrycode: connected").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun connectedRelayLegs() = compose.onAllNodesWithContentDescription("Relay: connected").fetchSemanticsNodes().size

    /**
     * Exactly one row claims to be this screen's own host, and it is [owner]'s.
     *
     * Asserted on the badge's own row since #751. The previous formulation identified the owner by
     * the *absence* of a click action — "the one row that navigates nowhere, because it is here" —
     * which this ticket deliberately reverses: the owner's row is now the one that opens the editor,
     * so every row acts and that indirection no longer separates them.
     *
     * The direct matcher it is replaced by was unavailable before, for the reason the old comment
     * recorded: a row without an `onClick` adds no semantics node of its own, so "has the name and
     * the badge as descendants" matched every common *ancestor* instead, four of them, and failed on
     * ambiguity. Both rows carrying a click action is what now yields one node per row, so a row's
     * subtree can be addressed directly and the badge asserted where it actually hangs.
     *
     * Which callback each row fires is a separate property, pinned by the two tests that tap a row —
     * [tappingAnotherHostsRowOpensItsSettingsAndDoesNotStackOnTheOne] and
     * [tappingTheOwnersRowOpensTheEditorOnTheCapturedHostNotTheSelectedOne]. This helper's callers
     * use it to follow *which host is the owner* across a selection flip, a restoration and a
     * reopen, so who is badged is the whole of what it needs to say.
     */
    private fun assertBadgedRowIs(
        owner: String,
        other: String,
    ) {
        compose.onAllNodesWithText(OWNER_BADGE).assertCountEquals(1)
        compose
            .onNode(hasAnyDescendant(hasText(owner)) and hasClickAction())
            .assert(hasAnyDescendant(hasText(OWNER_BADGE)))
        compose
            .onNode(hasAnyDescendant(hasText(other)) and hasClickAction())
            .assert(hasAnyDescendant(hasText(OWNER_BADGE)).not())
    }

    private fun assertNoHostIdentityOnScreen() {
        compose.onNodeWithText(ALPHA_NAME).assertDoesNotExist()
        compose.onNodeWithText(BRAVO_NAME).assertDoesNotExist()
        compose.onNodeWithText(ALPHA_RELAY).assertDoesNotExist()
        compose.onNodeWithText(BRAVO_RELAY).assertDoesNotExist()
        compose.onNodeWithText(ALPHA_ID).assertDoesNotExist()
    }

    /** Re-saving a record makes it the latest, which is what compatibility selection follows. */
    private fun select(serverId: String) {
        compose.runOnIdle { runBlocking { store.save(store.loadById(serverId)!!.record) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(registry.connectionFor(serverId), registry.selected.value) }
    }

    private fun start(live: Boolean = false): StateRestorationTester {
        val serverKey = NavigationPeer.key()
        val deviceKey = NavigationPeer.key()
        val raw =
            object : PairedServerCollectionStore {
                var entries =
                    listOf(ALPHA_ID to ALPHA_NAME, BRAVO_ID to BRAVO_NAME).map { (id, name) ->
                        PairedServerEntry(
                            PairedServer(id, "unused", relayFor(id), base64StdEncode(serverKey.publicKey)),
                            name,
                        )
                    }

                override suspend fun list() = entries

                override suspend fun load() = entries.lastOrNull()?.record

                override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

                // Preserves the local name, as the real store's contract requires — otherwise a
                // selection flip would silently blank the very identity under assertion.
                override suspend fun save(record: PairedServer) {
                    val held = entries.find { it.record.serverId == record.serverId }?.displayName
                    entries = entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record, held)
                }

                override suspend fun remove(serverId: String) {
                    entries = entries.filterNot { it.record.serverId == serverId }
                }

                override suspend fun setDisplayName(
                    serverId: String,
                    displayName: String?,
                ) {
                    entries = entries.map { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
                }
            }
        store = ObservablePairedServerStore(raw) { }
        val keys =
            object : DeviceStaticKeyStore {
                override suspend fun loadOrCreate(serverId: String) =
                    DeviceStaticKeyPair(deviceKey.publicKey.copyOf(), deviceKey.privateKey.copyOf())

                override suspend fun publicKey(serverId: String) = deviceKey.publicKey.copyOf()
            }
        registry =
            RelayConnectionRegistry(
                store,
                RelayConnectionFactory(
                    keys,
                    RelayTransportFactory { record ->
                        check(live) { "must not dial" }
                        NavigationPeer(record.serverId, "unused", serverKey).also { peers[record.serverId] = it }
                    },
                    NoiseClientInfo("test", "test"),
                    dispatcher = Dispatchers.Main.immediate,
                    ioDispatcher = Dispatchers.Main.immediate,
                ),
                Dispatchers.Main.immediate,
            )
        if (live) registry.connect()
        // Writable, unlike the read-only stand-in the #749 tests started from: #714's picker stores
        // its pick, and a store that drops writes would let a test pass while the value went nowhere.
        // In-memory rather than file-backed — nothing here needs to survive the process.
        preferences =
            AppPreferences(
                object : DataStore<Preferences> {
                    override val data = stored

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                        transform(stored.value).also { stored.value = it }
                },
            )
        app =
            KoinApplication.init().modules(
                appModule,
                conversationRepositoryModule(true),
                module {
                    single { registry }
                    single { store } binds arrayOf(PairedServerStore::class, PairedServerCollectionStore::class)
                    single { preferences }
                },
            )
        return StateRestorationTester(compose).also { tester ->
            tester.setContent {
                KoinIsolatedContext(app) {
                    PyrycodeMobileTheme {
                        nav = rememberNavController()
                        PyryNavHost(Routes.CHANNEL_LIST, navController = nav)
                    }
                }
            }
            compose.waitForIdle()
        }
    }

    private companion object {
        // Reserved characters in the owner's id, as the thread routes' test uses: here the id
        // travels in a query parameter rather than a path segment, and nothing else proves that
        // encoding round-trips.
        const val ALPHA_ID = "A /?#%"
        const val BRAVO_ID = "B"
        const val ALPHA_NAME = "Alpha"
        const val BRAVO_NAME = "Bravo"
        const val ALPHA_RELAY = "wss://alpha.example"
        const val BRAVO_RELAY = "wss://bravo.example"
        const val RENAMED = "Bravo renamed"
        const val OWNER_BADGE = "This server"
        const val EDIT_HOST_TITLE = "Edit host"

        /** Every verb the picker can put on the wire; a non-owner peer must see none of them. */
        val PICKER_VERBS = setOf("recent_workspaces", "create_workspace_folder")

        fun relayFor(serverId: String) = if (serverId == BRAVO_ID) BRAVO_RELAY else ALPHA_RELAY
    }
}
