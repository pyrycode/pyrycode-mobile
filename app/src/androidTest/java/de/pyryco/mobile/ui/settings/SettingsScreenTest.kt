package de.pyryco.mobile.ui.settings

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

    /** The owner's four facts, all of them present and none of them a click target (#749). */
    @Test
    fun connectionSection_rendersOwnersIdentityAndStatusInertly() {
        setSettings(
            host =
                SettingsHostState.Owned(
                    serverId = "pyrybox-2026-0f3a",
                    displayName = "Pyrybox",
                    relayUrl = "wss://relay.pyryco.de",
                    status = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                ),
        )

        composeTestRule.onNode(hasText("Pyrybox")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("pyrybox-2026-0f3a")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("wss://relay.pyryco.de")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("Pyrybox") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun connectionSection_saysNoHostIsPairedWhenTheDestinationOwnsNone() {
        var pairCount = 0
        setSettings(host = SettingsHostState.Unpaired, onPairServer = { pairCount++ })

        composeTestRule.onNode(hasText("No host is paired")).performScrollTo().assertExists()
        composeTestRule
            .onNode(hasText("Pair another server") and hasClickAction())
            .performScrollTo()
            .performClick()

        assertEquals(1, pairCount)
    }

    @Test
    fun connectionSection_saysTheOwnerIsGoneRatherThanNamingAnotherHost() {
        setSettings(host = SettingsHostState.Unknown)

        composeTestRule.onNode(hasText("This host is no longer paired")).performScrollTo().assertExists()
        composeTestRule.onNode(hasText("No host is paired")).assertDoesNotExist()
    }

    private fun setSettings(
        host: SettingsHostState = SettingsHostState.Resolving,
        archivedDiscussionCount: Int = 0,
        onPairServer: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    host = host,
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
                    onPairServer = onPairServer,
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                    onOpenAbout = onOpenAbout,
                )
            }
        }
    }
}
