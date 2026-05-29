package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun versionRow_rendersBuildConfigVersionName() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = 0,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Version ${BuildConfig.VERSION_NAME}", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun versionRow_rendersSupportingTextWithGitSha() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = 0,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("build ${BuildConfig.GIT_SHA}", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun openSourceRow_hasClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = 0,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Open source", substring = true))
            .performScrollTo()
            .assert(hasClickAction())
    }

    @Test
    fun wallpaperColorsRow_rendersMaterialYouLabel() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = 0,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Use Material You dynamic color"))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun licenseRow_hasNoClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = 0,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("License: MIT")
            .performScrollTo()
            .assert(hasClickAction().not())
    }

    @Test
    fun archivedDiscussionsRow_rendersSupportingTextWithCount() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(
                    themeMode = ThemeMode.SYSTEM,
                    useWallpaperColors = false,
                    archivedDiscussionCount = 11,
                    defaultModel = Model.OPUS_4_7,
                    defaultEffort = Effort.HIGH,
                    defaultYolo = false,
                    defaultWorkspace = DEFAULT_SCRATCH_CWD,
                    workspacePickerVisible = false,
                    onSelectTheme = {},
                    onToggleUseWallpaperColors = {},
                    onSelectDefaultModel = {},
                    onSelectDefaultEffort = {},
                    onToggleDefaultYolo = {},
                    onDefaultWorkspaceTapped = {},
                    onSelectDefaultWorkspace = {},
                    onWorkspacePickerDismissed = {},
                    onBack = {},
                    onOpenArchivedDiscussions = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("11 archived", substring = true))
            .performScrollTo()
            .assertExists()
    }
}
