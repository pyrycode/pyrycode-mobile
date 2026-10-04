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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real emulator pixels for the fixed sidebar chrome and its compact and large-text states. */
@RunWith(AndroidJUnit4::class)
class SidebarToolbarCaptureTest {
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

    @Test fun sidebarAt412By892() = capture("emulator-412x892.png", 412, 892, 1f)

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
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    contentView = LocalView.current
                    ChannelListScreen(hostState = fixture(), onEvent = {})
                }
            }
        }
        rule.onNodeWithText("Pyry").assertIsDisplayed()
        val menu = rule.onNode(hasContentDescription("Open menu")).getUnclippedBoundsInRoot()
        val addHost = rule.onNode(hasContentDescription("Pair another host")).getUnclippedBoundsInRoot()
        assertEquals(44.dp, menu.right - menu.left)
        assertEquals(44.dp, menu.bottom - menu.top)
        assertTrue(menu.right <= addHost.left)
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(contentView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(expectedWidth, bitmap.width)
        assertEquals(expectedHeight, bitmap.height)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "sidebar-1202")
                .apply { mkdirs() }
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(output, name.removeSuffix(".png") + ".txt").writeText(
            "viewport=${expectedWidth}x$expectedHeight density=1 fontScale=$fontScale staticDark=true " +
                "capture=decorView.draw fixture=one connected Pyry host\n",
        )
    }

    private fun fixture(): HostChannelListState =
        HostChannelListState(
            hosts =
                listOf(
                    HostChannelListEntry(
                        host =
                            HostConversationSnapshot(
                                serverId = "pyry",
                                displayName = "Pyry",
                                connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                                channels = emptyList(),
                                chats = emptyList(),
                            ),
                        recentChats = emptyList(),
                        chatCount = 0,
                    ),
                ),
        )

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
