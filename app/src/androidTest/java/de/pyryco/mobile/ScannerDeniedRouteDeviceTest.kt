package de.pyryco.mobile

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

/** Requires a fresh app on a full system image: real permission UI, settings and pixels. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ScannerDeniedRouteDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun realDenial_backSettingsAndPaste_preserveUnpairedState() {
        val context = instrumentation.targetContext
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        assertTrue("fresh unpaired app", runBlocking { store.list().isEmpty() })
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val oldSize = overrideOf(shell("wm size"))
        val oldDensity = overrideOf(shell("wm density"))
        val preferences = GlobalContext.get().get<AppPreferences>()
        val oldTheme = runBlocking { preferences.themeMode.first() }
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        runBlocking { preferences.setThemeMode(ThemeMode.DARK) }
        try {
            shell("wm density 160")
            shell("wm size 412x892")
            instrumentation.waitForIdleSync()
            automation.waitForIdle(1_000, 5_000)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.onNodeWithText("I already have pyrycode").performClick()
                denyCameraRequest()
                rule.onNodeWithText("Pair with pyrycode").assertIsDisplayed()
                rule.onNodeWithText("Camera permission required").assertIsDisplayed()
                var root: View? = null
                scenario.onActivity { root = it.window.decorView }
                capture(checkNotNull(root))

                rule.onNodeWithText("Open settings").performClick()
                rule.waitUntil(5_000) {
                    automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
                }
                assertTrue("this app's settings", shell("dumpsys activity activities").contains("dat=package:${context.packageName}"))
                shell("input keyevent 4")
                rule.onNodeWithText("Camera permission required").assertIsDisplayed()
                rule.onNodeWithText("Paste code instead").performClick()
                rule.onNodeWithText("Host name").assertIsDisplayed()
                rule.onNodeWithText("Pairing code").assertIsDisplayed()
                rule.onNodeWithText("Cancel").performClick()
                rule.onNodeWithText("Camera permission required").assertIsDisplayed()
                assertTrue("untouched form cancellation saves nothing", runBlocking { store.list().isEmpty() })
                rule.onNodeWithContentDescription("Back").performClick()
                rule.onNodeWithText("I already have pyrycode").assertIsDisplayed()
                assertTrue("Back saves nothing", runBlocking { store.list().isEmpty() })
            }
        } finally {
            shell("wm size $oldSize")
            shell("wm density $oldDensity")
            runBlocking { preferences.setThemeMode(oldTheme) }
        }
    }

    private fun denyCameraRequest() {
        var deny: AccessibilityNodeInfo? = null
        rule.waitUntil(5_000) {
            deny =
                automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")
                    ?.firstOrNull()
            deny != null
        }
        assertTrue(checkNotNull(deny).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitForIdle()
    }

    private fun capture(root: View) {
        rule.waitForIdle()
        val bars =
            rule.runOnIdle {
                checkNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.systemBars())
            }
        assertTrue("real system bars required", bars.top > 0 && bars.bottom > 0)
        val density = root.resources.displayMetrics.density
        assertEquals(1f, density, 0.01f)
        val back = rule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInWindow
        assertEquals(bars.top + 12f, back.top, 1f)
        val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "scanner-denied-1151")
        output.mkdirs()
        val bitmap = checkNotNull(automation.takeScreenshot())
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)
        assertTrue(
            "nonblank pixels",
            (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }.toSet().size >
                10,
        )
        File(output, "denied-dark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "context.txt").writeText(
            "activity=MainActivity route=welcome->real-camera-denial theme=Dark\n" +
                "sizeDp=412x892 density=$density systemBarsPx=$bars syntheticBars=false\n" +
                "api=${Build.VERSION.SDK_INT} device=${Build.MODEL} fingerprint=${Build.FINGERPRINT}\n" +
                "build=${BuildConfig.VERSION_NAME} backBoundsPx=$back\n",
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
