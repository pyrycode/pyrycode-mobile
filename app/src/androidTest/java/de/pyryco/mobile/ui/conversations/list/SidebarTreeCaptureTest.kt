package de.pyryco.mobile.ui.conversations.list

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.components.EDIT_HOST_NAME_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.treeHostChannelAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostEditTestTag
import de.pyryco.mobile.ui.host.HostEditorState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** Real emulator pixels for the tree bands, including compact and enlarged-text states. */
@RunWith(AndroidJUnit4::class)
class SidebarTreeCaptureTest {
    @get:Rule(order = 0)
    val displayBeforeActivity =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    setDisplay()
                    try {
                        base.evaluate()
                    } finally {
                        restoreDisplay()
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var contentView: View? = null
    private var oldSize = "reset"
    private var oldDensity = "reset"
    private val longName = "Long channel name that must ellipsize before the neighboring edit control"

    private fun setDisplay() {
        oldSize = overrideOf(shell("wm size"))
        oldDensity = overrideOf(shell("wm density"))
        shell("wm density 160")
        shell("wm size 412x892")
        instrumentation.waitForIdleSync()
    }

    private fun restoreDisplay() {
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

    @Test fun compactCoordinateTapsOpenTheIntendedHostEditor() {
        shell("wm size 320x800")
        instrumentation.waitForIdleSync()
        val events = mutableListOf<ChannelListEvent>()
        var state by mutableStateOf(fixture(longConversationName = true))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    ChannelListScreen(
                        hostState = state,
                        onEvent = { event ->
                            events += event
                            if (event == ChannelListEvent.TreeHostEditTapped("pyry")) {
                                state =
                                    state.copy(
                                        hostEditor =
                                            HostEditorState(
                                                serverId = "pyry",
                                                serverIdentity = "server-777",
                                                relayAddress = "wss://relay.example:8443",
                                                initialName = "Pyry",
                                            ),
                                    )
                            }
                        },
                    )
                }
            }
        }
        val edit = rule.onNodeWithTag(treeHostEditTestTag("pyry")).getUnclippedBoundsInRoot()
        tap(edit.left.value + 1f, edit.midY())
        assertEquals(listOf(ChannelListEvent.TreeHostEditTapped("pyry")), events)
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertIsDisplayed()
    }

    private fun capture(
        name: String,
        expectedWidth: Int,
        expectedHeight: Int,
        fontScale: Float,
    ) {
        val events = mutableListOf<ChannelListEvent>()
        val longNames = expectedWidth == 320 || fontScale > 1f
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    contentView = LocalView.current
                    ChannelListScreen(hostState = fixture(longConversationName = longNames), onEvent = { events += it })
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
                "capture=decorView.draw fixture=Pyry host, six channels" +
                (if (longNames) " (one long ellipsized name)" else "") +
                ", collapsed Chats, collapsed second host\n",
        )

        val fold =
            rule
                .onNode(hasContentDescription(instrumentation.targetContext.getString(R.string.cd_tree_row_collapse, "Pyry")))
                .getUnclippedBoundsInRoot()
        val sectionFold =
            rule
                .onNode(hasContentDescription(instrumentation.targetContext.getString(R.string.cd_tree_row_collapse, "Channels on Pyry")))
                .getUnclippedBoundsInRoot()
        val name = if (longNames) longName else "pyrycode discord integration"
        val conversation = rule.onNodeWithText(name).getUnclippedBoundsInRoot()
        val conversationEdit =
            rule
                .onNode(hasContentDescription(instrumentation.targetContext.getString(R.string.cd_tree_channel_edit, name)))
                .getUnclippedBoundsInRoot()
        val nextConversation = rule.onNodeWithText("rocd-thinking").getUnclippedBoundsInRoot()
        assertTrue("conversation text must end before its edit control", conversation.right <= conversationEdit.left)
        assertTrue("conversation edit must stay within the viewport", conversationEdit.right <= expectedWidth.dp)
        assertTrue("long conversation name must stay on one line", conversation.height <= 24.dp)
        val hostFoldEvent = ChannelListEvent.TreeFoldToggled(TreeFoldKey(ConversationTreeSection.Host, "pyry"))
        val sectionFoldEvent = ChannelListEvent.TreeFoldToggled(TreeFoldKey(ConversationTreeSection.Channels, "pyry"))
        val addEvent = ChannelListEvent.TreeHostChannelAddTapped("pyry")
        val openC3 = ChannelListEvent.TreeRowTapped(HostConversationTarget("pyry", "c3"))
        val editC3 = ChannelListEvent.TreeChannelEditTapped(HostConversationTarget("pyry", "c3"))
        val openC4 = ChannelListEvent.TreeRowTapped(HostConversationTarget("pyry", "c4"))
        val hostEditEvent = ChannelListEvent.TreeHostEditTapped("pyry")
        val taps =
            listOf(
                Triple(fold.right.value - 1f, fold.midY(), hostFoldEvent),
                Triple(sectionFold.right.value - 1f, sectionFold.midY(), sectionFoldEvent),
                Triple(addBounds.left.value + 1f, addBounds.midY(), addEvent),
                Triple(addBounds.midX(), addBounds.midY(), addEvent),
                Triple(addBounds.right.value - 1f, addBounds.midY(), addEvent),
                Triple(conversation.left.value + 1f, conversation.midY(), openC3),
                Triple(conversation.right.value - 1f, conversation.midY(), openC3),
                Triple(conversationEdit.left.value + 1f, conversationEdit.midY(), editC3),
                Triple(conversationEdit.right.value - 1f, conversationEdit.midY(), editC3),
                Triple(nextConversation.left.value + 1f, nextConversation.midY(), openC4),
                Triple(editBounds.left.value + 1f, editBounds.midY(), hostEditEvent),
                Triple(editBounds.midX(), editBounds.midY(), hostEditEvent),
                Triple(editBounds.right.value - 1f, editBounds.midY(), hostEditEvent),
            )
        taps.forEach { (x, y, expected) ->
            val before = events.size
            tap(x, y)
            assertEquals("tap at ($x, $y) must dispatch only its own action", listOf(expected), events.drop(before))
        }
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        rule.onRoot().performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()
    }

    private fun DpRect.midX(): Float = (left.value + right.value) / 2f

    private fun DpRect.midY(): Float = (top.value + bottom.value) / 2f

    private fun fixture(longConversationName: Boolean = false): HostChannelListState {
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
                                conversation("c3", if (longConversationName) longName else "pyrycode discord integration", true),
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
