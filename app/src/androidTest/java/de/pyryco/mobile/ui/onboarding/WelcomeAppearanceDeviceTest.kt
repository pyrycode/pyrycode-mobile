package de.pyryco.mobile.ui.onboarding

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    private lateinit var view: View

    @Before fun prepare() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
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
        instrumentation.waitForIdleSync()
    }

    @Test fun darkAt412By892() = capture(412, 892)

    @Test fun darkAt360By800() = capture(360, 800)

    private fun capture(
        width: Int,
        height: Int,
    ) {
        shell("wm density 160")
        shell("wm size ${width}x$height")
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 5_000)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { view = it.window.decorView }
        rule.waitForIdle()
        val density = view.resources.displayMetrics.density
        assertEquals(1f, density, 0.01f)
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
        for (label in listOf("I already have pyrycode", "Set up pyrycode first", "Open source · github.com/pyrycode/pyrycode-mobile")) {
            val bounds =
                rule
                    .onNodeWithText(label)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInWindow
            assertTrue("$label below body", bounds.top >= body.bottom)
            assertTrue("$label clear of bars", bounds.top >= bars.top && bounds.bottom <= height - bars.bottom)
            assertTrue("$label fits horizontally", bounds.left >= 0 && bounds.right <= width)
        }
        val requirePixels = InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true"
        if (requirePixels) assertTrue("physical system bars required: $bars", bars.top > 0 && bars.bottom > 0)
        if (bars.top == 0 || bars.bottom == 0) return
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "welcome-1150",
            ).apply { mkdirs() }
        val name = "${width}x$height-dark"
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val colors = (0 until height step 16).flatMap { y -> (0 until width step 16).map { x -> bitmap.getPixel(x, y) } }.toSet()
        assertTrue("capture contains rendered content", colors.size > 10)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$name.txt").writeText(
            "activity=MainActivity api=${Build.VERSION.SDK_INT} sizeDp=${width}x$height density=$density " +
                "systemBarsPx=$bars bodyBoundsPx=$body theme=DARK wallpaperColors=false\n",
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
