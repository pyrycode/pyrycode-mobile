package de.pyryco.mobile

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.components.MobileModalTestIme
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
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
    private var evidenceFolder = "insets-1149"
    private lateinit var preferences: AppPreferences
    private lateinit var oldTheme: ThemeMode
    private var oldWallpaper = false
    private var oldYolo = false
    private var oldNightMode: String? = null
    private val drafts get() = GlobalContext.get().get<ComposerDraftStore>()
    private var keyboardConversationId: String? = null
    private val output by lazy {
        File(
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "$evidenceFolder/api-${Build.VERSION.SDK_INT}",
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
        preferences = GlobalContext.get().get()
        runBlocking {
            oldTheme = preferences.themeMode.first()
            oldWallpaper = preferences.useWallpaperColors.first()
            oldYolo = preferences.defaultYolo.first()
        }
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
        keyboardConversationId?.let { id ->
            drafts.clearConversation("demo", id)
            runBlocking { GlobalContext.get().get<FakeConversationRepository>().delete(id) }
        }
        if (::oldTheme.isInitialized) {
            runBlocking {
                preferences.setThemeMode(oldTheme)
                preferences.setUseWallpaperColors(oldWallpaper)
                preferences.setDefaultYolo(oldYolo)
            }
        }
        oldNightMode?.let { shell("cmd uimode night $it") }
        if (::originalStore.isInitialized) loadKoinModules(module { single { originalStore } })
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        oldIme?.takeIf { it.isNotEmpty() }?.let { shell("ime set $it") }
        if (!imeEnabled) shell("ime disable $imeId")
        if (oldIme.isNullOrEmpty()) shell("settings delete secure default_input_method")
        instrumentation.waitForIdleSync()
    }

    @Test fun activityAt412By892() = exercise(412, 892)

    @Test fun savedAppearanceAndAndroidModeCannotChangeStaticDarkPalette() {
        evidenceFolder = "palette-1238"
        width = 412
        height = 892
        shell("wm density 160")
        shell("wm size ${width}x$height")
        oldNightMode = Regex("\\b(auto|yes|no)\\b").find(shell("cmd uimode night"))?.value
        assertTrue("original night mode must be known before changing it", oldNightMode != null)
        paired = true
        runBlocking { preferences.setDefaultYolo(true) }
        val cases =
            listOf(
                Triple("no", ThemeMode.LIGHT, true),
                Triple("no", ThemeMode.LIGHT, false),
                Triple("yes", ThemeMode.SYSTEM, false),
                Triple("no", ThemeMode.SYSTEM, true),
                Triple("yes", ThemeMode.DARK, true),
            )
        cases.forEachIndexed { index, (night, theme, wallpaper) ->
            scenario?.close()
            scenario = null
            shell("cmd uimode night $night")
            runBlocking {
                preferences.setThemeMode(theme)
                preferences.setUseWallpaperColors(wallpaper)
            }
            instrumentation.waitForIdleSync()
            launch()
            scenario?.onActivity { activity ->
                val actualNight = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val expectedNight = if (night == "yes") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                assertEquals("Android mode applied for case $index", expectedNight, actualNight)
            }
            rule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
            assertEquals(theme, runBlocking { preferences.themeMode.first() })
            assertEquals(wallpaper, runBlocking { preferences.useWallpaperColors.first() })
            assertTrue(runBlocking { preferences.defaultYolo.first() })
            val pixel =
                rule.runOnIdle {
                    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).let { image ->
                        view.draw(Canvas(image))
                        image.getPixel(400, 800).also { image.recycle() }
                    }
                }
            assertEquals("case $index: static-dark channel-list canvas", 0xFF0B0E11.toInt(), pixel)
            if (index == 0) capture("light-wallpaper-android-light")
        }
    }

    @Test fun activityAt360By800() = exercise(360, 800)

    @Test fun populatedThreadKeyboardAt412By892() = exerciseThreadKeyboard(412, 892)

    @Test fun populatedThreadKeyboardAt360By640() = exerciseThreadKeyboard(360, 640)

    private fun exerciseThreadKeyboard(
        widthDp: Int,
        heightDp: Int,
    ) {
        evidenceFolder = "insets-1166"
        width = widthDp
        height = heightDp
        shell("wm density 160")
        shell("wm size ${width}x$height")
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(250, 10_000)
        paired = true
        val conversationId =
            runBlocking {
                val repository = GlobalContext.get().get<FakeConversationRepository>()
                val conversation = repository.createChannel("Keyboard regression", "~/keyboard-fixture")
                keyboardConversationId = conversation.id
                repeat(30) { index ->
                    repository.sendMessage(
                        conversation.id,
                        "Message ${index + 1}. Fixed populated history for keyboard layout and scroll restoration.",
                    )
                }
                conversation.id
            }
        for ((theme, wallpaper) in listOf(ThemeMode.DARK to false, ThemeMode.LIGHT to false, ThemeMode.LIGHT to true)) {
            drafts.clearConversation("demo", conversationId)
            runBlocking {
                preferences.setThemeMode(theme)
                preferences.setUseWallpaperColors(wallpaper)
            }
            launch()
            density = view.resources.displayMetrics.density
            assertEquals(1f, density, 0.01f)
            val bars = insets().getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue("nonzero system bars", bars.top > 0 && bars.bottom > 0)
            if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
                assertTrue("physical system bars required", !syntheticBars)
            }
            rule.onNodeWithText("Keyboard regression").performScrollTo().performClick()
            val list = rule.onNode(hasScrollToIndexAction())
            list.assertIsDisplayed()
            val field = rule.onNode(hasSetTextAction())
            val header = rule.onNodeWithContentDescription("Back")
            val footer = rule.onNodeWithContentDescription("Expand status details")
            val send = rule.onNodeWithContentDescription("Send message")
            val label = "${theme.name.lowercase()}-wallpaper-$wallpaper"
            for (index in listOf(0, 4)) {
                list.performScrollToIndex(index)
                rule.waitForIdle()
                val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("requested message anchor is established", if (index == 0) scrollBefore == 0f else scrollBefore >= index)
                val headerBefore = screenBounds(header)
                val footerGap = height - bars.bottom - screenBounds(footer).bottom
                assertTrue("normal footer spacing", footerGap in 15f..17f)
                val prefix = "$label-anchor-$index"
                capture("$prefix-before")
                openKeyboard(field)
                if (index == 0) field.performTextInput("Keyboard draft")
                rule.waitForIdle()
                capture("$prefix-open")
                assertEquals("header stays stationary", headerBefore.top, screenBounds(header).top, 1f)
                assertTrue("header below status bar", screenBounds(header).top >= bars.top)
                val ime = insets().getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("actual nonzero keyboard inset", ime > 0)
                assertEquals("one keyboard reservation with normal footer gap", footerGap, height - ime - screenBounds(footer).bottom, 1f)
                assertTrue("send above keyboard", screenBounds(send.assertIsEnabled()).bottom <= height - ime)
                assertTrue("messages retain a viewport", screenBounds(list).height > 48 * density)
                val viewport = bounds(list)
                assertTrue(
                    "populated message text remains visible",
                    rule
                        .onAllNodes(hasAnyAncestor(hasScrollToIndexAction()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                        .fetchSemanticsNodes()
                        .any { it.boundsInWindow.height > 0 && it.boundsInWindow.overlaps(viewport) },
                )
                field.assertTextEquals("Keyboard draft")
                Espresso.pressBack()
                awaitKeyboard(false)
                capture("$prefix-dismissed")
                assertEquals("header restored", headerBefore.top, screenBounds(header).top, 1f)
                assertEquals(
                    "message index and offset restored",
                    scrollBefore,
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
                    0.00001f,
                )
                field.assertTextEquals("Keyboard draft")
                openKeyboard(field)
                field.assertTextEquals("Keyboard draft")
                capture("$prefix-reopened")
                assertEquals("header stationary on reopening", headerBefore.top, screenBounds(header).top, 1f)
                val reopenedKeyboardTop = height - insets().getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertEquals("normal footer gap on reopening", footerGap, reopenedKeyboardTop - screenBounds(footer).bottom, 1f)
                assertTrue("send reachable on reopening", screenBounds(send.assertIsEnabled()).bottom <= reopenedKeyboardTop)
                // The preservation cycle above never scrolls; now prove scrolling with the IME open.
                val beforeScroll = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                list.performTouchInput { swipeDown() }
                assertTrue(
                    "messages scroll with keyboard open",
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > beforeScroll,
                )
                list.assertIsDisplayed()
                send.assertIsDisplayed().assertIsEnabled()
                Espresso.pressBack()
                awaitKeyboard(false)
            }
            scenario?.close()
            scenario = null
        }
    }

    private fun openKeyboard(field: SemanticsNodeInteraction) {
        // The ATD Bluetooth crash dialog can steal focus; reuse MobileModalTest's recovery.
        rule.waitUntil(10_000) {
            val focused = rule.runOnIdle { view.hasWindowFocus() }
            if (!focused) shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            focused
        }
        field.performClick()
        // Like MobileModalTest, explicitly request the real test IME after focusing the editor.
        rule.runOnIdle { view.windowInsetsController?.show(WindowInsets.Type.ime()) }
        awaitKeyboard(true)
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

    private fun screenBounds(node: SemanticsNodeInteraction): Rect {
        val offset =
            rule.runOnIdle {
                val screen = IntArray(2)
                val window = IntArray(2)
                view.getLocationOnScreen(screen)
                view.getLocationInWindow(window)
                Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
            }
        return bounds(node).translate(offset)
    }

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

        openKeyboard(rule.onNodeWithText("Host name"))
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
