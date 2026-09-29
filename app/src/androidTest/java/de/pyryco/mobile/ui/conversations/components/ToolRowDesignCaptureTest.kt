package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device pixels for the simple and described Figma tool-use variants. */
@RunWith(AndroidJUnit4::class)
class ToolRowDesignCaptureTest {
    @get:Rule val rule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private var variant by mutableIntStateOf(0)
    private var contentView: View? = null

    @Before
    fun setViewport() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        shell("wm density 160")
        shell("wm size 412x892")
        instrumentation.waitForIdleSync()
    }

    @After
    fun restoreViewport() {
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        instrumentation.waitForIdleSync()
    }

    @Test
    fun referenceVariantsAt412By892() {
        showRow()
        rule.onNodeWithText("read_file").assertIsDisplayed()
        assertTrue(
            "simple row should meet the 36 dp design height",
            rule
                .onNode(hasClickAction())
                .fetchSemanticsNode()
                .boundsInRoot.height >= 36f,
        )
        capture("emulator-simple.png", 412, 892, 1f)

        variant = 1
        rule.onNodeWithText(DESCRIPTION).assertIsDisplayed()
        rule.onNodeWithTag(TOOL_DESCRIPTION_CHEVRON_TAG, useUnmergedTree = true).assertIsDisplayed()
        capture("emulator-collapsed.png", 412, 892, 1f)

        rule.onNode(hasClickAction()).performClick()
        rule
            .onNodeWithText("function migrateLegacyOrders", substring = true)
            .assertIsDisplayed()
        capture("emulator-expanded.png", 412, 892, 1f)
    }

    @Test
    fun compactLargeTextKeepsDescriptionAndStatusReachable() {
        shell("wm size 320x700")
        instrumentation.waitForIdleSync()
        variant = 1
        showRow(fontScale = 1.5f)
        rule.onNodeWithText(DESCRIPTION).assertIsDisplayed()
        rule.onNodeWithTag(TOOL_DESCRIPTION_CHEVRON_TAG, useUnmergedTree = true).assertIsDisplayed()
        val done = instrumentation.targetContext.getString(R.string.cd_tool_done)
        rule.onNodeWithContentDescription(done).assertIsDisplayed()
        rule.onNode(hasClickAction()).performClick()
        rule.onNodeWithText("Input").assertIsDisplayed()
        capture("emulator-compact-large-text.png", 320, 700, 1.5f)
        rule.onNodeWithTag(TOOL_EXPANDED_BODY_TAG, useUnmergedTree = true).performTouchInput { swipeUp() }
        rule.onNodeWithText("Output").assertIsDisplayed()
        capture("emulator-compact-large-text-scrolled.png", 320, 700, 1.5f)
    }

    private fun showRow(fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    contentView = LocalView.current
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Spacer(Modifier.height(97.dp))
                            ToolCallRow(toolCall = if (variant == 0) SIMPLE else DESCRIBED)
                        }
                    }
                }
            }
        }
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        fontScale: Float,
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
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "tool-row-1208")
                .apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText(
            "api=${Build.VERSION.SDK_INT} build=${shell("getprop ro.build.version.incremental").trim()} " +
                "sizeDp=${width}x$height density=1.0 fontScale=$fontScale staticDark=true " +
                "capture=decorView.draw design=16:8,134:4881,134:4904,134:4895,134:4890 date=2026-09-30\n",
        )
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String): String =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"

    private companion object {
        const val DESCRIPTION = "Listed assistant docs and read elli-management"
        val SIMPLE =
            ToolCall(
                toolName = "read_file",
                input = "kitchenclaw/db/schema.ts",
                inputFields = mapOf("file_path" to "kitchenclaw/db/schema.ts"),
                output = "schema content",
                status = ToolCallStatus.Done,
            )
        val DESCRIBED =
            ToolCall(
                toolName = "Bash",
                input = "migrate legacy orders",
                inputFields =
                    mapOf(
                        "description" to DESCRIPTION,
                        "command" to
                            "function migrateLegacyOrders(legacy: LegacyOrder[]): Order[] {\n" +
                            "  return legacy.map(o => o.toModern())\n}",
                    ),
                output =
                    "The snapshot route above copies the short-lived access token. " +
                        "It is fine for a test that runs and exits inside the token's life.",
                status = ToolCallStatus.Done,
            )
    }
}
