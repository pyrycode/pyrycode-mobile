package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.ui.host.HostEditorState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wallpaperColorsRow_rendersMaterialYouLabel() {
        setSettings()

        composeTestRule
            .onNode(hasText("Use Material You dynamic color"))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun archivedDiscussionsRow_rendersSupportingTextWithCount() {
        setSettings(archivedDiscussionCount = 11)

        composeTestRule
            .onNode(hasText("11 archived", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun aboutRow_navigatesOnClick() {
        var aboutCount = 0
        setSettings(onOpenAbout = { aboutCount++ })

        composeTestRule
            .onNode(hasText("About") and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(1, aboutCount)
    }

    /** Both hosts' four facts, with only the owner's row inert and only its row badged (#750). */
    @Test
    fun connectionSection_rendersEverySavedHostAndMarksTheOwner() {
        setSettings(connection = SettingsConnectionState.Loaded(listOf(OWNER, OTHER), ownerMissing = false))

        listOf("Pyrybox", "pyrybox-2026-0f3a", "wss://relay.pyryco.de", "Mac mini", "mac-2026-8c41", "wss://relay.example")
            .forEach { composeTestRule.onNode(hasText(it)).performScrollTo().assertExists() }
        composeTestRule.onNode(hasText("This server")).performScrollTo().assertExists()
        // Both rows act since #751 — the owner's opens its editor, every other row opens that host's
        // Settings. What still separates them is the badge, and which callback each one fires (below).
        composeTestRule.onNode(hasAnyDescendant(hasText("Pyrybox")) and hasClickAction()).assertExists()
        composeTestRule.onNode(hasAnyDescendant(hasText("Mac mini")) and hasClickAction()).assertExists()
        composeTestRule.onAllNodes(hasText("This server")).assertCountEquals(1)
    }

    @Test
    fun connectionSection_opensTheTappedHostByItsOwnServerId() {
        val opened = mutableListOf<String>()
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OWNER, OTHER), ownerMissing = false),
            onOpenHost = { opened += it },
        )

        composeTestRule
            .onNode(hasAnyDescendant(hasText("Mac mini")) and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(listOf("mac-2026-8c41"), opened)
    }

    @Test
    fun connectionSection_saysNoHostIsPairedWhenNoneIsSaved() {
        var pairCount = 0
        setSettings(
            connection = SettingsConnectionState.Loaded(emptyList(), ownerMissing = false),
            onPairServer = { pairCount++ },
        )

        composeTestRule.onNode(hasText("No host is paired")).performScrollTo().assertExists()
        composeTestRule
            .onNode(hasText("Pair another server") and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(1, pairCount)
    }

    /** A vanished owner is said so, without any surviving host's row being badged as this one. */
    @Test
    fun connectionSection_saysTheOwnerIsGoneWithoutBadgingASurvivingHost() {
        setSettings(connection = SettingsConnectionState.Loaded(listOf(OTHER), ownerMissing = true))

        composeTestRule.onNode(hasText("This host is no longer paired")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("Mac mini")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("This server")).assertDoesNotExist()
        composeTestRule.onNode(hasText("No host is paired")).assertDoesNotExist()
    }

    /**
     * A destination owning no host draws the Archive entry inert (#715). Load-bearing rather than
     * cosmetic: the callback is null exactly when there is no owner to put in the route, and a blank
     * owner would build `archived_discussions/`, which matches no destination.
     */
    @Test
    fun archiveRow_isNotClickable_whenNoOwnerToOpenItFor() {
        setSettings(onOpenArchivedDiscussions = null)

        composeTestRule
            .onNode(hasText("Archived discussions"))
            .performScrollTo()
            .assertHasNoClickAction()
    }

    /**
     * The host's chosen name for the default workspace wins over its directory basename (#723). The
     * row's own path is still `~/Workspace/Projects/pyrycode-mobile`, which must not be on screen —
     * asserting its basename is absent is what separates "the label won" from "both are rendered".
     */
    @Test
    fun defaultWorkspaceRow_rendersTheHostsLabel_whenOneNamesThePath() {
        setSettings(defaultWorkspace = BOUND_PATH, defaultWorkspaceLabel = "Pyrycode Mobile")

        composeTestRule.onNode(hasText("Pyrycode Mobile")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("pyrycode-mobile")).assertDoesNotExist()
    }

    @Test
    fun defaultWorkspaceRow_fallsBackToTheLastPathSegment_whenNothingNamesIt() {
        setSettings(defaultWorkspace = BOUND_PATH, defaultWorkspaceLabel = null)

        composeTestRule.onNode(hasText("pyrycode-mobile")).performScrollTo().assertExists()
    }

    /** AC1: the owner's row — and only it — opens the editor, rather than navigating anywhere. */
    @Test
    fun connectionSection_opensTheEditorFromTheOwnersRowOnly() {
        var editCount = 0
        val opened = mutableListOf<String>()
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OWNER, OTHER), ownerMissing = false),
            onEditHost = { editCount++ },
            onOpenHost = { opened += it },
        )

        composeTestRule
            .onNode(hasAnyDescendant(hasText("Pyrybox")) and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(1, editCount)
        assertEquals(emptyList<String>(), opened)
    }

    /** AC1, the other half: a non-owner row still hops to that host's own Settings, not the editor. */
    @Test
    fun connectionSection_keepsHostToHostNavigationOnEveryOtherRow() {
        var editCount = 0
        val opened = mutableListOf<String>()
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OWNER, OTHER), ownerMissing = false),
            onEditHost = { editCount++ },
            onOpenHost = { opened += it },
        )

        composeTestRule
            .onNode(hasAnyDescendant(hasText("Mac mini")) and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(listOf("mac-2026-8c41"), opened)
        assertEquals(0, editCount)
    }

    /** The modal is on screen exactly while the view model holds a target, and draws that host. */
    @Test
    fun hostEditor_rendersTheModalForTheOpenTarget() {
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OWNER, OTHER), ownerMissing = false),
            hostEditor =
                HostEditorState(
                    serverId = "pyrybox-2026-0f3a",
                    serverIdentity = "pyrybox-2026-0f3a",
                    relayAddress = "wss://relay.pyryco.de",
                    initialName = "Pyrybox",
                ),
        )

        composeTestRule.onNode(hasText("Edit host")).assertExists()
        composeTestRule.onNode(hasText("Unpair host")).assertExists()
    }

    /** AC2: the confirmation replaces the frame's content in place and decides through the footer. */
    @Test
    fun hostEditor_showsTheUnpairConfirmationInPlace() {
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OWNER), ownerMissing = false),
            hostEditor =
                HostEditorState(
                    serverId = "pyrybox-2026-0f3a",
                    serverIdentity = "pyrybox-2026-0f3a",
                    relayAddress = "wss://relay.pyryco.de",
                    initialName = "Pyrybox",
                    confirmingUnpair = true,
                ),
        )

        composeTestRule.onNode(hasText("Unpair host?")).assertExists()
        // Matched on the prompt's own wording, not on the host name: the row behind the scrim still
        // renders that name, so a bare name matcher finds two nodes and fails on ambiguity rather
        // than on absence — the same trap #750 recorded for the status line's content descriptions.
        composeTestRule.onNode(hasText("will be removed from this phone", substring = true)).assertExists()
    }

    /**
     * AC3: a failed removal reports a generic message — no identity, no relay address.
     *
     * The shell renders `error` verbatim into a live region, so this asserts the absence of both
     * values as well as the presence of the string: a message that named the host would be announced.
     */
    @Test
    fun hostEditor_reportsAFailedRemovalWithoutNamingTheHost() {
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OWNER), ownerMissing = false),
            hostEditor =
                HostEditorState(
                    serverId = "pyrybox-2026-0f3a",
                    serverIdentity = "pyrybox-2026-0f3a",
                    relayAddress = "wss://relay.pyryco.de",
                    initialName = "Pyrybox",
                    confirmingUnpair = true,
                    unpairFailed = true,
                ),
        )

        composeTestRule.onNode(hasText("Couldn't unpair the host. Try again.")).assertExists()
        // Counted, not asserted absent: the owner's row behind the scrim renders the relay address
        // legitimately (#750), so "nowhere on screen" is the wrong property. What must hold is that
        // the modal adds no second rendering of it — the confirmation step draws neither identity
        // line, and the error string carries no format argument that could smuggle one in.
        composeTestRule.onAllNodes(hasText("wss://relay.pyryco.de", substring = true)).assertCountEquals(1)
    }

    /**
     * AC4: after the removal this destination stays put — it lists what is left, says the owner is
     * gone, and keeps the pairing entry usable. No row is badged and none opens an editor.
     */
    @Test
    fun connectionSection_offersNoEditorOnceTheOwnerIsNoLongerPaired() {
        var editCount = 0
        var pairCount = 0
        setSettings(
            connection = SettingsConnectionState.Loaded(listOf(OTHER), ownerMissing = true),
            onEditHost = { editCount++ },
            onPairServer = { pairCount++ },
        )

        composeTestRule.onNode(hasText("This host is no longer paired")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("This server")).assertDoesNotExist()
        composeTestRule
            .onNode(hasAnyDescendant(hasText("Mac mini")) and hasClickAction())
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNode(hasText("Pair another server") and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(0, editCount)
        assertEquals(1, pairCount)
    }

    private fun setSettings(
        connection: SettingsConnectionState = SettingsConnectionState.Resolving,
        archivedDiscussionCount: Int = 0,
        defaultWorkspace: String = DEFAULT_SCRATCH_CWD,
        defaultWorkspaceLabel: String? = null,
        onOpenArchivedDiscussions: (() -> Unit)? = {},
        onOpenHost: (String) -> Unit = {},
        hostEditor: HostEditorState? = null,
        onEditHost: () -> Unit = {},
        onPairServer: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    connection = connection,
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = archivedDiscussionCount,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    pushNotifications = true,
                    defaultWorkspace = defaultWorkspace,
                    defaultWorkspaceLabel = defaultWorkspaceLabel,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onTogglePushNotifications = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onOpenHost = onOpenHost,
                    hostEditor = hostEditor,
                    onEditHost = onEditHost,
                    onEditHostNameSubmitted = {},
                    onHostUnpairRequested = {},
                    onHostUnpairConfirmed = {},
                    onHostUnpairDeclined = {},
                    onEditHostDismissed = {},
                    onPairServer = onPairServer,
                    onBack = {},
                    onOpenArchivedDiscussions = onOpenArchivedDiscussions,
                    onOpenAbout = onOpenAbout,
                )
            }
        }
    }

    private companion object {
        const val BOUND_PATH = "~/Workspace/Projects/pyrycode-mobile"

        val OWNER =
            SettingsHostRow(
                serverId = "pyrybox-2026-0f3a",
                displayName = "Pyrybox",
                relayUrl = "wss://relay.pyryco.de",
                status = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                isOwner = true,
            )
        val OTHER =
            SettingsHostRow(
                serverId = "mac-2026-8c41",
                displayName = "Mac mini",
                relayUrl = "wss://relay.example",
                status = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
                isOwner = false,
            )
    }
}
