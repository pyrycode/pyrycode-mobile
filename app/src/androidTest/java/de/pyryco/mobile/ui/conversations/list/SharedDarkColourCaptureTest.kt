package de.pyryco.mobile.ui.conversations.list

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
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

/** Real 412 × 892 pixels for comparing the shared static-dark roles with Figma's sidebar frame. */
@RunWith(AndroidJUnit4::class)
class SharedDarkColourCaptureTest {
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

    @Test fun sidebarAt412By892() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                contentView = LocalView.current
                ChannelListScreen(hostState = fixture(), onEvent = {})
            }
        }
        rule.onNodeWithText("pyrycode discord integration").assertIsDisplayed()
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(contentView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(412, bitmap.width)
        assertEquals(892, bitmap.height)

        fun contains(color: Int): Boolean =
            (0 until bitmap.height step 2).any { y ->
                (0 until bitmap.width step 2).any { x -> bitmap.getPixel(x, y) == color }
            }
        // 15:8's Hover row is the selected one (#1523); its darker On Primary row is the pressed state.
        assertTrue("selected row uses Schemes/Primary Container", contains(android.graphics.Color.rgb(19, 74, 116)))
        assertTrue("unread dot uses Schemes/Success", contains(android.graphics.Color.rgb(47, 192, 56)))
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "colors-1225",
            ).apply { mkdirs() }
        File(output, "emulator-sidebar.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, "emulator-sidebar.txt").writeText(
            "api=${Build.VERSION.SDK_INT} sizeDp=412x892 density=1.0 staticDark=true capture=decorView.draw " +
                "fixture=one connected host with five named channels and one unread attention state\n",
        )
    }

    private fun fixture(): HostChannelListState {
        val names =
            listOf(
                "kitchenclaw refactor",
                "pyrycode discord integration",
                "rocd-thinking",
                "Culinary Corner",
                "Gourmet Hub",
            )
        val channels =
            names.mapIndexed { index, name ->
                Conversation(
                    id = "channel-$index",
                    name = name,
                    cwd = "/workspace",
                    currentSessionId = "session-$index",
                    sessionHistory = emptyList(),
                    isPromoted = true,
                    lastUsedAt = Instant.fromEpochSeconds(index.toLong()),
                )
            }
        val host =
            HostChannelListEntry(
                host =
                    HostConversationSnapshot(
                        serverId = "pyry",
                        displayName = "Pyry",
                        connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                        channels = channels,
                        chats = emptyList(),
                    ),
                recentChats = emptyList(),
                chatCount = 0,
                channelGroups = groupConversationsByWorkspace("pyry", channels),
                attention = mapOf("channel-2" to ConversationAttention.Unread),
            )
        return HostChannelListState(
            hosts = listOf(host),
            selected = HostConversationTarget("pyry", "channel-1"),
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
