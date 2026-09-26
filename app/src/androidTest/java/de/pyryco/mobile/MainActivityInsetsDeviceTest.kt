package de.pyryco.mobile

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.ui.components.MobileModalTestIme
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

/** Real activity, display metrics, system insets and test-only software keyboard. */
@RunWith(AndroidJUnit4::class)
class MainActivityInsetsDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var view: View
    private lateinit var originalStore: PairedServerCollectionStore
    private var paired = false
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private var oldIme: String? = null
    private var imeEnabled = false
    private val imeId get() = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
    private var width = 0
    private var height = 0
    private var density = 0f
    private var syntheticBars = false
    private var appliedInsets: WindowInsetsCompat? = null
    private val output by lazy {
        File(
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "insets-1149/api-${Build.VERSION.SDK_INT}",
        ).apply { mkdirs() }
    }

    @Before fun prepare() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        val context = instrumentation.targetContext
        oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        imeEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == imeId }
        shell("ime enable $imeId")
        shell("ime set $imeId")
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        originalStore = GlobalContext.get().get()
        // Only control the startup snapshot. Demo destinations use their existing fake repository;
        // no synthetic record is saved and no real connection or credential is needed.
        val startupStore =
            object : PairedServerCollectionStore by originalStore {
                override suspend fun list() =
                    if (paired) listOf(PairedServerEntry(PairedServer("demo", "unused", "wss://demo.invalid", "unused"))) else emptyList()
            }
        loadKoinModules(module { single<PairedServerCollectionStore> { startupStore } })
        instrumentation.waitForIdleSync()
    }

    @After fun restore() {
        scenario?.close()
        if (::originalStore.isInitialized) loadKoinModules(module { single { originalStore } })
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        oldIme?.takeIf { it.isNotEmpty() }?.let { shell("ime set $it") }
        if (!imeEnabled) shell("ime disable $imeId")
        if (oldIme.isNullOrEmpty()) shell("settings delete secure default_input_method")
        instrumentation.waitForIdleSync()
    }

    @Test fun activityAt412By892() = exercise(412, 892)

    @Test fun activityAt360By800() = exercise(360, 800)

    private fun exercise(
        widthDp: Int,
        heightDp: Int,
    ) {
        width = widthDp
        height = heightDp
        shell("wm density 160")
        shell("wm size ${width}x$height")
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 5_000)
        launch()
        rule.onNodeWithText("Pyrycode Mobile").assertIsDisplayed()
        density = view.resources.displayMetrics.density
        assertEquals(1f, density, 0.01f)
        val bars = insets().getInsets(WindowInsetsCompat.Type.systemBars())
        assertTrue("nonzero system bars: $bars", bars.top > 0 && bars.bottom > 0)
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
            assertTrue("full-image evidence must use physical system bars", !syntheticBars)
        }
        assertEquals(bars.top + 300 * density, bounds(rule.onNodeWithText("Pyrycode Mobile")).top, 1f)
        val footer = rule.onNodeWithText("Open source · github.com/pyrycode/pyrycode-mobile")
        assertEquals(height - bars.bottom - 16 * density, bounds(footer).bottom, 1f)
        capture("welcome")

        rule.onNodeWithText("I already have pyrycode").performClick()
        val paste = rule.onNodeWithText("Trouble scanning? Paste the pairing code instead")
        paste.assertIsDisplayed()
        val scannerTop = bounds(rule.onNodeWithContentDescription("Back")).top
        assertEquals(bars.top + 18 * density, scannerTop, 1f)
        clearOfBars(paste)
        capture("scanner")
        paste.performClick()
        assertEquals(scannerTop, bounds(rule.onNodeWithContentDescription("Back")).top, 1f)
        for (label in listOf("Host name", "Pairing code", "Pair", "Cancel")) clearOfBars(rule.onNodeWithText(label))
        capture("pair")

        rule.onNodeWithText("Host name").performClick()
        rule.waitUntil(5_000) { insets().isVisible(WindowInsetsCompat.Type.ime()) }
        for (label in listOf("Host name", "Pairing code", "Pair", "Cancel")) {
            val node = rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val ime = insets().getInsets(WindowInsetsCompat.Type.ime()).bottom
            assertTrue("visible keyboard for $label", insets().isVisible(WindowInsetsCompat.Type.ime()) && ime > 0)
            assertTrue("$label above keyboard", bounds(node).bottom <= height - ime + 1)
            capture("ime-${label.lowercase().replace(' ', '-')}")
        }
        Espresso.pressBack()
        rule.waitUntil(5_000) { !insets().isVisible(WindowInsetsCompat.Type.ime()) }
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        paste.assertIsDisplayed()

        scenario?.close()
        paired = true
        launch()
        val settings = rule.onNodeWithContentDescription("Open settings")
        clearOfBars(settings)
        capture("list")
        settings.performClick()
        clearOfBars(rule.onNodeWithContentDescription("Back"))
        clearOfBars(rule.onNodeWithText("Theme"))
        capture("settings")
        Espresso.pressBack()
        rule.onNodeWithText("Pyrycode Mobile").performScrollTo().performClick()
        clearOfBars(rule.onNodeWithContentDescription("Back"))
        clearOfBars(rule.onNodeWithContentDescription("More actions"))
        clearOfBars(rule.onNodeWithContentDescription("Send message"))
        capture("thread")
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity {
            val content = it.findViewById<ViewGroup>(android.R.id.content)
            view = content.getChildAt(0)
            ViewCompat.setOnApplyWindowInsetsListener(content) { _, incoming ->
                val bars = incoming.getInsets(WindowInsetsCompat.Type.systemBars())
                syntheticBars = bars.top == 0 && bars.bottom == 0
                val effective =
                    if (syntheticBars) {
                        val band = (24 * view.resources.displayMetrics.density).toInt()
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

    private fun bounds(node: SemanticsNodeInteraction) = node.assertIsDisplayed().fetchSemanticsNode().boundsInWindow

    private fun clearOfBars(node: SemanticsNodeInteraction) {
        val bounds = bounds(node)
        val bars = insets().getInsets(WindowInsetsCompat.Type.systemBars())
        assertTrue("$bounds below $bars", bounds.top >= bars.top)
        assertTrue("$bounds above $bars", bounds.bottom <= height - bars.bottom)
    }

    private fun insets(): WindowInsetsCompat = rule.runOnIdle { checkNotNull(appliedInsets) }

    private fun capture(screen: String) {
        rule.waitForIdle()
        val name = "${width}x$height-$screen"
        val bars = insets().getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets().getInsets(WindowInsetsCompat.Type.ime())
        File(
            output,
            "$name.txt",
        ).writeText(
            "activity=MainActivity sizeDp=${width}x$height density=$density syntheticBars=$syntheticBars systemBarsPx=$bars imePx=$ime\n",
        )
        // ATD omits system chrome and its framebuffer can be black. Only the full image supplies
        // screenshots; ATD still runs every geometry/keyboard assertion and records measured insets.
        if (syntheticBars) return
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val colors =
            (0 until bitmap.height step 16)
                .flatMap { y ->
                    (0 until bitmap.width step 16).map { x -> bitmap.getPixel(x, y) }
                }.toSet()
        assertTrue("capture must contain rendered content", colors.size > 10)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
