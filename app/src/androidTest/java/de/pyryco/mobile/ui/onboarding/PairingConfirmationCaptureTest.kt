package de.pyryco.mobile.ui.onboarding

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Real-pixel and reachability proof using only a synthetic pending record. */
@OptIn(ExperimentalTestApi::class)
class PairingConfirmationCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 0)
    val viewport =
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    val originalSize = overrideOf(shell("wm size"))
                    val originalDensity = overrideOf(shell("wm density"))
                    val compact = description.methodName.contains("compact")
                    shell("wm density 160")
                    shell("wm size ${if (compact) "360x640" else "412x892"}")
                    try {
                        instrumentation.waitForIdleSync()
                        base.evaluate()
                    } finally {
                        shell("wm size $originalSize")
                        shell("wm density $originalDensity")
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun confirmationAt412By892() = checkConfirmation(412, 892, 1f)

    @Test
    fun codeConfirmationAt412By892() = checkConfirmation(412, 892, 1f, viaCode = true)

    @Test
    fun compactConfirmationWithEnlargedText() = checkConfirmation(360, 640, 1.5f)

    @Test
    fun systemBackDeclinesWithoutConfirming() {
        var declined = 0
        var confirmed = 0
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, syntheticServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    onConfirmPairing = { confirmed++ },
                    onDeclinePairing = { declined++ },
                )
            }
        }
        rule.onNodeWithText(FINGERPRINT).assertIsDisplayed()
        Espresso.pressBack()
        rule.runOnIdle {
            assertEquals(1, declined)
            assertEquals(0, confirmed)
        }
    }

    private fun checkConfirmation(
        width: Int,
        height: Int,
        fontScale: Float,
        viaCode: Boolean = false,
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                PyrycodeMobileTheme(darkTheme = true) {
                    val pending = ScannerUiState.AwaitingConfirm(FINGERPRINT, syntheticServer())
                    if (viaCode) {
                        PairCodeScreen(
                            state = PairCodeState(phase = PairCodePhase.Confirming, confirmation = pending),
                            onEvent = {},
                        )
                    } else {
                        ScannerScreen(
                            state = pending,
                            onNavigateBack = {},
                            onOpenSettings = {},
                            onPasteCode = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val title = rule.onNodeWithText("Pair").assertIsDisplayed().getUnclippedBoundsInRoot()
        val fingerprint = rule.onNodeWithText(FINGERPRINT).assertIsDisplayed().getUnclippedBoundsInRoot()
        val explanation = rule.onNodeWithText("Static-key fp:", substring = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val decline = rule.onNodeWithText("Don't pair").assertIsDisplayed().getUnclippedBoundsInRoot()
        val confirm = rule.onNodeWithText("Confirm pairing").assertIsDisplayed().getUnclippedBoundsInRoot()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        assertTrue("fingerprint overlaps title", fingerprint.top > title.bottom)
        assertTrue("explanation overlaps fingerprint", explanation.top >= fingerprint.bottom)
        assertTrue("actions overlap explanation", decline.top >= explanation.bottom && confirm.top >= explanation.bottom)
        assertTrue("actions overlap each other", decline.right <= confirm.left)
        assertTrue("fingerprint clipped horizontally", fingerprint.left >= 0.dp && fingerprint.right <= width.dp)
        assertTrue("actions clipped vertically", decline.bottom <= height.dp && confirm.bottom <= height.dp)
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") != "true") return

        SystemClock.sleep(600)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        val colors = (0 until height step 16).flatMap { y -> (0 until width step 16).map { x -> image.getPixel(x, y) } }.toSet()
        assertTrue("actual emulator capture is blank", colors.size > 10)
        val output =
            File(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).path,
                "pairing-1270/api-${Build.VERSION.SDK_INT}",
            ).apply { mkdirs() }
        val name = "${if (viaCode) "code" else "qr"}-confirm-${width}x$height-dark-${fontScale}x"
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText(
            "api=${Build.VERSION.SDK_INT} sizeDp=${width}x$height density=160 fontScale=$fontScale " +
                "theme=dark record=synthetic keyboard=closed\n",
        )
        image.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"

    private fun syntheticServer() =
        PairedServer(
            serverId = "synthetic-server",
            token = "synthetic-token",
            relayUrl = "wss://example.invalid",
            serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )

    private companion object {
        const val FINGERPRINT = "32:0b:5e:a9:9e:65:3b:c2"
    }
}
