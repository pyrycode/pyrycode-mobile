package de.pyryco.mobile.ui.settings

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real-window geometry and optional framebuffer evidence for the Settings modal. */
@RunWith(AndroidJUnit4::class)
class SettingsDensityDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun settingsAtFigmaViewport() = exercise(412, 892, 1f)

    @Test fun compactSettingsWithLargeText() = exercise(320, 640, 2f)

    private fun exercise(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        val oldSize = overrideOf(shell("wm size"))
        val oldDensity = overrideOf(shell("wm density"))
        val oldScale = shell("settings get system font_scale").trim()
        try {
            shell("wm density 160")
            shell("wm size ${width}x$height")
            shell("settings put system font_scale $fontScale")
            instrumentation.waitForIdleSync()
            val dismissals = AtomicInteger()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(fontScale, activity.resources.configuration.fontScale, 0.01f)
                    activity.setContent {
                        PyrycodeMobileTheme(darkTheme = true) {
                            SettingsScreen(
                                pushNotifications = true,
                                onTogglePushNotifications = {},
                                collapseToolUses = true,
                                onToggleCollapseToolUses = {},
                                onDismissRequest = { dismissals.incrementAndGet() },
                            )
                        }
                    }
                }
                for (label in listOf(
                    "Settings",
                    "Notifications",
                    "Push notifications when claude responds",
                    "Notification sound",
                    "Default",
                    "Thread",
                    "Collapse assistant tool uses",
                )) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    rule
                        .onNodeWithText(label)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertFalse("$label clipped", layouts.single().hasVisualOverflow)
                }
                rule.onNodeWithText("Done").assertIsDisplayed()
                rule.onNodeWithContentDescription("Close").assertIsDisplayed()
                capture(width, height, fontScale)
                shell("input keyevent 4")
                rule.waitUntil(5_000) { dismissals.get() == 1 }
            }
        } finally {
            shell("wm size $oldSize")
            shell("wm density $oldDensity")
            if (oldScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldScale")
            instrumentation.waitForIdleSync()
        }
    }

    private fun capture(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        if (InstrumentationRegistry.getArguments().getString("captureSettingsPixels") != "true") return
        rule.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        val colors = (0 until height step 16).flatMap { y -> (0 until width step 16).map { x -> image.getPixel(x, y) } }
        assertTrue("actual emulator pixels must not be blank", colors.toSet().size > 10)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "settings-1239/api-${Build.VERSION.SDK_INT}",
            ).apply { mkdirs() }
        File(output, "settings-${width}x$height-scale-$fontScale.png")
            .outputStream()
            .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(value: String) = value.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
