package de.pyryco.mobile.ui.settings

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only: changes real display/font settings and retains actual framebuffer pixels. */
@RunWith(AndroidJUnit4::class)
class SettingsDensityDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val output by lazy {
        File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "settings-density-1152")
            .apply { mkdirs() }
    }

    @Test fun normalFont_settingsAndAbout() = exercise(1f)

    @Test fun largeFont_settingsAndAbout() = exercise(2f)

    private fun exercise(fontScale: Float) {
        val oldSize = overrideOf(shell("wm size"))
        val oldDensity = overrideOf(shell("wm density"))
        val oldScale = shell("settings get system font_scale").trim()
        try {
            shell("wm density 420")
            shell("wm size 1080x2340")
            shell("settings put system font_scale $fontScale")
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(1_000, 5_000)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                var about by mutableStateOf(false)
                scenario.onActivity { activity ->
                    assertEquals(2.625f, activity.resources.displayMetrics.density, 0.01f)
                    assertEquals(fontScale, activity.resources.configuration.fontScale, 0.01f)
                    // Match MainActivity's window setup and outer system-inset boundary.
                    activity.enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                    activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    activity.setContent {
                        PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                            Scaffold(Modifier.fillMaxSize()) { inner ->
                                val screenModifier = Modifier.padding(inner).consumeWindowInsets(inner)
                                if (about) {
                                    AboutScreen(onBack = { about = false }, modifier = screenModifier)
                                } else {
                                    DensitySettings(modifier = screenModifier) { about = true }
                                }
                            }
                        }
                    }
                }
                rule.onNodeWithText("Pyrybox").assertIsDisplayed()
                rule.onNodeWithText("This server").assertIsDisplayed()
                rule.onNodeWithContentDescription("Relay: connected").assertIsDisplayed()
                rule.onNodeWithContentDescription("Pyrycode: connected").assertIsDisplayed()
                capture("settings-top", fontScale)
                val measurements = StringBuilder()
                for ((label, expected) in listOf(
                    "Pair another server" to 48,
                    "Theme" to 62,
                    "Use Material You dynamic color" to 52,
                    "Default YOLO" to 62,
                )) {
                    val bounds =
                        rule
                            .onNodeWithText(label)
                            .performScrollTo()
                            .assertIsDisplayed()
                            .fetchSemanticsNode()
                            .boundsInWindow
                    assertEquals(1080f, bounds.width, 1f)
                    if (fontScale == 1f) assertEquals(label, expected * 2.625f, bounds.height, 2f)
                    measurements.appendLine("$label boundsPx=$bounds heightDp=${bounds.height / 2.625f}")
                }
                rule.onNodeWithText("Default workspace").performScrollTo().assertIsDisplayed()
                capture("settings-defaults", fontScale)
                rule.onNode(hasText("About") and hasClickAction()).performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Log data").assertIsDisplayed()
                capture("settings-lower", fontScale)
                rule.onNode(hasText("About") and hasClickAction()).performClick()
                rule.onNodeWithText("License: MIT").performScrollTo().assertIsDisplayed()
                val link = "Open source · github.com/pyrycode/pyrycode-mobile"
                val layouts = mutableListOf<TextLayoutResult>()
                rule
                    .onNodeWithText(
                        link,
                        useUnmergedTree = true,
                    ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(layouts.single().hasVisualOverflow)
                assertTrue("long About link wraps", layouts.single().lineCount > 1)
                for ((label, expected) in listOf(
                    "Version ${BuildConfig.VERSION_NAME}" to 62,
                    "Privacy policy" to 48,
                    "License: MIT" to 44,
                )) {
                    val bounds =
                        rule
                            .onNodeWithText(label)
                            .performScrollTo()
                            .fetchSemanticsNode()
                            .boundsInWindow
                    if (fontScale == 1f) assertEquals(label, expected * 2.625f, bounds.height, 2f)
                    measurements.appendLine("$label boundsPx=$bounds heightDp=${bounds.height / 2.625f}")
                }
                capture("about", fontScale)
                File(output, "scale-$fontScale.txt").writeText(
                    "productionComposables=SettingsScreen,AboutScreen syntheticHost=true theme=dark wallpaperColors=false\n" +
                        "sizePx=1080x2340 sizeDp=411.43x891.43 density=2.625 densityDpi=420 fontScale=$fontScale\n" +
                        "api=${Build.VERSION.SDK_INT} device=${Build.MODEL} fingerprint=${Build.FINGERPRINT} " +
                        "build=${BuildConfig.GIT_SHA}\n" +
                        measurements,
                )
            }
        } finally {
            shell("wm size $oldSize")
            shell("wm density $oldDensity")
            if (oldScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldScale")
            instrumentation.waitForIdleSync()
        }
    }

    private fun capture(
        section: String,
        scale: Float,
    ) {
        // The regular ATD gate proves layout. Request pixels explicitly on the full Pixel 8 image;
        // ATD's black framebuffer must never be retained as visual evidence.
        if (InstrumentationRegistry.getArguments().getString("captureSettingsPixels") != "true") return
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
        rule.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(1080, bitmap.width)
        assertEquals(2340, bitmap.height)
        assertTrue(
            "nonblank screenshot",
            (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }.toSet().size >
                10,
        )
        File(output, "$section-scale-$scale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText() }

    private fun overrideOf(value: String) = value.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}

@androidx.compose.runtime.Composable
private fun DensitySettings(
    modifier: Modifier = Modifier,
    onAbout: () -> Unit,
) {
    SettingsScreen(
        connection =
            SettingsConnectionState.Loaded(
                listOf(
                    SettingsHostRow(
                        "pyrybox-2026-0f3a",
                        "Pyrybox",
                        "wss://relay.example",
                        ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                        true,
                    ),
                ),
                ownerMissing = false,
            ),
        themeMode = ThemeMode.DARK,
        useWallpaperColors = false,
        archivedDiscussionCount = 11,
        defaultModel = Model.OPUS_4_7,
        defaultEffort = Effort.HIGH,
        defaultYolo = false,
        pushNotifications = true,
        defaultWorkspace = DEFAULT_SCRATCH_CWD,
        defaultWorkspaceLabel = null,
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
        onOpenHost = {},
        hostEditor = null,
        onEditHost = {},
        onEditHostNameSubmitted = {},
        onHostUnpairRequested = {},
        onHostUnpairConfirmed = {},
        onHostUnpairDeclined = {},
        onEditHostDismissed = {},
        onPairServer = {},
        onBack = {},
        onOpenArchivedDiscussions = {},
        onOpenLogData = {},
        logData = null,
        onLogDataRequested = {},
        onLogDataSaveRequested = {},
        onLogDataDismissed = {},
        onOpenAbout = onAbout,
        modifier = modifier,
    )
}
