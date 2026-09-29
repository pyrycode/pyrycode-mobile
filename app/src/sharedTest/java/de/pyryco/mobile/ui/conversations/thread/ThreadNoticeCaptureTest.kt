package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real-device screenshots of #1210's two Figma-backed thread notice variants. */
@RunWith(AndroidJUnit4::class)
class ThreadNoticeCaptureTest {
    @get:Rule val rule = createComposeRule()

    private var composeView: View? = null

    @OptIn(ExperimentalTestApi::class)
    private fun show(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    PyrycodeMobileTheme(darkTheme = true) {
                        composeView = LocalView.current
                        ThreadScreen(
                            state = ThreadUiState("notice-capture", "pyrycode discord integration", isPromoted = true),
                            onBack = {},
                            onSendMessage = {},
                            connectionState = ConnectionState.Connected,
                            onRetry = {},
                            usageLimit =
                                UsageLimitReading(
                                    status = "allowed_warning",
                                    limitType = "seven_day",
                                    resetsAt = 0L,
                                    utilization = 0.8,
                                    truncatedFields = null,
                                ),
                            showRePair = true,
                            onRePair = {},
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val view = checkNotNull(composeView)
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
        val file = File(directory, "notice-1210-$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun referenceViewport() {
        show(412, 892, 1f)
        rule.onNodeWithText("Pairing error - Re-pair").assertIsDisplayed()
        capture("412x892")
    }

    @Test
    fun compactViewportWithLargeText() {
        show(320, 700, 1.5f)
        rule.onNodeWithText("Pairing error - Re-pair").assertIsDisplayed()
        capture("320x700-large-text")
    }
}
