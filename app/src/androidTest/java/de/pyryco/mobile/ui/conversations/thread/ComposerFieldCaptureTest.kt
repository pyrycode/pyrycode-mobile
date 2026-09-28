package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual dark-theme device pixels for Figma Input area 533:1957 and Send 113:3543. */
@RunWith(AndroidJUnit4::class)
class ComposerFieldCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private var draft by mutableStateOf("")
    private var contentView: View? = null

    @Before fun setViewport() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        shell("wm density 160")
        shell("wm size 412x892")
        instrumentation.waitForIdleSync()
    }

    @After fun restoreViewport() {
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        instrumentation.waitForIdleSync()
    }

    @Test fun typedAndStopAt412By892() {
        setComposer()
        val field = rule.onNode(hasSetTextAction())
        field.performTextReplacement("My message")
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().assertIsDisplayed()
        capture("emulator-send-412x892.png", 412, 892)

        field.performTextReplacement("")
        rule.onNodeWithContentDescription("Stop the running turn").assertIsEnabled().assertIsDisplayed()
        capture("emulator-stop-412x892.png", 412, 892)
    }

    @Test fun compactLargeTextKeepsSendReachable() {
        shell("wm size 280x400")
        instrumentation.waitForIdleSync()
        setComposer(fontScale = 1.6f)
        val field = rule.onNode(hasSetTextAction())
        field.performTextReplacement("One\ntwo\nthree")
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().assertIsDisplayed()
        capture("emulator-compact-large-text-280x400.png", 280, 400)
    }

    private fun setComposer(fontScale: Float = 1f) {
        draft = ""
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    contentView = LocalView.current
                    ThreadScreen(
                        state = ThreadUiState(conversationId = "capture", displayName = "Test channel", isPromoted = true),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        draft = draft,
                        onDraftChange = { draft = it },
                        isBusy = true,
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Stop the running turn").assertIsEnabled()
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
    ) {
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(contentView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val samples = (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) } }
        assertTrue("capture must contain rendered content", samples.toSet().size > 10)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "composer-1205",
            ).apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText(
            "api=${Build.VERSION.SDK_INT} sizeDp=${width}x$height density=1.0 fontScale=${if (width == 280) 1.6 else 1.0} " +
                "staticDark=true capture=decorView.draw design=533:1957,347:6446,113:3543 date=2026-09-29\n",
        )
        bitmap.recycle()
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
