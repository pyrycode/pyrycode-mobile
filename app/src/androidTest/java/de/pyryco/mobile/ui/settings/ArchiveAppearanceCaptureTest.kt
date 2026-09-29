package de.pyryco.mobile.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.days

/** Real dark pixels and reachable controls for the Archive design comparison. */
@RunWith(AndroidJUnit4::class)
class ArchiveAppearanceCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private var view: View? = null

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

    @Test fun populatedTabsAt412By892() {
        val channels =
            listOf(
                archived("old-project-experiments", true, 14),
                archived("weekend-debugging", true, 30),
                archived("claude-code-evaluation", true, 60),
            )
        val discussions = (1..8).map { archived("old-discussion-$it", false, it * 3) }
        var tab by mutableStateOf(ArchiveTab.Channels)
        var hostName by mutableStateOf("")
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                ArchivedDiscussionsScreen(
                    state = ArchivedDiscussionsUiState.Loaded(channels, discussions, tab),
                    onEvent = { if (it is ArchivedDiscussionsEvent.TabSelected) tab = it.tab },
                    hostName = hostName,
                )
            }
        }
        rule.onNodeWithText("Archived 2 weeks ago").assertIsDisplayed()
        rule.onNodeWithContentDescription("Restore old-project-experiments").assertIsDisplayed()
        capture("channels-reference-412x892.png", 412, 892)
        rule.runOnIdle { hostName = "studio-mini" }
        rule.onNodeWithText("studio-mini").assertIsDisplayed()
        capture("channels-412x892.png", 412, 892)
        rule.onNodeWithText("Discussions (8)").performClick()
        rule.onNodeWithText("old-discussion-1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Restore old-discussion-1").assertIsDisplayed()
        capture("discussions-412x892.png", 412, 892)
    }

    @Test fun emptyTabsAt412By892() {
        var tab by mutableStateOf(ArchiveTab.Channels)
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                ArchivedDiscussionsScreen(
                    state = ArchivedDiscussionsUiState.Loaded(emptyList(), emptyList(), tab),
                    onEvent = { if (it is ArchivedDiscussionsEvent.TabSelected) tab = it.tab },
                    hostName = "studio-mini",
                )
            }
        }
        rule.onNodeWithText("No archived channels").assertIsDisplayed()
        capture("channels-empty-412x892.png", 412, 892)
        rule.onNodeWithText("Discussions (0)").performClick()
        rule.onNodeWithText("No archived discussions").assertIsDisplayed()
        capture("discussions-empty-412x892.png", 412, 892)
    }

    @Test fun compactLargeTextKeepsRestoreReachable() {
        shell("wm size 280x400")
        instrumentation.waitForIdleSync()
        val name = "archived-conversation-with-a-very-long-name-that-must-not-cover-the-restore-control"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    view = LocalView.current
                    ArchivedDiscussionsScreen(
                        state = ArchivedDiscussionsUiState.Loaded(listOf(archived(name, true, 14)), emptyList(), ArchiveTab.Channels),
                        onEvent = {},
                        hostName = "workstation-in-the-attic-behind-the-boiler-and-down-the-hall",
                    )
                }
            }
        }
        rule.onNodeWithText("Channels (1)").assertIsDisplayed()
        rule.onNodeWithText("Discussions (0)").assertIsDisplayed()
        val tabs = rule.onNodeWithTag("archive_tabs").getUnclippedBoundsInRoot()
        val indicator = rule.onNodeWithTag("archive_selected_indicator").getUnclippedBoundsInRoot()
        assertEquals(tabs.bottom, indicator.bottom)
        val restore = rule.onNodeWithContentDescription("Restore $name")
        restore.assertIsDisplayed()
        assertTrue(
            "long name must leave the restore control reachable",
            rule.onNodeWithText(name).getUnclippedBoundsInRoot().right < restore.getUnclippedBoundsInRoot().left,
        )
        capture("compact-280x400-large-text.png", 280, 400)
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
    ) {
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(view).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val samples = (0 until height step 8).flatMap { y -> (0 until width step 8).map { x -> bitmap.getPixel(x, y) } }
        assertTrue("capture must contain rendered content", samples.toSet().size > 10)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "archive-1265")
                .apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun archived(
        name: String,
        promoted: Boolean,
        ageDays: Int,
    ) = Conversation(
        id = name,
        name = name,
        cwd = DEFAULT_SCRATCH_CWD,
        currentSessionId = "session-$name",
        sessionHistory = emptyList(),
        isPromoted = promoted,
        lastUsedAt = Clock.System.now() - ageDays.days,
        archived = true,
    )

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
