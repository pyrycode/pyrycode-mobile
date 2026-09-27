package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ThreadCanvasPaletteTest {
    @get:Rule val rule = createComposeRule()

    private var view: View? = null
    private var density = 1f
    private var expectedWidth = 0
    private var dark by mutableStateOf(true)
    private var wallpaper by mutableStateOf(false)
    private var reader by mutableStateOf(false)
    private var populated by mutableStateOf(false)
    private var background = Color.Unspecified
    private var surface = Color.Unspecified
    private var divider = Color.Unspecified
    private var foreground = Color.Unspecified
    private var titleColor = Color.Unspecified
    private var renderedMode: Pair<Boolean, Boolean>? = null

    private fun show(
        isDark: Boolean = true,
        isWallpaper: Boolean = false,
    ) {
        dark = isDark
        wallpaper = isWallpaper
        rule.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PyrycodeMobileTheme(darkTheme = dark, dynamicColor = wallpaper) {
                    val scheme = MaterialTheme.colorScheme
                    renderedMode = dark to wallpaper
                    background = if (dark && !wallpaper) Color(0xFF0B0E11) else scheme.background
                    surface = if (dark && !wallpaper) Color(0xFF0B0E11) else scheme.surface
                    divider = (if (dark && !wallpaper) Color(0xFF32628D) else scheme.outlineVariant).copy(alpha = 0.6f)
                    foreground = scheme.onSurface
                    titleColor = scheme.onPrimaryContainer
                    expectedWidth = LocalWindowInfo.current.containerSize.width
                    if (!wallpaper) {
                        assertEquals(Color(if (dark) 0xFF101418 else 0xFFF8F9FF), scheme.surface)
                        assertEquals(scheme.surface, scheme.background)
                    }
                    density = LocalDensity.current.density
                    view = LocalView.current
                    if (reader) {
                        MarkdownReaderScreen(
                            document = MarkdownDocument("Canvas.md", if (populated) longNote else "Reader body"),
                            onBack = {},
                            modifier = Modifier.testTag("canvas"),
                        )
                    } else {
                        ThreadScreen(
                            state =
                                ThreadUiState(
                                    conversationId = "canvas-fixture",
                                    displayName = "Canvas thread",
                                    isPromoted = true,
                                    hasMessages = populated,
                                    items = if (populated) listOf(ThreadItem.MessageItem(message)) else emptyList(),
                                ),
                            onBack = {},
                            onSendMessage = {},
                            connectionState = ConnectionState.Connected,
                            onRetry = {},
                            modifier = Modifier.testTag("canvas"),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertCanvas() {
        val bounds = rule.onNodeWithTag("canvas").fetchSemanticsNode().boundsInRoot
        assertEquals(dark to wallpaper, renderedMode)
        assertEquals(expectedWidth.toFloat(), bounds.width, 1f)
        val title = if (reader) "Canvas.md" else "Canvas thread"
        rule.onNodeWithText(title).assertIsDisplayed()
        val titleResults = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleResults) }
        assertEquals(
            titleColor,
            titleResults
                .single()
                .layoutInput.style.color,
        )
        rule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))

            fun pixel(
                x: Float,
                y: Float,
                expected: Color,
            ) {
                val actual = Color(bitmap.getPixel((bounds.left + x * density).toInt(), (bounds.top + y * density).toInt()))
                val location = "$title ($x,$y) dark=$dark wallpaper=$wallpaper"
                assertEquals("$location red", expected.red, actual.red, 1f / 255f)
                assertEquals("$location green", expected.green, actual.green, 1f / 255f)
                assertEquals("$location blue", expected.blue, actual.blue, 1f / 255f)
            }
            val canvas = if (reader) surface else background
            pixel(2f, 30f, canvas) // Header, outside the back glyph.
            pixel(2f, 200f, canvas) // Empty/populated message or reader gutter.
            pixel(160f, 64.5f, divider.compositeOver(canvas)) // Existing 1dp rule.
            pixel(10f, 64.5f, canvas) // Retained 20dp rule inset.
            pixel(160f, 63f, canvas)
            pixel(160f, 66f, canvas)
            pixel(2f, bounds.height / density - 30f, surface) // Composer surround or trailing reader space.
            if (!populated) pixel(160f, 300f, canvas)
            System.getProperty("canvas.capture.dir")?.let { directory ->
                File(directory, "canvas-$reader-$dark-$wallpaper-$populated.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            bitmap.recycle()
        }
        if (reader && !populated) {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("Reader body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(
                foreground,
                results
                    .single()
                    .layoutInput.style.color,
            )
        }
    }

    private fun assertBothScreens() {
        assertCanvas()
        rule.runOnIdle { populated = true }
        rule.waitForIdle()
        rule.onNodeWithText("Synthetic reply").assertIsDisplayed()
        assertCanvas()
        rule.runOnIdle {
            reader = true
            populated = false
        }
        rule.waitForIdle()
        assertCanvas()
        rule.runOnIdle { populated = true }
        rule.waitForIdle()
        rule.onNodeWithText("Paragraph 60").performScrollTo().assertIsDisplayed()
        assertCanvas()
    }

    @Test fun staticDarkMatchesBothReferenceCanvasesAndRules() {
        show()
        assertBothScreens()
    }

    @Test fun staticLightRetainsBothCanvasesAndRules() {
        show(isDark = false)
        assertBothScreens()
    }

    @Test fun wallpaperDarkRetainsBothCanvasesAndRules() {
        show(isWallpaper = true)
        assertBothScreens()
    }

    @Test fun wallpaperLightRetainsBothCanvasesAndRules() {
        show(isDark = false, isWallpaper = true)
        assertBothScreens()
    }

    @Test fun themeChangesUpdateAlreadyComposedScreens() {
        show(isDark = false)
        for (showReader in listOf(false, true)) {
            rule.runOnIdle { reader = showReader }
            for ((newDark, newWallpaper) in listOf(true to false, true to true, false to true, false to false)) {
                rule.runOnIdle {
                    dark = newDark
                    wallpaper = newWallpaper
                }
                rule.waitForIdle()
                assertCanvas()
            }
        }
    }

    private val longNote = (1..60).joinToString("\n\n") { "Paragraph $it" }
    private val message =
        Message(
            id = "canvas-message",
            sessionId = "canvas-session",
            role = Role.Assistant,
            content = "Synthetic reply",
            timestamp = Instant.parse("2026-01-13T12:55:00Z"),
            isStreaming = false,
        )
}
