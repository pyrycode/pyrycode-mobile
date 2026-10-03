package de.pyryco.mobile.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Duration.Companion.days

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchivedDiscussionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun populatedTabsAlignTextAndRestore_light() = assertPopulatedTabs(darkTheme = false)

    @Test fun populatedTabsAlignTextAndRestore_dark() = assertPopulatedTabs(darkTheme = true)

    @Test fun darkArchiveBackdropHasTheReferenceGlow() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            PyrycodeMobileTheme(darkTheme = true) {
                ArchivedDiscussionsScreen(
                    state = ArchivedDiscussionsUiState.Loaded(emptyList(), emptyList(), ArchiveTab.Channels),
                    onEvent = {},
                )
            }
        }
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val glow = bitmap.getPixel(view.width / 2, view.height / 3)
            val edge = bitmap.getPixel(view.width / 2, view.height - 8)
            org.junit.Assert.assertNotEquals("Archive must have the Figma radial glow", edge, glow)
            bitmap.recycle()
        }
    }

    @Test fun archivedRowsUseTheRelativeLabelsShownByFigma() {
        val now = Clock.System.now()
        val rows =
            listOf(14.days, 30.days, 60.days).mapIndexed { index, age ->
                archived("archive-$index", promoted = true).copy(lastUsedAt = now - age)
            }
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ArchivedDiscussionsScreen(
                    state = ArchivedDiscussionsUiState.Loaded(rows, emptyList(), ArchiveTab.Channels),
                    onEvent = {},
                )
            }
        }
        compose.onNodeWithText("Archived 2 weeks ago").assertIsDisplayed()
        compose.onNodeWithText("Archived 1 month ago").assertIsDisplayed()
        compose.onNodeWithText("Archived 2 months ago").assertIsDisplayed()
    }

    @Test fun darkEmptyLoadingAndErrorCopyRemainsReadable() {
        lateinit var view: View
        var state by mutableStateOf<ArchivedDiscussionsUiState>(
            ArchivedDiscussionsUiState.Loaded(emptyList(), emptyList(), ArchiveTab.Channels),
        )
        compose.setContent {
            view = LocalView.current
            PyrycodeMobileTheme(darkTheme = true) {
                ArchivedDiscussionsScreen(state = state, onEvent = {})
            }
        }
        val cases =
            listOf(
                state to "No archived channels",
                ArchivedDiscussionsUiState.Loaded(emptyList(), emptyList(), ArchiveTab.Discussions) to "No archived discussions",
                ArchivedDiscussionsUiState.Loading to "Loading…",
                ArchivedDiscussionsUiState.Error("offline") to "Couldn't load archived discussions: offline",
            )
        for ((next, copy) in cases) {
            compose.runOnIdle { state = next }
            val bounds = compose.onNodeWithText(copy).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val brightest =
                    (bounds.top.toInt() until bounds.bottom.toInt())
                        .flatMap { y ->
                            (bounds.left.toInt() until bounds.right.toInt()).map { x ->
                                val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                                val red = android.graphics.Color.red(pixel)
                                val green = android.graphics.Color.green(pixel)
                                val blue = android.graphics.Color.blue(pixel)
                                (red + green + blue) / 3
                            }
                        }.maxOrNull() ?: 0
                assertTrue("$copy should have light text on the dark canvas", brightest > 150)
                bitmap.recycle()
            }
        }
    }

    // #1332: a row archived from this phone gets its stamp only from the list reply that lands
    // after the screen first draws, so it moves in front of the row the list is anchored on.
    @Test fun rowMovedToTheTopIsShownFirstWhenTheListWasAtTheTop() {
        val rows = (0 until 20).map { archived("row-$it", promoted = false) }
        var state by mutableStateOf(ArchivedDiscussionsUiState.Loaded(emptyList(), rows, ArchiveTab.Discussions))
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) { ArchivedDiscussionsScreen(state = state, onEvent = {}) }
        }
        compose.onNodeWithContentDescription("Restore row-0").assertIsDisplayed()

        compose.runOnIdle { state = state.copy(discussions = listOf(rows.last()) + rows.dropLast(1)) }

        val moved = compose.onNodeWithContentDescription("Restore row-19").assertIsDisplayed()
        val previousTop = compose.onNodeWithContentDescription("Restore row-0").getUnclippedBoundsInRoot()
        assertTrue(moved.getUnclippedBoundsInRoot().top < previousTop.top)
    }

    @Test fun rowMovedToTheTopLeavesAScrolledListInPlace() {
        val rows = (0 until 20).map { archived("row-$it", promoted = false) }
        var state by mutableStateOf(ArchivedDiscussionsUiState.Loaded(emptyList(), rows, ArchiveTab.Discussions))
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) { ArchivedDiscussionsScreen(state = state, onEvent = {}) }
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(10)
        compose.onNodeWithContentDescription("Restore row-10").assertIsDisplayed()

        compose.runOnIdle { state = state.copy(discussions = listOf(rows.last()) + rows.dropLast(1)) }

        compose.onNodeWithContentDescription("Restore row-10").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore row-19").assertDoesNotExist()
    }

    // #1487: 18:2 repeats rows every 66 px: 12 padding, 24 title, 2 gap, 16 subtitle, 12 padding.
    @Test fun rowsRepeatEvery66Dp() {
        val rows = (0 until 3).map { archived("row-$it", promoted = true) }
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ArchivedDiscussionsScreen(
                    state = ArchivedDiscussionsUiState.Loaded(rows, emptyList(), ArchiveTab.Channels),
                    onEvent = {},
                )
            }
        }
        val tops = rows.map { compose.onNodeWithContentDescription("Restore ${it.name}").getUnclippedBoundsInRoot().top }
        assertEquals(66.dp, tops[1] - tops[0])
        assertEquals(66.dp, tops[2] - tops[1])
    }

    // #1487: at 320x700 and 150 % font scale each label stays on one line inside its tab, above the indicator.
    @Test fun tabLabelsStayOnOneLineAt320By700LargeText() {
        val size = DpSize(320.dp, 700.dp)
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                        ArchivedDiscussionsScreen(
                            state =
                                ArchivedDiscussionsUiState.Loaded(
                                    listOf(archived("old-project", promoted = true)),
                                    listOf(archived("old-discussion", promoted = false)),
                                    ArchiveTab.Channels,
                                ),
                            onEvent = {},
                            hostName = "studio-mini",
                            modifier = Modifier.size(size),
                        )
                    }
                }
            }
        }
        val indicator = compose.onNodeWithTag("archive_selected_indicator").getUnclippedBoundsInRoot()
        val tabs = compose.onNodeWithTag("archive_tabs").getUnclippedBoundsInRoot()
        val half = (tabs.right - tabs.left) / 2
        for ((index, label) in listOf("Channels (1)", "Discussions (1)").withIndex()) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(
                    label,
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$label lines", 1, layout.lineCount)
            assertFalse("$label is cut off", layout.hasVisualOverflow)
            val bounds = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$label leaves its tab", bounds.left >= tabs.left + half * index && bounds.right <= tabs.left + half * (index + 1))
            assertTrue("$label overruns the indicator", bounds.bottom <= indicator.top)
        }
    }

    private fun assertPopulatedTabs(darkTheme: Boolean) {
        val channel = archived("old-project", promoted = true)
        val discussion = archived("old-discussion", promoted = false)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            var tab by remember { mutableStateOf(ArchiveTab.Discussions) }
            PyrycodeMobileTheme(darkTheme = darkTheme) {
                ArchivedDiscussionsScreen(
                    state = ArchivedDiscussionsUiState.Loaded(listOf(channel), listOf(discussion), tab),
                    onEvent = { if (it is ArchivedDiscussionsEvent.TabSelected) tab = it.tab },
                    hostName = "studio-mini",
                )
            }
        }

        for ((tab, name) in listOf("Discussions (1)" to "old-discussion", "Channels (1)" to "old-project")) {
            compose.onNodeWithText(tab).performClick()
            compose.onNodeWithText(name).assertLeftPositionInRootIsEqualTo(16.dp)
            compose.onNodeWithText("Archived 3d ago").assertLeftPositionInRootIsEqualTo(16.dp)
            compose.onNodeWithText("studio-mini").assertIsDisplayed()
            val header = compose.onNodeWithTag("archive_header").getUnclippedBoundsInRoot()
            val tabs = compose.onNodeWithTag("archive_tabs").getUnclippedBoundsInRoot()
            val indicator = compose.onNodeWithTag("archive_selected_indicator").getUnclippedBoundsInRoot()
            assertEquals(64.dp, header.bottom - header.top)
            assertEquals(48.dp, tabs.bottom - tabs.top)
            assertEquals(2.dp, indicator.bottom - indicator.top)
            assertEquals(tabs.bottom, indicator.bottom)
            if (tab.startsWith("Channels")) {
                assertEquals(tabs.left, indicator.left)
            } else {
                assertEquals(tabs.right, indicator.right)
            }
            assertTrue(compose.onNodeWithText(name).getUnclippedBoundsInRoot().top >= tabs.bottom + 4.dp)
            val icon = compose.onNodeWithContentDescription("Restore $name", useUnmergedTree = true)
            icon.assertIsDisplayed()
            val bounds = icon.getUnclippedBoundsInRoot()
            val screen = compose.onRoot().getUnclippedBoundsInRoot()
            assertEquals(22.dp, bounds.right - bounds.left)
            assertEquals(22.dp, bounds.bottom - bounds.top)
            assertEquals(screen.right - 25.dp, bounds.right)

            // Optional local visual evidence; normal shared/device runs create no files.
            System.getProperty("archive.capture.dir")?.let { directory ->
                val file = File(directory, "archive-${if (darkTheme) "dark" else "light"}-$name.png")
                compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    private fun archived(
        name: String,
        promoted: Boolean,
    ) = Conversation(
        id = name,
        name = name,
        cwd = DEFAULT_SCRATCH_CWD,
        currentSessionId = "session-$name",
        sessionHistory = emptyList(),
        isPromoted = promoted,
        lastUsedAt = Clock.System.now() - 3.days,
        archived = true,
    )
}
