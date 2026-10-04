package de.pyryco.mobile.design

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.ui.components.MobileModalTestIme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File

/**
 * Drives the real [MainActivity] over the fake graph for design captures. Use after [ViewportRule] and an
 * empty compose rule: `@get:Rule(order = 2) val design = DesignCapture(rule)`.
 *
 * It selects the test IME before any launch (selecting it under a running activity can recreate it), grants
 * camera and notification permission, pins the fixed dark theme with wallpaper colours off, controls whether
 * startup finds a paired host ([paired]), and installs [inputs]. Everything is restored afterwards.
 */
class DesignCapture(
    private val rule: ComposeTestRule,
) : TestRule {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val imeId get() = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"

    /** The Koin override for this test. */
    val inputs = DesignInputs()

    /** Whether startup finds a paired host, which opens the channel list instead of Welcome. */
    var paired = false

    var scenario: ActivityScenario<MainActivity>? = null
        private set
    lateinit var view: View
        private set
    private var appliedInsets: WindowInsetsCompat? = null
    private var syntheticBars = false

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = instrumentation.targetContext
                val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                val imeEnabled =
                    context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == imeId }
                val koin = GlobalContext.get()
                val originalStore = koin.get<PairedServerCollectionStore>()
                val preferences = koin.get<AppPreferences>()
                val oldTheme = runBlocking { preferences.themeMode.first() }
                val oldWallpaper = runBlocking { preferences.useWallpaperColors.first() }
                try {
                    shell("ime enable $imeId")
                    shell("ime set $imeId")
                    // Not revoked afterwards: revoking a runtime permission kills the app process, and with it
                    // this instrumentation. ScannerDeniedRouteDeviceTest sorts ahead of the design package.
                    for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)) {
                        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
                    }
                    runBlocking {
                        preferences.setThemeMode(ThemeMode.DARK)
                        preferences.setUseWallpaperColors(false)
                    }
                    val startupStore =
                        object : PairedServerCollectionStore by originalStore {
                            override suspend fun list() =
                                if (paired) {
                                    listOf(PairedServerEntry(PairedServer("demo", "unused", "wss://demo.invalid", "unused")))
                                } else {
                                    emptyList()
                                }
                        }
                    loadKoinModules(module { single<PairedServerCollectionStore> { startupStore } })
                    inputs.install()
                    instrumentation.waitForIdleSync()
                    base.evaluate()
                } finally {
                    scenario?.close()
                    scenario = null
                    inputs.uninstall()
                    loadKoinModules(module { single { originalStore } })
                    runBlocking {
                        preferences.setThemeMode(oldTheme)
                        preferences.setUseWallpaperColors(oldWallpaper)
                    }
                    oldIme?.takeIf { it.isNotEmpty() }?.let { shell("ime set $it") }
                    if (!imeEnabled) shell("ime disable $imeId")
                    if (oldIme.isNullOrEmpty()) shell("settings delete secure default_input_method")
                    instrumentation.waitForIdleSync()
                }
            }
        }

    /** Launches (or relaunches) [MainActivity] at the viewport [ViewportRule] applied. */
    fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        checkNotNull(scenario).onActivity {
            val content = it.findViewById<ViewGroup>(android.R.id.content)
            view = content.getChildAt(0)
            // ATD can report zero system bars; flag it so captures skip pixels and geometry still sees bars.
            ViewCompat.setOnApplyWindowInsetsListener(content) { _, incoming ->
                val bars = incoming.getInsets(WindowInsetsCompat.Type.systemBars())
                syntheticBars = bars.top == 0 && bars.bottom == 0
                val band = (24 * content.resources.displayMetrics.density).toInt()
                val effective =
                    if (syntheticBars) {
                        WindowInsetsCompat
                            .Builder(incoming)
                            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, band, 0, 0))
                            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, band))
                            .build()
                    } else {
                        incoming
                    }
                appliedInsets = effective
                effective
            }
            ViewCompat.requestApplyInsets(content)
        }
        rule.waitForIdle()
    }

    fun insets(): WindowInsetsCompat = rule.runOnIdle { checkNotNull(appliedInsets) }

    /** Focuses [field] and waits for the test IME to report a nonzero inset. */
    fun openKeyboard(field: SemanticsNodeInteraction) {
        // A system crash dialog can steal window focus on the emulator; dismiss it as MobileModalTest does.
        rule.waitUntil(10_000) {
            val focused = rule.runOnIdle { view.hasWindowFocus() }
            if (!focused) shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            focused
        }
        field.performClick()
        rule.runOnIdle { view.windowInsetsController?.show(WindowInsets.Type.ime()) }
        awaitKeyboard(true)
    }

    fun closeKeyboard() {
        Espresso.pressBack()
        awaitKeyboard(false)
    }

    /** Header actions share the activity window, so await an unconditional row rather than a popup root. */
    fun openHeaderMenu() {
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Channel info").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        rule.waitForIdle()
    }

    /**
     * Saves the screen as `<additionalTestOutputDir>/design-1220/<folder>/<name>.png` with a `.txt` beside it.
     * Fails on a blank frame and, under `requireRealSystemBars=true`, on synthetic bars. On synthetic bars
     * without that argument only the metadata is written, because ATD framebuffers can be black.
     */
    fun capture(
        folder: String,
        name: String,
        figmaNode: String,
    ) {
        rule.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "design-1220/$folder",
            ).apply { mkdirs() }
        val metrics = view.resources.displayMetrics
        val bars = insets().getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets().getInsets(WindowInsetsCompat.Type.ime())
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
            assertTrue("real system bars required for design evidence", !syntheticBars && bars.top > 0 && bars.bottom > 0)
            assertTrue("hardware-rendered window required for visual evidence", view.isHardwareAccelerated)
        }
        File(output, "$name.txt").writeText(
            "activity=MainActivity figma=$figmaNode sizePx=${metrics.widthPixels}x${metrics.heightPixels} " +
                "density=${metrics.density} fontScale=${view.resources.configuration.fontScale} staticDark=true " +
                "hardwareAccelerated=${view.isHardwareAccelerated} syntheticBars=$syntheticBars systemBarsPx=$bars imePx=$ime " +
                "api=${Build.VERSION.SDK_INT} device=${Build.MODEL}\n",
        )
        if (syntheticBars) return
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val colors = (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }
        assertTrue("capture must contain rendered content", colors.toSet().size > 10)
        assertTrue("capture must not be black", colors.any { (it and 0xFFFFFF) > 0x101010 })
        assertEquals("capture matches the window", view.rootView.width, bitmap.width)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun awaitKeyboard(visible: Boolean) {
        rule.waitUntil(5_000) {
            val current = insets()
            current.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                (current.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0) == visible
        }
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        rule.waitForIdle()
    }
}
