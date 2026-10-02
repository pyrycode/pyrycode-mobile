package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** Real pixels for Figma 553:2574 and the reader states that node does not depict. */
@RunWith(AndroidJUnit4::class)
class MarkdownReaderCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var view: View? = null

    /** The display size a test captures at; without it the viewport rule applies 412x892. */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    private annotation class Viewport(
        val size: String,
    )

    // Resizing under a running activity can recreate or refocus it, so the final size is
    // applied here before the compose rule launches its activity, and restored after it ends.
    @get:Rule(order = 0)
    val viewport =
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    val size = overrideOf(shell("wm size"))
                    val density = overrideOf(shell("wm density"))
                    shell("wm density 160")
                    shell("wm size ${description.getAnnotation(Viewport::class.java)?.size ?: "412x892"}")
                    try {
                        instrumentation.waitForIdleSync()
                        base.evaluate()
                    } finally {
                        shell("wm size $size")
                        shell("wm density $density")
                        instrumentation.waitForIdleSync()
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test fun referenceAndMenuAt412By892() {
        show("Builder Pipeline - Plan.md", REFERENCE_MARKDOWN, 1f)
        rule.onNodeWithText("Builder Pipeline Plan").assertIsDisplayed()
        rule.onNodeWithText("Tokens first, running time second.").assertIsDisplayed()
        capture("reference-412x892.png", 412, 892, 1f)

        rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        rule.onNodeWithText(string(R.string.markdown_reader_save_to_device)).assertIsDisplayed()
        rule.onNodeWithTag("markdown-reader-menu").assertIsDisplayed()
        capture("menu-412x892.png", 412, 892, 1f, systemWindow = true)
    }

    @Viewport("320x700")
    @Test
    fun compactLargeTextKeepsControlsAndBodyReachable() {
        show(
            "builder-pipeline-plan-with-a-long-description-that-must-stay-readable.md",
            REFERENCE_MARKDOWN + "\n\n" + (1..30).joinToString("\n\n") { "Paragraph $it" },
            1.5f,
        )
        rule.onNodeWithContentDescription(string(R.string.cd_back)).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).assertIsDisplayed()
        capture("compact-large-text-320x700.png", 320, 700, 1.5f)
        rule.onNodeWithText("Paragraph 30").performScrollTo().assertIsDisplayed()
        capture("compact-scrolled-320x700.png", 320, 700, 1.5f)
        rule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        rule.onNodeWithText(string(R.string.markdown_reader_save_to_device)).assertIsDisplayed()
        rule.onNodeWithTag("markdown-reader-menu").assertIsDisplayed()
        capture("compact-menu-320x700.png", 320, 700, 1.5f, systemWindow = true)
    }

    private fun show(
        name: String,
        markdown: String,
        fontScale: Float,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    view = LocalView.current
                    MarkdownReaderScreen(MarkdownDocument(name, markdown), onBack = {})
                }
            }
        }
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        fontScale: Float,
        systemWindow: Boolean = false,
    ) {
        rule.waitForIdle()
        val bitmap =
            if (systemWindow) {
                rule.runOnIdle {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        val canvas = Canvas(bitmap)
                        val global = Class.forName("android.view.WindowManagerGlobal")
                        val manager = global.getMethod("getInstance").invoke(null)
                        val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<*>
                        roots.filterIsInstance<View>().filter(View::isShown).forEach { window ->
                            val origin = IntArray(2)
                            window.getLocationOnScreen(origin)
                            canvas.save()
                            canvas.translate(origin[0].toFloat(), origin[1].toFloat())
                            window.draw(canvas)
                            canvas.restore()
                        }
                    }
                }
            } else {
                rule.runOnIdle {
                    val root = checkNotNull(view).rootView
                    Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
                }
            }
        if (!systemWindow) {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
        }
        val samples =
            (0 until bitmap.height step 4).flatMap { y -> (0 until bitmap.width step 4).map { x -> bitmap.getPixel(x, y) } }
        if (samples.toSet().size <= 10) {
            // The headless ATD may return a blank framebuffer; the full Pixel 8 run supplies visual evidence.
            Log.w("MarkdownReaderCapture", "Blank framebuffer; skipped $name")
            bitmap.recycle()
            return
        }
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "markdown-reader-1291")
                .apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText(
            "api=${Build.VERSION.SDK_INT} sizeDp=${width}x$height density=1 fontScale=$fontScale " +
                "staticDark=true design=553:2574 inspected=2026-09-30 menuComposite=$systemWindow " +
                "capturePixels=${bitmap.width}x${bitmap.height}\n",
        )
        bitmap.recycle()
    }

    private fun string(id: Int) = instrumentation.targetContext.getString(id)

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String): String =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"

    private companion object {
        const val REFERENCE_MARKDOWN =
            "# Builder Pipeline Plan\n\nThe main board runs four roles since 2026-09-01. Each ticket moves from " +
                "[refiner](https://example.com) to builder, then through review and the merge gate.\n\n## Next steps\n\n" +
                "- Pin all five agents repos to one dispatcher version\n- Measure token spend per role\n" +
                "- Retire the old single-role runner\n\n```\npyry release --both\npyry status\n```\n\n" +
                "> Tokens first, running time second."
    }
}
