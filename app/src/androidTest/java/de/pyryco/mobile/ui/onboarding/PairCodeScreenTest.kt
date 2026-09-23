package de.pyryco.mobile.ui.onboarding

import android.Manifest
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.ui.components.MobileModalTestIme
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import java.io.File

@OptIn(ExperimentalTestApi::class)
class PairCodeScreenTest {
    @get:Rule(order = 0)
    val ime =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val resolver = instrumentation.targetContext.contentResolver
                    val id = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
                    val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    val manager = instrumentation.targetContext.getSystemService(InputMethodManager::class.java)
                    val enabled = manager.enabledInputMethodList.any { it.id == id }

                    fun shell(command: String) =
                        ParcelFileDescriptor
                            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                            .bufferedReader()
                            .use { it.readText() }
                    try {
                        shell("ime enable $id")
                        shell("ime set $id")
                        instrumentation.waitForIdleSync()
                        base.evaluate()
                    } finally {
                        if (!previous.isNullOrEmpty()) shell("ime set $previous")
                        if (!enabled) shell("ime disable $id")
                        if (previous.isNullOrEmpty()) shell("settings delete secure default_input_method")
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(PairCodeState(name = "Host", code = "draft"))
    private val events = mutableListOf<PairCodeEvent>()
    private var dark by mutableStateOf(true)
    private lateinit var view: View

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir =
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: instrumentation.targetContext.getExternalFilesDir(null)?.path ?: return
        File(dir).mkdirs()
        File(
            dir,
            "$name.png",
        ).outputStream().use {
            rule
                .onNode(
                    isRoot() and hasAnyDescendant(hasText("Pairing")),
                ).captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun show(small: Boolean = false) {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            view = LocalView.current
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(if (small) DpSize(360.dp, 640.dp) else DpSize(412.dp, 892.dp)),
            ) {
                PyrycodeMobileTheme(darkTheme = dark) {
                    Scaffold { padding ->
                        PairCodeScreen(state, { event ->
                            events += event
                            state =
                                when (event) {
                                    is PairCodeEvent.Name -> state.copy(name = event.value)
                                    is PairCodeEvent.Code -> state.copy(code = event.value)
                                    else -> state
                                }
                        }, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    @Test fun cancelToolbarAndAndroidBackReturnToCallerWithoutSaving() {
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val before = runBlocking { store.list() }
        lateinit var nav: NavHostController
        rule.setContent {
            PyrycodeMobileTheme {
                nav = rememberNavController()
                PyryNavHost(Routes.WELCOME, navController = nav)
            }
        }
        for (caller in listOf(Routes.WELCOME, Routes.CHANNEL_LIST)) {
            if (caller != Routes.WELCOME) rule.runOnIdle { nav.navigate(caller) }
            repeat(3) { exit ->
                rule.runOnIdle { nav.navigate(Routes.PAIR_CODE) }
                rule.onNodeWithText("Pair").assertIsDisplayed().performClick()
                rule.onNodeWithText("Invalid pairing code").assertIsDisplayed()
                when (exit) {
                    0 -> rule.onNodeWithText("Cancel").performScrollTo().performClick()
                    1 -> rule.onNodeWithContentDescription("Back").performClick()
                    else -> Espresso.pressBack()
                }
                rule.waitUntil(5_000) { nav.currentDestination?.route == caller }
            }
        }
        assertEquals(before, runBlocking { store.list() })
    }

    @Test fun targetedRouteNamesItsHostAndBackReturnsWithoutSaving() {
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val before = runBlocking { store.list() }
        lateinit var nav: NavHostController
        rule.setContent {
            PyrycodeMobileTheme {
                nav = rememberNavController()
                PyryNavHost(Routes.WELCOME, navController = nav)
            }
        }
        rule.runOnIdle { nav.navigate(Routes.pairCode("unsaved/host id")) }
        rule.onNodeWithText("Host name").assertTextContains("unsaved/host id").assertIsNotEnabled()
        Espresso.pressBack()
        rule.waitUntil(5_000) { nav.currentDestination?.route == Routes.WELCOME }
        assertEquals(before, runBlocking { store.list() })
    }

    @Test fun scannerPasteReturnsFromEveryRecoveryStateWithoutSaving() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val before = runBlocking { store.list() }
        lateinit var nav: NavHostController
        rule.setContent {
            PyrycodeMobileTheme {
                nav = rememberNavController()
                PyryNavHost(Routes.WELCOME, navController = nav)
            }
        }
        rule.runOnIdle { nav.navigate(Routes.SCANNER) }
        rule.onNodeWithText("Pairing").assertIsDisplayed()
        val vm = rule.runOnIdle { ViewModelProvider(nav.getBackStackEntry(Routes.SCANNER))[ScannerViewModel::class.java] }
        for ((event, label) in listOf(
            ScannerEvent.PermissionGranted to "Trouble scanning? Paste the pairing code instead",
            ScannerEvent.PermissionDenied to "Paste code instead",
            ScannerEvent.CameraError("Camera unavailable") to "Paste the pairing code instead",
        )) {
            repeat(2) { exit ->
                rule.runOnIdle { vm.onEvent(event) }
                rule.onNodeWithText(label).assertIsDisplayed().performClick()
                rule.onNodeWithText("Pairing code").assertIsDisplayed()
                if (exit == 0) rule.onNodeWithText("Cancel").performScrollTo().performClick() else Espresso.pressBack()
                rule.waitUntil(5_000) { nav.currentDestination?.route == Routes.SCANNER }
                assertEquals(before, runBlocking { store.list() })
            }
        }
        rule.runOnIdle { vm.onEvent(ScannerEvent.PermissionGranted) }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(5_000) { nav.currentDestination?.route == Routes.WELCOME }
    }

    @Test fun fullSizeLightAndDarkFrames() {
        state = PairCodeState()
        show()
        for (label in listOf("Host name", "Pairing code", "Pair", "Cancel")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        capture("pair-code-dark")
        rule.runOnIdle { dark = false }
        capture("pair-code-light")
    }

    @Test fun targetModeNamesTheHostReadOnlyAndShowsWrongHostOnTheCode() {
        state = PairCodeState(targetName = "Pyrybox", code = "draft")
        show()
        rule.onNodeWithText("Host name").assertTextContains("Pyrybox").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Clear host name").assertIsNotEnabled()
        rule.runOnIdle { state = state.copy(error = WRONG_HOST_ERROR) }
        rule.onNodeWithText(WRONG_HOST_ERROR).assertIsDisplayed()
        rule.onNodeWithText("Pair").assertIsDisplayed()
        capture("pair-code-target")
    }

    @Test fun clearControlsErrorsAndConfirmationBack() {
        show()
        capture("pair-code-frame")
        rule.onNodeWithContentDescription("Clear host name").performClick()
        rule.onNodeWithText("Pairing code").assertTextContains("draft")
        rule.runOnIdle { state = state.copy(name = "Other") }
        rule.onNodeWithContentDescription("Clear pairing code").performClick()
        rule.runOnIdle {
            assertEquals("", state.code)
            assertEquals("Other", state.name)
        }
        rule.runOnIdle { state = state.copy(error = "Invalid pairing code") }
        rule.onNodeWithText("Invalid pairing code").assertIsDisplayed()
        rule.onNodeWithText("Pair").performClick()
        rule.runOnIdle {
            assertEquals(PairCodeEvent.Pair, events.last())
            state =
                state.copy(
                    phase = PairCodePhase.Confirming,
                    confirmation = ScannerUiState.AwaitingConfirm("aa:bb", PairedServer("B", "secret", "relay", "key")),
                )
        }
        rule.onNodeWithText("Confirm pairing").performClick()
        rule.runOnIdle { assertEquals(PairCodeEvent.Confirm, events.last()) }
        Espresso.pressBack()
        rule.runOnIdle { assertEquals(PairCodeEvent.Back, events.last()) }
        rule.runOnIdle { state = state.copy(phase = PairCodePhase.Editing, error = "Pairing saved. Host unavailable. Retry or cancel.") }
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle { assertEquals(PairCodeEvent.Pair, events.last()) }
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(PairCodeEvent.Back, events.last()) }
    }

    @Test fun softwareKeyboardKeepsBothFieldsClearControlsAndActionsReachable() {
        show(small = true)
        rule.onNodeWithText("Host name").performClick()
        rule.waitUntil(5_000) {
            rule.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        }
        for (label in listOf("Host name", "Pairing code")) {
            rule.onNodeWithText(label).performScrollTo()
            capture("pair-code-field-$label")
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        for (label in listOf("Clear host name", "Clear pairing code")) {
            rule.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
        }
        for (label in listOf("Pair", "Cancel")) {
            rule.onNodeWithText(label).performScrollTo()
            capture("pair-code-field-$label")
            rule.onNodeWithText(label).assertIsDisplayed()
            capture("pair-code-ime-$label")
            val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            rule.runOnIdle {
                val inset = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                assertTrue(inset > 0)
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                assertTrue(
                    "$label: y=${location[1]} bounds=$bounds height=${view.resources.displayMetrics.heightPixels} ime=$inset",
                    location[1] + bounds.bottom <= view.resources.displayMetrics.heightPixels - inset + 1,
                )
            }
        }
    }
}
