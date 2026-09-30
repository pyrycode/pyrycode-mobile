package de.pyryco.mobile.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog
import de.pyryco.mobile.ui.conversations.list.ChannelPromptReading
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** Real-pixel evidence for the four caller forms in the 412 × 892 mobile shell. */
@RunWith(AndroidJUnit4::class)
class FormCallerCaptureTest {
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

    @Test fun createChannel() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                CreateChannelModal("host", null, { _, _ -> }, {})
            }
        }
        capture("Create channel", "create-channel")
    }

    @Test fun editChat() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                EditChatModal("chat", "Release notes", {}, {}, {})
            }
        }
        capture("Edit Chat", "edit-chat")
    }

    @Test fun editChannel() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                EditChannelModal(
                    conversationId = "channel",
                    initialName = "Release notes",
                    prompt = ChannelPromptReading.Read("Answer in short paragraphs.", SessionPromptStatus.Matches),
                    initialMuted = true,
                    onSubmit = { _, _, _ -> },
                    onArchiveRequested = {},
                    onDismissRequest = {},
                )
            }
        }
        capture("Edit channel", "edit-channel")
    }

    @Test fun saveAsChannel() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                SaveAsChannelDialog("chat", "Release notes", { _, _ -> }, {})
            }
        }
        capture("Save as channel", "save-as-channel")
    }

    private fun capture(
        title: String,
        name: String,
    ) {
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.waitForIdle()
        rule.runOnIdle {
            WindowCompat
                .getInsetsController(rule.activity.window, rule.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        // The field can reopen the IME in the dialog's separate window after the activity hides it.
        SystemClock.sleep(800)
        repeat(2) {
            val probe = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val keyboardSurface = probe.getPixel(200, 750)
            probe.recycle()
            if (android.graphics.Color.red(keyboardSurface) < 150) return@repeat
            shell("input keyevent 4")
            SystemClock.sleep(800)
            rule.waitForIdle()
        }
        // Dialog and IME window animations can outlive Compose idleness.
        SystemClock.sleep(2_000)
        val footer =
            rule
                .onNodeWithText("OK")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)
        val colors = (0 until bitmap.height step 16).flatMap { y -> (0 until bitmap.width step 16).map { x -> bitmap.getPixel(x, y) } }
        assertTrue("$name must contain rendered content", colors.toSet().size > 10)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "forms-1217")
                .apply { mkdirs() }
        File(output, "$name-412x892.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "$name-context.txt").writeText(
            "api=${Build.VERSION.SDK_INT} size=412x892 density=160 staticDark=true capture=uiAutomation.takeScreenshot " +
                "title=$title footer=${footer.top},${footer.bottom}\n",
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
