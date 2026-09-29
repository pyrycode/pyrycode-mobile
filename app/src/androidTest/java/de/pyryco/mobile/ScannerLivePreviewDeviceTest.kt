package de.pyryco.mobile

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Captures the production ready-to-scan route with CameraX bound to the emulator camera. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ScannerLivePreviewDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun readyRoute_streamingCameraIsVisibleUnderOverlay() {
        val context = instrumentation.targetContext
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        assertTrue("fresh unpaired app", runBlocking { store.list().isEmpty() })
        val oldSize = overrideOf(shell("wm size"))
        val oldDensity = overrideOf(shell("wm density"))
        val preferences = GlobalContext.get().get<AppPreferences>()
        val oldTheme = runBlocking { preferences.themeMode.first() }
        val originallyGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        runBlocking { preferences.setThemeMode(ThemeMode.DARK) }
        try {
            shell("wm density 160")
            shell("wm size 412x892")
            instrumentation.waitForIdleSync()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.onNodeWithText("I already have pyrycode").performClick()
                if (!originallyGranted) allowCameraRequest()
                rule.onNodeWithText("Pairing").assertIsDisplayed()
                rule.waitUntil(15_000) {
                    var streaming = false
                    scenario.onActivity {
                        streaming = findPreview(it.window.decorView)?.previewStreamState?.value == PreviewView.StreamState.STREAMING
                    }
                    streaming
                }
                scenario.onActivity {
                    assertEquals(PreviewView.ImplementationMode.COMPATIBLE, findPreview(it.window.decorView)?.implementationMode)
                }
                var rawPreview: Bitmap? = null
                rule.waitUntil(15_000) {
                    var candidate: Bitmap? = null
                    scenario.onActivity { candidate = findPreview(it.window.decorView)?.bitmap }
                    val frame = candidate ?: return@waitUntil false
                    if (sampledColors(frame) > 30) {
                        rawPreview = frame
                        true
                    } else {
                        frame.recycle()
                        false
                    }
                }
                val raw = checkNotNull(rawPreview)
                rule.waitUntil(15_000) {
                    val frame = checkNotNull(automation.takeScreenshot())
                    val visible = Color.red(frame.getPixel(100, 200)) + Color.green(frame.getPixel(100, 200)) > 120
                    frame.recycle()
                    visible
                }
                val bitmap = checkNotNull(automation.takeScreenshot())
                assertEquals(412, bitmap.width)
                assertEquals(892, bitmap.height)
                val cameraPixels =
                    (180 until 680 step 8).flatMap { y ->
                        (32 until 380 step 8).map { x -> bitmap.getPixel(x, y) }
                    }
                assertTrue("nonblank live camera window", cameraPixels.toSet().size > 30)
                val output =
                    File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "scanner-live-1213")
                output.mkdirs()
                File(output, "camera-raw.png").outputStream().use { raw.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "scanner-live-412x892.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                raw.recycle()
                bitmap.recycle()
                File(output, "context.txt").writeText(
                    "activity=MainActivity route=welcome->ready-to-scan preview=STREAMING mode=COMPATIBLE\n" +
                        "sizeDp=412x892 density=1 theme=Dark api=${Build.VERSION.SDK_INT} device=${Build.MODEL}\n",
                )
                assertTrue("preview capture saves no pairing", runBlocking { store.list().isEmpty() })
            }
        } finally {
            shell("wm size $oldSize")
            shell("wm density $oldDensity")
            runBlocking { preferences.setThemeMode(oldTheme) }
        }
    }

    private fun allowCameraRequest() {
        var allow: AccessibilityNodeInfo? = null
        rule.waitUntil(5_000) {
            allow =
                automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_one_time_button")
                    ?.firstOrNull()
            allow != null
        }
        assertTrue(checkNotNull(allow).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPreview(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun sampledColors(bitmap: Bitmap): Int =
        (0 until bitmap.height step 8)
            .flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }
            .toSet()
            .size

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
