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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import de.pyryco.mobile.ui.theme.threadColors
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private var actionBacking = Color.Unspecified
    private var actionTint = Color.Unspecified
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
                    background = scheme.threadColors.background
                    actionTint = scheme.inversePrimary
                    actionBacking = scheme.inverseSurface
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
        val glyphs = rule.onAllNodesWithTag("message-copy-glyph", useUnmergedTree = true).fetchSemanticsNodes() +
            rule.onAllNodesWithTag("message-reply-glyph", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(6, glyphs.size)

        fun contrast(
            first: Color,
            second: Color,
        ): Float {
            val firstLuminance = first.luminance()
            val secondLuminance = second.luminance()
            return (maxOf(firstLuminance, secondLuminance) + 0.05f) / (minOf(firstLuminance, secondLuminance) + 0.05f)
        }
        // #1818 pairs the inverted accent with its contrast surface for both controls.
        val glyphContrast = contrast(actionTint, actionBacking)
        assertTrue("action contrast must be at least 3:1; got $glyphContrast", glyphContrast >= 3f)
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
            glyphs.forEach { glyph ->
                val bounds = glyph.boundsInRoot
                assertPixel(bounds.left + 1, bounds.top - 1, actionBacking)
                val expected = actionTint.toArgb()
                var matchingPixels = 0
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                        if (bitmap.getPixel(x, y) == expected) matchingPixels++
                    }
                }
                assertTrue("both actions must use the scheme inversePrimary tint", matchingPixels > 0)
            }
            bitmap.recycle()
        }
        assertTextColor(rule.onNodeWithText("User", useUnmergedTree = true), userBody)
        assertTextColor(rule.onNodeWithText("Reply", useUnmergedTree = true), assistantBody)
        assertTextColor(rule.onNodeWithText("Live", substring = true, useUnmergedTree = true), assistantBody)
        assertTextColor(rule.onNodeWithText("Queued", useUnmergedTree = true), userBody)
        val timestamps = rule.onAllNodesWithText(" - ", substring = true, useUnmergedTree = true)
        assertEquals(if (streaming) 2 else 3, timestamps.fetchSemanticsNodes().size)
        for (index in 0 until timestamps.fetchSemanticsNodes().size) {
            val body = if (index == 0) userBody else assistantBody
            assertTextColor(
                timestamps[index],
                body.copy(alpha = 0.8f),
            )
            if (dark && !wallpaper) {
                val fill = if (index == 0) userFill else assistantFill
                val foreground = body.copy(alpha = 0.8f).compositeOver(fill).luminance()
                val background = fill.luminance()
                val contrast = (foreground + 0.05f) / (background + 0.05f)
                assertTrue("metadata contrast must be at least 4.5:1; got $contrast", contrast >= 4.5f)
            }
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
