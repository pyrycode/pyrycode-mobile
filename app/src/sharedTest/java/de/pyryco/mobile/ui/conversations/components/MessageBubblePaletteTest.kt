package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@Config(qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MessageBubblePaletteTest {
    @get:Rule val rule = createComposeRule()

    private var view: View? = null
    private var dark by mutableStateOf(true)
    private var wallpaper by mutableStateOf(false)
    private var streaming by mutableStateOf(true)
    private var userFill = Color.Unspecified
    private var assistantFill = Color.Unspecified
    private var background = Color.Unspecified
    private var userBody = Color.Unspecified
    private var assistantBody = Color.Unspecified
    private var renderedMode: Pair<Boolean, Boolean>? = null

    private fun show(
        isDark: Boolean,
        isWallpaper: Boolean = false,
    ) {
        dark = isDark
        wallpaper = isWallpaper
        rule.setContent {
            // Deliberately disagree with the effective app mode.
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PyrycodeMobileTheme(darkTheme = dark, dynamicColor = wallpaper) {
                    val scheme = MaterialTheme.colorScheme
                    renderedMode = dark to wallpaper
                    userFill = if (dark && !wallpaper) Color(0xFF003355) else scheme.primaryContainer
                    assistantFill = if (dark && !wallpaper) Color(0xFF001D34) else scheme.secondaryContainer
                    background = scheme.background
                    userBody = scheme.onPrimaryContainer
                    assistantBody = scheme.onSecondaryContainer
                    if (!wallpaper) {
                        assertEquals(Color(if (dark) 0xFF134A74 else 0xFFCFE4FF), scheme.primaryContainer)
                        assertEquals(Color(if (dark) 0xFF3A4857 else 0xFFD6E4F7), scheme.secondaryContainer)
                        assertEquals(Color(if (dark) 0xFFCFE4FF else 0xFF134A74), userBody)
                        assertEquals(Color(if (dark) 0xFFD6E4F7 else 0xFF3A4857), assistantBody)
                    }
                    view = LocalView.current
                    Surface(color = background) {
                        Column {
                            MessageBubble(message(Role.User, "User"))
                            MessageBubble(message(Role.Assistant, "Reply"))
                            MessageBubble(message(Role.Assistant, "Live", streaming))
                            QueuedMessageRow(text = "Queued", onDrop = {})
                        }
                    }
                }
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Live", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun message(
        role: Role,
        text: String,
        isStreaming: Boolean = false,
    ) = Message(
        id = text,
        sessionId = "palette-fixture",
        role = role,
        content = text,
        timestamp = Instant.parse("2026-01-13T12:55:00Z"),
        isStreaming = isStreaming,
    )

    private fun assertTextColor(
        node: SemanticsNodeInteraction,
        expected: Color,
    ) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(
            expected,
            results
                .single()
                .layoutInput.style.color,
        )
    }

    private fun assertPalette() {
        val bubbles = rule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG).fetchSemanticsNodes()
        assertEquals(dark to wallpaper, renderedMode)
        assertEquals(3, bubbles.size)
        val queued = rule.onNodeWithText("Queued", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))

            fun assertPixel(
                x: Float,
                y: Float,
                expected: Color,
            ) {
                val actual = Color(bitmap.getPixel(x.toInt(), y.toInt()))
                assertEquals(expected.red, actual.red, 1f / 255f)
                assertEquals(expected.green, actual.green, 1f / 255f)
                assertEquals(expected.blue, actual.blue, 1f / 255f)
            }
            bubbles.forEachIndexed { index, bubble ->
                // Interior padding, clear of rounded corners, text and copy glyphs.
                assertPixel(
                    bubble.boundsInRoot.left + 12,
                    bubble.boundsInRoot.top + 8,
                    if (index == 0) userFill else assistantFill,
                )
            }
            assertPixel(queued.left - 8, queued.top + 2, userFill.copy(alpha = 0.6f).compositeOver(background))
            // Opt-in synthetic evidence, never live message content.
            System.getProperty("bubble.capture.dir")?.let { directory ->
                File(directory, "bubbles-$dark-$wallpaper-$streaming.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            bitmap.recycle()
        }
        assertTextColor(rule.onNodeWithText("User", useUnmergedTree = true), userBody)
        assertTextColor(rule.onNodeWithText("Reply", useUnmergedTree = true), assistantBody)
        assertTextColor(rule.onNodeWithText("Live", substring = true, useUnmergedTree = true), assistantBody)
        assertTextColor(rule.onNodeWithText("Queued", useUnmergedTree = true), userBody)
        val timestamps = rule.onAllNodesWithText(" - ", substring = true, useUnmergedTree = true)
        assertEquals(3, timestamps.fetchSemanticsNodes().size)
        for (index in 0..2) {
            assertTextColor(
                timestamps[index],
                if (dark && !wallpaper) Color(0xFF32628D) else (if (index == 0) userBody else assistantBody).copy(alpha = 0.8f),
            )
        }
    }

    @Test fun staticDarkMatchesReferenceThroughFinalization() {
        show(isDark = true)
        assertPalette()
        rule.runOnIdle { streaming = false }
        rule.waitForIdle()
        rule.onNodeWithText("Live", useUnmergedTree = true).assertExists()
        assertPalette()
    }

    @Test fun staticLightRetainsContainers() {
        show(isDark = false)
        assertPalette()
    }

    @Test fun wallpaperDarkRetainsContainers() {
        show(isDark = true, isWallpaper = true)
        assertPalette()
    }

    @Test fun wallpaperLightRetainsContainers() {
        show(isDark = false, isWallpaper = true)
        assertPalette()
    }

    @Test fun themeChangesUpdateAlreadyComposedBubbles() {
        show(isDark = false)
        assertPalette()
        rule.runOnIdle { dark = true }
        rule.waitForIdle()
        assertPalette()
        rule.runOnIdle { wallpaper = true }
        rule.waitForIdle()
        assertPalette()
    }
}
