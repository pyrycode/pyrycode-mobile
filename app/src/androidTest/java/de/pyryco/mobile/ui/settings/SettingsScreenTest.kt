package de.pyryco.mobile.ui.settings

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
        // The owner is here already, so its row navigates nowhere; every other row does.
        composeTestRule.onNode(hasAnyDescendant(hasText("Pyrybox")) and hasClickAction()).assertDoesNotExist()
        composeTestRule.onNode(hasAnyDescendant(hasText("Mac mini")) and hasClickAction()).assertExists()
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

    private fun setSettings(
        connection: SettingsConnectionState = SettingsConnectionState.Resolving,
        archivedDiscussionCount: Int = 0,
        onOpenArchivedDiscussions: (() -> Unit)? = {},
        onOpenHost: (String) -> Unit = {},
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
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
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
                    onPairServer = onPairServer,
                    onBack = {},
                    onOpenArchivedDiscussions = onOpenArchivedDiscussions,
                    onOpenAbout = onOpenAbout,
                )
            }
        }
    }

    private companion object {
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
