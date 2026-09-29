package de.pyryco.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** Real 412 × 892 pixels for comparing the shared field wells with the current Figma form. */
@RunWith(AndroidJUnit4::class)
class ChannelFormFieldsCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var dialogView: View? = null

    // Configure the viewport before the Compose rule launches its host activity.
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
                        instrumentation.waitForIdleSync()
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test fun createFormAt412By892() {
        val name = mutableStateOf(TextFieldValue(""))
        val prompt =
            mutableStateOf(
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Cras et mauris nisi. " +
                    "Sed ultrices urna quis vulputate semper. Praesent dignissim sollicitudin metus quis porta. " +
                    "Aliquam bibendum turpis quis pellentesque tincidunt. Etiam tincidunt elit eu tincidunt pretium. " +
                    "Maecenas neque nisl, tristique a est eu, aliquet vestibulum enim. ",
            )
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                MobileModal(title = "Create channel", onDismissRequest = {}, onSubmit = {}) {
                    dialogView = LocalView.current
                    ChannelFormFields(
                        name = name.value,
                        onNameChange = { name.value = it },
                        systemPrompt = prompt.value,
                        onSystemPromptChange = { prompt.value = it },
                    )
                }
            }
        }
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertIsDisplayed()
        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsDisplayed()
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(dialogView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "channel-fields-1233")
                .apply { mkdirs() }
        File(output, "emulator-create.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "capture-context.txt").writeText(
            "api=${Build.VERSION.SDK_INT} sizeDp=412x892 density=1.0 staticDark=true capture=dialogDecorView.draw " +
                "fixture=Create channel with empty name and Figma example prompt\n",
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
