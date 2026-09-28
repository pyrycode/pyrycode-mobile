package de.pyryco.mobile.ui.conversations.list

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.components.treeHostChannelAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostEditTestTag
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real emulator pixels for the tree bands, including compact and enlarged-text states. */
@RunWith(AndroidJUnit4::class)
class SidebarTreeCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var contentView: View? = null
    private var oldSize = "reset"
    private var oldDensity = "reset"

    @Before fun setDisplay() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        shell("wm density 160")
        shell("wm size 412x892")
        instrumentation.waitForIdleSync()
    }

    @After fun restoreDisplay() {
        shell("wm size $oldSize")
        shell("wm density $oldDensity")
        instrumentation.waitForIdleSync()
    }

    @Test fun treeAt412By892() = capture("emulator-412x892.png", 412, 892, 1f)

    @Test fun compactAt320By800() {
        shell("wm size 320x800")
        instrumentation.waitForIdleSync()
        capture("emulator-320x800.png", 320, 800, 1f)
    }

    @Test fun enlargedTextAt412By892() = capture("emulator-412x892-large-text.png", 412, 892, 1.5f)

    private fun capture(
        name: String,
        expectedWidth: Int,
        expectedHeight: Int,
        fontScale: Float,
    ) {
        val events = mutableListOf<ChannelListEvent>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    contentView = LocalView.current
                    ChannelListScreen(hostState = fixture(), onEvent = { events += it })
                }
            }
        }
        rule.onNodeWithText("Pyry").assertIsDisplayed()
        val hostEdit = rule.onNodeWithTag(treeHostEditTestTag("pyry"))
        val channelAdd = rule.onNodeWithTag(treeHostChannelAddTestTag("pyry"))
        val editBounds = hostEdit.getUnclippedBoundsInRoot()
        val addBounds = channelAdd.getUnclippedBoundsInRoot()
        assertTrue(editBounds.right > editBounds.left)
        assertTrue(addBounds.right > addBounds.left)
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(contentView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(expectedWidth, bitmap.width)
        assertEquals(expectedHeight, bitmap.height)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "sidebar-1203")
                .apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, name.removeSuffix(".png") + ".txt").writeText(
            "viewport=${expectedWidth}x$expectedHeight density=1 fontScale=$fontScale staticDark=true " +
                "capture=decorView.draw fixture=Pyry host, six channels, collapsed Chats, collapsed second host\n",
        )

        hostEdit.performClick()
        channelAdd.performClick()
        rule.onNodeWithText("pyrycode discord integration").performClick()
        assertEquals(
            listOf(
                ChannelListEvent.TreeHostEditTapped("pyry"),
                ChannelListEvent.TreeHostChannelAddTapped("pyry"),
                ChannelListEvent.TreeRowTapped(HostConversationTarget("pyry", "c3")),
            ),
            events,
        )
    }

    private fun fixture(): HostChannelListState {
        fun conversation(
            id: String,
            name: String,
            promoted: Boolean,
        ) = Conversation(
            id = id,
            name = name,
            cwd = "/fixture",
            currentSessionId = "$id-session",
            sessionHistory = emptyList(),
            isPromoted = promoted,
            lastUsedAt = Instant.fromEpochSeconds(0),
        )

        fun entry(
            serverId: String,
            name: String,
            channels: List<Conversation>,
            chats: List<Conversation>,
        ) = HostChannelListEntry(
            host =
                HostConversationSnapshot(
                    serverId = serverId,
                    displayName = name,
                    connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                    channels = channels,
                    chats = chats,
                ),
            recentChats = chats,
            chatCount = chats.size,
            attention = mapOf("c4" to ConversationAttention.Unread, "c5" to ConversationAttention.Failed),
        )
        return HostChannelListState(
            hosts =
                listOf(
                    entry(
                        "pyry",
                        "Pyry",
                        channels =
                            listOf(
                                conversation("c1", "kitchenclaw refactor", true),
                                conversation("c2", "kitchenclaw refactor", true),
                                conversation("c3", "pyrycode discord integration", true),
                                conversation("c4", "rocd-thinking", true),
                                conversation("c5", "Culinary Corner", true),
                                conversation("c6", "Gourmet Hub", true),
                            ),
                        chats = emptyList(),
                    ),
                    entry("second", "MB Second brain", emptyList(), emptyList()),
                ),
            collapsed = setOf(TreeFoldKey(ConversationTreeSection.Host, "second"), TreeFoldKey(ConversationTreeSection.Chats, "pyry")),
            selected = HostConversationTarget("pyry", "c3"),
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output
            .lineSequence()
            .firstOrNull { it.startsWith("Override") }
            ?.substringAfter(": ") ?: "reset"
}
