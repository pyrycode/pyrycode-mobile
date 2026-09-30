package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Device-rendered permission gate evidence. Compose capture retains pixels behind FLAG_SECURE. */
class ThreadPermissionCaptureTest {
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
    fun permissionGateAtFigmaViewport() {
        val accepted = mutableStateOf(false)
        val armed = mutableStateOf<String?>(null)
        val modal =
            ModalUiState.Open(
                modalId = "capture",
                modalClass = "permission",
                title = "Permission required",
                prompt = "Allow this action in the current session?",
                options =
                    listOf(
                        ModalOption("allow_once", "Allow once"),
                        ModalOption("reject_once", "Reject once"),
                    ),
                defaultOptionId = "reject_once",
                alwaysAllowRules = listOf("Only for this session"),
            )
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                PermissionModalOverlay(
                    open = modal,
                    armedOptionId = armed.value,
                    onOption = { if (it == "allow_once") armed.value = it },
                    onCancel = {},
                    alwaysAllowAccepted = accepted.value,
                    onAlwaysAllowChanged = { _, value -> accepted.value = value },
                )
            }
        }
        rule.onNodeWithText("Reject once").assertIsDisplayed()
        capture("permission-unchecked-412x892.png")

        val offerLabel = instrumentation.targetContext.getString(R.string.modal_always_allow_label)
        rule.onNodeWithText(offerLabel).performClick()
        rule.onNodeWithText(offerLabel).assertIsDisplayed()
        capture("permission-checked-412x892.png")

        rule.onNodeWithText("Allow once").performClick()
        capture("permission-armed-412x892.png")
    }

    private fun capture(name: String) {
        val image =
            rule
                .onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Permission required"))
                .captureToImage()
                .asAndroidBitmap()
        assertEquals(412, image.width)
        assertEquals(892, image.height)
        val colors = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        assertTrue("actual modal pixels must be visible", colors.toSet().size > 10)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "permission-1300")
                .apply { mkdirs() }
        File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
