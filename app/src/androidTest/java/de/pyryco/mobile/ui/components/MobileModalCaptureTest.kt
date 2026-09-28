package de.pyryco.mobile.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Actual device capture of the shared chrome with a real editor caller at the Figma viewport. */
class MobileModalCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 0)
    val viewport =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val size = overrideOf(shell("wm size"))
                    val density = overrideOf(shell("wm density"))
                    shell("wm density 160")
                    shell("wm size 412x892")
                    try {
                        instrumentation.waitForIdleSync()
                        base.evaluate()
                    } finally {
                        shell("wm size $size")
                        shell("wm density $density")
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editHostAtFigmaViewport() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                EditHostModal(
                    serverIdentity = "345345-345345345-gw3vw-w4wv34-vw34t",
                    relayAddress = "https://asdf.afwevawef.fwef/asdffe",
                    initialHostName = "Pyrybox",
                    onDismissRequest = {},
                    onSubmit = {},
                    onUnpairRequested = {},
                    onUnpairConfirmed = {},
                    onUnpairDeclined = {},
                )
            }
        }
        rule.waitForIdle()
        val title =
            rule
                .onNodeWithText("Edit host")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val footer =
            rule
                .onNodeWithText("OK")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        assertTrue("footer must follow the header", footer.top > title.bottom)
        // The ATD gate can return a black framebuffer even when the dialog is laid out.
        // The full image run opts into strict pixel proof with this instrumentation argument.
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") != "true") return
        // Dialog window dim/enter animations can outlive Compose idleness on a real device.
        SystemClock.sleep(600)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(412, image.width)
        assertEquals(892, image.height)
        val colors = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        assertTrue("actual emulator capture must contain rendered content", colors.toSet().size > 10)
        assertEquals("modal fill after window animation", 0xFF001D34.toInt(), image.getPixel(200, 200))
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "modal-1232/api-${Build.VERSION.SDK_INT}",
            ).apply { mkdirs() }
        File(output, "edit-host-412x892.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
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
