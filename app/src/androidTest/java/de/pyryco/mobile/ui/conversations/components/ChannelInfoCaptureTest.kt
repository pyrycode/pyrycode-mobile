package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.repository.McpServerStatus
import de.pyryco.mobile.data.repository.McpStatus
import de.pyryco.mobile.data.repository.McpStatusReport
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device capture of the actual modal at the Figma viewport and a compact enlarged-text viewport. */
@RunWith(AndroidJUnit4::class)
class ChannelInfoCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var oldSize = "reset"
    private var oldDensity = "reset"

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

    @Test fun channelInfoAt412By892() {
        showSheet()
        rule.onNodeWithText("Folder").assertIsDisplayed()
        rule.onNodeWithText("Install").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Archive").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        capture("emulator-412x892.png", 412, 892)
    }

    @Test fun channelInfoAtCompactWidthWithLargeText() {
        shell("wm size 320x692")
        instrumentation.waitForIdleSync()
        showSheet(fontScale = 1.5f, longContent = true)
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("Folder").assertIsDisplayed()
        rule.onNodeWithText("Install").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertExists()
        rule.onNodeWithText("Archive").assertExists()
        rule.onNodeWithText("Delete").assertExists()
        capture("emulator-320x692-large-text.png", 320, 692)
    }

    @Test fun longProviderAtCompactWidthWithLargeText() {
        shell("wm size 320x692")
        instrumentation.waitForIdleSync()
        val provider = MemorySearchProvider("p", "Notebook Search ".repeat(10), true, true, MemorySearchAvailability.Available)
        showSheet(
            fontScale = 1.5f,
            longContent = true,
            memorySearch = MemorySearchReport(MemorySearchAvailability.Available, listOf(provider)),
        )
        rule.onNodeWithText("Memory search available").assertIsDisplayed()
        rule.onNodeWithText("Install").assertDoesNotExist()
        rule.onNodeWithText("Delete").assertExists()
        capture("emulator-320x692-provider.png", 320, 692)
    }

    /** #1488: the top of the full-height sheet in the sample state of Figma `668:5355`. */
    @Test fun channelInfoTopMatches668_5355() {
        showSheet(frameState = true)
        rule.onNodeWithText("Folder").assertIsDisplayed()
        rule.onNodeWithText("None").assertIsDisplayed()
        rule.onNodeWithText("Install").assertIsDisplayed()
        rule.onNodeWithText("Change workspace").assertDoesNotExist()
        capture("emulator-668-5355.png", 412, 892, FRAMES_FOLDER)
    }

    /** #1488: the same sheet scrolled to its end, as Figma `668:5460` shows the Actions grid and the footer. */
    @Test fun channelInfoScrolledMatches668_5460() {
        showSheet(frameState = true)
        val content = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        rule.onNode(content).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Archive").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Channel ID: ch_a8f3c2d1e9b7").assertIsDisplayed()
        capture("emulator-668-5460.png", 412, 892, FRAMES_FOLDER)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSheet(
        fontScale: Float = 1f,
        longContent: Boolean = false,
        memorySearch: MemorySearchReport = MemorySearchReport(MemorySearchAvailability.Absent, emptyList()),
        frameState: Boolean = false,
    ) {
        val name =
            if (longContent) "A very long channel name that should never overlap the close button" else "kitchenclaw refactor"
        val path =
            if (longContent) "~/Workspace/Projects/a/very/long/conversation/folder" else "~/Workspace/Projects/KitchenClaw"
        val model =
            ChannelInfoUiModel(
                conversationName = name,
                workspacePath = path,
                createdLabel = "3 weeks ago",
                lastActivityLabel = "2 hours ago",
                sessionCount = 12,
                messageCount = 347,
                memorySearch = memorySearch,
                channelId = "ch_a8f3c2d1e9b7",
                sessionFacts = if (frameState) SessionFacts("2.1.143", "acceptEdits", null) else null,
                sessionCostUsd = if (frameState) 0.42 else null,
                mcpServers = if (frameState) FRAME_MCP else null,
            )
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    ChannelInfoSheet(
                        model = model,
                        onRename = {},
                        onArchive = {},
                        onDelete = {},
                        onInstallMemoryPlugin = {},
                        onDismiss = {},
                        systemPrompt = if (frameState) FRAME_PROMPT else null,
                    )
                }
            }
        }
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        folder: String = "channel-info-1266",
    ) {
        rule.waitForIdle()
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val colorCount = (0 until height step 8).flatMap { y -> (0 until width step 8).map { x -> bitmap.getPixel(x, y) } }.toSet().size
        check(colorCount > 10) { "capture is blank" }
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                folder,
            ).apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "capture-context.txt").appendText(
            "api=${Build.VERSION.SDK_INT} size=${width}x$height density=1 fontScale=${if (width == 412) 1 else 1.5} staticDark=true\n",
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"

    private companion object {
        const val FRAMES_FOLDER = "channel-info-1488"
        val FRAME_PROMPT =
            SystemPromptEditorState.Loaded(
                confirmed = "Answer in short paragraphs.",
                appliedStatus = SessionPromptStatus.Matches,
                draft = "Answer in short paragraphs.",
            )
        val FRAME_MCP =
            McpStatus(
                report =
                    McpStatusReport(
                        servers =
                            listOf(
                                McpServerStatus("github", "connected", "", "user", ""),
                                McpServerStatus("postgres", "failed", "MCP error -32000: Connection closed", "user", ""),
                            ),
                        droppedServers = 0,
                    ),
            )
    }
}
