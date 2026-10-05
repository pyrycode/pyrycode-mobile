package de.pyryco.mobile.ui.host

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.design.ViewportRule
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only: real dialog pixels for the five dark Figma states, without a daemon or credential. */
@RunWith(AndroidJUnit4::class)
class HostPromptCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test fun darkHostAndPromptStates() {
        val state =
            mutableStateOf(
                HostEditorState("fixture", "345345-345345345-gw3vw-w4wv34-vw34t", "https://asdf.afwevawef.fwef/asdffe", "Pyrybox"),
            )
        val captureState = mutableStateOf("initial")
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                // A fresh dialog avoids reusing hardware drawing layers in a software decor capture.
                key(captureState.value) { HostEditorModal(state.value, {}, {}, {}, {}, {}) }
            }
        }
        val custom =
            "Reply in British English and keep answers short.\n" +
                "Ask before you push, merge or delete anything.\nWrite commit messages in the imperative mood."
        // Synthetic long fixture proves growing/scrolling geometry; the UI never owns a default literal.
        val default = List(22) { "Default fixture line ${it + 1}." }.joinToString("\n")
        for ((name, current, editing) in listOf(
            Triple("host-empty", "", false),
            Triple("host-filled", custom, false),
            Triple("editor-empty", "", true),
            Triple("editor-filled", custom, true),
            Triple("editor-default", default, true),
        )) {
            rule.runOnIdle {
                captureState.value = name
                state.value =
                    state.value.copy(prompt = HostPromptState.Loaded(current, default, current), editingPrompt = editing)
            }
            rule.onNodeWithText(if (editing) "Host system prompt" else "Edit host").assertIsDisplayed()
            rule.onNodeWithText("Cancel").assertIsDisplayed()
            rule.onNodeWithText("OK").assertIsDisplayed()
            if (editing) rule.onNodeWithTag(HOST_PROMPT_FIELD_TAG).assertIsDisplayed()
            capture(name, if (editing) "Host system prompt" else "Edit host")
        }
    }

    private fun capture(
        name: String,
        title: String,
    ) {
        rule.waitForIdle()
        val view = (checkNotNull(rule.onNodeWithText(title).fetchSemanticsNode().root) as ViewRootForTest).view
        rule.waitUntil(5_000) { rule.runOnIdle { view.hasWindowFocus() } }
        val bitmap =
            rule.runOnIdle {
                val root = view.rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("dialog must contain rendered pixels", pixels.toSet().size > 10)
        assertTrue("close control must be rendered", bitmap.getPixel(370, 38) != bitmap.getPixel(340, 38))
        assertTrue("OK control must be rendered", bitmap.getPixel(240, 840) != bitmap.getPixel(240, 810))
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "host-prompt-1775",
            ).apply {
                mkdirs()
            }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(
            output,
            "capture-context.txt",
        ).writeText("412x892 density=1.0 staticDark=true capture=dialogDecorView.draw fixture=synthetic\n")
    }
}
