package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ThreadInputBarStyleTest {
    @get:Rule val rule = createComposeRule()

    private var view: View? = null
    private var expectedWell = Color.Unspecified
    private var expectedGlyph = Color.Unspecified
    private var sends = 0
    private var stops = 0

    private fun show(
        dark: Boolean,
        wallpaper: Boolean = false,
        busy: Boolean = false,
        sending: Boolean = false,
    ) {
        sends = 0
        stops = 0
        var draft by mutableStateOf("")
        rule.setContent {
            // The app explicitly chooses the opposite of the system in every case.
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PyrycodeMobileTheme(darkTheme = dark, dynamicColor = wallpaper) {
                    val scheme = MaterialTheme.colorScheme
                    expectedGlyph = scheme.primary
                    expectedWell =
                        (
                            if (dark && !wallpaper) {
                                Color(0xFF003355).copy(alpha = 0.41f)
                            } else {
                                scheme.surfaceContainerHigh
                            }
                        ).compositeOver(scheme.background)
                    view = LocalView.current
                    Surface(color = scheme.background) {
                        Box {
                            ThreadInputBar(
                                text = draft,
                                onTextChange = { draft = it },
                                onSend = { sends++ },
                                modifier = Modifier.testTag("composer"),
                                isBusy = busy,
                                onInterrupt = { stops++ },
                                sending = sending,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertWell() {
        val bounds = rule.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            // Clear fill inside the rounded corner, above text and away from the action glyph.
            val actual = Color(bitmap.getPixel((bounds.left + 12).toInt(), (bounds.top + 8).toInt()))
            assertEquals(expectedWell.red, actual.red, 1f / 255f)
            assertEquals(expectedWell.green, actual.green, 1f / 255f)
            assertEquals(expectedWell.blue, actual.blue, 1f / 255f)
            // Optional local render for comparing the field with Figma; ordinary runs write no files.
            System.getProperty("composer.capture.dir")?.let { directory ->
                val field =
                    Bitmap.createBitmap(
                        bitmap,
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.width.toInt(),
                        bounds.height.toInt(),
                    )
                File(directory, "composer-${expectedWell.toArgb()}.png").outputStream().use {
                    field.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                field.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertBodyMedium(node: SemanticsNodeInteraction) {
        val style = layout(node).layoutInput.style
        assertEquals(14.sp, style.fontSize)
        assertEquals(20.sp, style.lineHeight)
    }

    private fun assertEmptyFocusedAndTyped() {
        assertWell()
        assertBodyMedium(rule.onNodeWithText("Message", useUnmergedTree = true))
        rule.onNodeWithTag("composer").assertHeightIsAtLeast(52.dp)
        rule
            .onNodeWithContentDescription("Send message")
            .assertHeightIsEqualTo(48.dp)
            .assertWidthIsEqualTo(48.dp)
        val field = rule.onNode(hasSetTextAction())
        field.performClick().assertIsFocused()
        assertWell()
        field.performTextInput("My message")
        assertBodyMedium(field)
        assertWell()
    }

    @Test fun staticDarkOverridesLightSystem() {
        show(dark = true)
        assertEmptyFocusedAndTyped()
    }

    @Test fun darkSendCircleIsFilledAndDisabledStateIsVisiblyDimmed() {
        show(dark = true)
        val send = rule.onNodeWithContentDescription("Send message")
        send.assertIsNotEnabled()
        val disabled = sampleSendCircle(send)

        rule.onNode(hasSetTextAction()).performTextInput("My message")
        send.assertIsEnabled()
        val active = sampleSendCircle(send)
        assertTrue("enabled send circle should be visibly brighter", active.red > disabled.red)
        assertTrue("designed send icon has a filled top-center edge", active.blue > 0.65f)
    }

    @Test fun stopMatchesFullCircleAndRoundedCutoutInCentered48DpButton() {
        show(dark = true, busy = true)
        val stop = rule.onNodeWithContentDescription("Stop the running turn")
        stop.assertIsEnabled().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val bounds = stop.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val scale = bounds.width / 48f

            fun pixel(
                x: Float,
                y: Float,
            ): Color = Color(bitmap.getPixel((bounds.left + x * scale).toInt(), (bounds.top + y * scale).toInt()))

            fun assertPixel(
                x: Float,
                y: Float,
                expected: Color,
            ) {
                val actual = pixel(x, y)
                assertEquals("red at $x,$y", expected.red, actual.red, 2f / 255f)
                assertEquals("green at $x,$y", expected.green, actual.green, 2f / 255f)
                assertEquals("blue at $x,$y", expected.blue, actual.blue, 2f / 255f)
            }
            // Figma 114:3549: 28dp circle starts 10dp inside the 48dp button.
            assertPixel(24f, 11f, expectedGlyph)
            assertPixel(24f, 36f, expectedGlyph)
            assertPixel(11f, 24f, expectedGlyph)
            assertPixel(36f, 24f, expectedGlyph)
            assertPixel(24f, 7f, expectedWell)
            assertPixel(7f, 24f, expectedWell)
            assertPixel(24f, 24f, expectedWell)
            // The square cutout has rounded corners, rather than Material StopCircle's sharp ones: the cutout runs
            // from 18.75 dp with a 1.75 dp corner radius, so the curve crosses the diagonal near 19.26 dp.
            val corner = pixel(19f, 19f)
            assertTrue("rounded corner retains circle coverage", corner.red > expectedWell.red + 0.05f)
            if (scale < 2f) {
                // At density 1 the curve falls inside this one pixel, so it is part glyph, part well.
                assertTrue("rounded corner is antialiased", corner.red < expectedGlyph.red - 0.05f)
            } else {
                // On the device a pixel is under half a dp, so just inside the curve is already the well,
                // which a corner rounded wider than designed would still cover.
                assertTrue("rounded corner is no wider than designed", pixel(19.6f, 19.6f).red < expectedGlyph.red - 0.05f)
            }
            assertPixel(21f, 21f, expectedWell)
            bitmap.recycle()
        }
    }

    @Test fun busyEmptyStopsButTypedTextSends() {
        show(dark = true, busy = true)
        rule.onNodeWithContentDescription("Stop the running turn").assertIsEnabled().performClick()
        assertEquals(1, stops)
        rule.onNode(hasSetTextAction()).performTextInput("queued message")
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        assertEquals(1, sends)
        assertEquals(1, stops)
    }

    @Test fun sendingAttachmentDisablesDuplicateTap() {
        show(dark = true, sending = true)
        rule.onNode(hasSetTextAction()).performTextInput("with files")
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    private fun sampleSendCircle(send: SemanticsNodeInteraction): Color {
        val bounds = send.fetchSemanticsNode().boundsInRoot
        // 11 dp down the 48 dp button, just inside the top of its 28 dp circle, at any density.
        val scale = bounds.width / 48f
        return rule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val color =
                Color(bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + 11 * scale).toInt()))
            bitmap.recycle()
            color
        }
    }

    @Test fun staticLightOverridesDarkSystem() {
        show(dark = false)
        assertEmptyFocusedAndTyped()
    }

    @Test fun wallpaperDarkRetainsExistingFill() {
        show(dark = true, wallpaper = true)
        assertEmptyFocusedAndTyped()
    }

    @Test fun wallpaperLightRetainsExistingFill() {
        show(dark = false, wallpaper = true)
        assertEmptyFocusedAndTyped()
    }

    @Test fun textWrapsAndFieldStopsGrowingAfterFiveLines() {
        show(dark = true)
        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("A wrapped composer message ".repeat(8))
        assertTrue(layout(field).lineCount > 1)
        field.performTextReplacement("one\ntwo\nthree\nfour\nfive")
        val fiveLineHeight =
            rule
                .onNodeWithTag("composer")
                .fetchSemanticsNode()
                .boundsInRoot.height
        field.performTextReplacement("one\ntwo\nthree\nfour\nfive\nsix")
        assertEquals(6, layout(field).lineCount)
        assertEquals(
            fiveLineHeight,
            rule
                .onNodeWithTag("composer")
                .fetchSemanticsNode()
                .boundsInRoot.height,
        )
    }
}
