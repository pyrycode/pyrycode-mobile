package de.pyryco.mobile.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File

/** Real pixels require the full pixel8Api35 image; ATD runs only reachability checks. */
@RunWith(AndroidJUnit4::class)
class WelcomeAppearanceDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val preferences: AppPreferences get() = GlobalContext.get().get()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var originalStore: PairedServerCollectionStore
    private lateinit var oldTheme: ThemeMode
    private var oldWallpaper = false
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private var oldFontScale = "1.0"
    private lateinit var view: View

    @Before fun prepare() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        oldFontScale = shell("settings get system font_scale").trim()
        runBlocking {
            oldTheme = preferences.themeMode.first()
            oldWallpaper = preferences.useWallpaperColors.first()
            preferences.setThemeMode(ThemeMode.DARK)
            preferences.setUseWallpaperColors(false)
        }
        originalStore = GlobalContext.get().get()
        val unpaired =
            object : PairedServerCollectionStore by originalStore {
                override suspend fun list() = emptyList<de.pyryco.mobile.data.crypto.PairedServerEntry>()
            }
        loadKoinModules(module { single<PairedServerCollectionStore> { unpaired } })
    }

    @After fun restore() {
        scenario?.close()
        if (::originalStore.isInitialized) loadKoinModules(module { single { originalStore } })
        if (::oldTheme.isInitialized) {
            runBlocking {
                preferences.setThemeMode(oldTheme)
                preferences.setUseWallpaperColors(oldWallpaper)
            }
        }
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        if (oldFontScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldFontScale")
        instrumentation.waitForIdleSync()
    }

    @Test fun darkAt412By892() = capture(412, 892)

    @Test fun darkAt360By800() = capture(360, 800)

    @Test fun darkAt360By800LargeText() = capture(360, 800, 1.5f)

    private fun capture(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
    ) {
        shell("wm density 160")
        shell("wm size ${width}x$height")
        shell("settings put system font_scale $fontScale")
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 5_000)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { view = it.window.decorView }
        rule.waitForIdle()
        val density = view.resources.displayMetrics.density
        assertEquals(1f, density, 0.01f)
        assertEquals(fontScale, view.resources.configuration.fontScale, 0.01f)
        val bars =
            rule.runOnIdle {
                checkNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(WindowInsetsCompat.Type.systemBars())
            }
        val body =
            rule
                .onNodeWithText("Pyrycode runs Claude", substring = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInWindow
        assertEquals(minOf(320, width - 64).toFloat(), body.width, 1f)
        assertEquals(32f, body.left, 1f)
        val requirePixels = InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true"
        if (requirePixels) assertTrue("physical system bars required: $bars", bars.top > 0 && bars.bottom > 0)
        if (bars.top > 0 && bars.bottom > 0) {
            val initialBounds =
                mapOf(
                    "title" to rule.onNodeWithText("Pyrycode").fetchSemanticsNode().boundsInWindow,
                    "primary" to rule.onNodeWithText("I already have pyrycode").fetchSemanticsNode().boundsInWindow,
                )
            saveCapture(width, height, fontScale, density, bars, body, "initial", initialBounds)
        }
        for (label in listOf("I already have pyrycode", "Set up pyrycode first", "Open source · github.com/pyrycode/pyrycode-mobile")) {
            val bounds =
                rule
                    .onNodeWithText(label)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInWindow
            if (fontScale == 1f) assertTrue("$label below body", bounds.top >= body.bottom)
            assertTrue("$label clear of bars", bounds.top >= bars.top && bounds.bottom <= height - bars.bottom)
            assertTrue("$label fits horizontally", bounds.left >= 0 && bounds.right <= width)
            if (fontScale > 1f && label == "I already have pyrycode") {
                val textBounds = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
                assertTrue("primary label clipped by button", textBounds.bottom <= bounds.bottom)
                val layouts = mutableListOf<TextLayoutResult>()
                rule
                    .onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("primary label text overflow", layouts.single().let { !it.didOverflowHeight && !it.didOverflowWidth })
            }
        }
        if (bars.top == 0 || bars.bottom == 0) return
        if (fontScale > 1f) {
            rule.waitForIdle()
            instrumentation.waitForIdleSync()
            val visibleBody = rule.onNodeWithText("Pyrycode runs Claude", substring = true).fetchSemanticsNode().boundsInWindow
            val primary = rule.onNodeWithText("I already have pyrycode").fetchSemanticsNode().boundsInWindow
            val secondary = rule.onNodeWithText("Set up pyrycode first").fetchSemanticsNode().boundsInWindow
            val footer = rule.onNodeWithText("Open source · github.com/pyrycode/pyrycode-mobile").fetchSemanticsNode().boundsInWindow
            assertTrue("body and primary overlap", visibleBody.bottom <= primary.top)
            assertTrue("actions overlap", primary.bottom <= secondary.top)
            assertTrue("footer and secondary overlap", secondary.bottom <= footer.top)
            saveCapture(
                width,
                height,
                fontScale,
                density,
                bars,
                body,
                "scrolled",
                mapOf("visibleBody" to visibleBody, "primary" to primary, "secondary" to secondary, "footer" to footer),
            )
        }
    }

    private fun saveCapture(
        width: Int,
        height: Int,
        fontScale: Float,
        density: Float,
        bars: androidx.core.graphics.Insets,
        body: androidx.compose.ui.geometry.Rect,
        state: String,
        labelBounds: Map<String, androidx.compose.ui.geometry.Rect> = emptyMap(),
    ) {
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "welcome-1212",
            ).apply { mkdirs() }
        val name = "${width}x$height-dark-${fontScale}x-$state"
        val deadline = SystemClock.uptimeMillis() + 5_000
        var bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        while (bitmap.getPixel(8, height - 100) != Color.rgb(16, 20, 24) && SystemClock.uptimeMillis() < deadline) {
            bitmap.recycle()
            SystemClock.sleep(100)
            bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        assertEquals("settled static surface pixel", Color.rgb(16, 20, 24), bitmap.getPixel(8, height - 100))
        val colors = (0 until height step 16).flatMap { y -> (0 until width step 16).map { x -> bitmap.getPixel(x, y) } }.toSet()
        assertTrue("capture contains rendered content", colors.size > 10)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$name.txt").writeText(
            "activity=MainActivity api=${Build.VERSION.SDK_INT} sizeDp=${width}x$height density=$density " +
                "systemBarsPx=$bars bodyBoundsPx=$body fontScale=$fontScale state=$state theme=DARK wallpaperColors=false\n" +
                labelBounds.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n",
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
