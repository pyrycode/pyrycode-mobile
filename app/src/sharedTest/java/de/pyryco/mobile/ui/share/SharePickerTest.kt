package de.pyryco.mobile.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.assertDpEquals
import de.pyryco.mobile.ui.conversations.components.treeHostChannelAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostEditTestTag
import de.pyryco.mobile.ui.conversations.list.ChannelListEvent
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.HostChannelListEntry
import de.pyryco.mobile.ui.conversations.list.HostChannelListState
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.AttachmentRead
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.OwnedPasteCopy
import de.pyryco.mobile.ui.conversations.thread.PasteCopyCapture
import de.pyryco.mobile.ui.conversations.thread.PickedAttachment
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharesReuseTheFoldedOfflineTreeAndOnlyRowsChooseTheirOwnHost() {
        val entries = listOf(entry("a", RelayLinkStatus.Connected), entry("b", RelayLinkStatus.Offline))
        val state = mutableStateOf(HostChannelListState(hosts = entries))
        val picked = mutableListOf<HostConversationTarget>()
        var cancelled = false
        val preview = SharedContent(1, "caption", listOf(file("one.png", "image/png"), file("two.pdf", "application/pdf")), false)
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                ChannelListScreen(state.value, { event ->
                    when (event) {
                        is ChannelListEvent.TreeRowTapped -> picked += event.target
                        is ChannelListEvent.TreeFoldToggled ->
                            state.value =
                                state.value.copy(
                                    collapsed =
                                        state.value.collapsed.let {
                                            if (event.key in
                                                it
                                            ) {
                                                it - event.key
                                            } else {
                                                it + event.key
                                            }
                                        },
                                )
                        else -> Unit
                    }
                }, shareHeader = { SharePickerHeader(preview) { cancelled = true } })
            }
        }
        compose.onNodeWithText("Share to…").assertIsDisplayed()
        compose.onNodeWithText("2 files").assertIsDisplayed()
        compose.onNodeWithText("one.png").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("share-preview").getUnclippedBoundsInRoot()
        assertDpEquals(48.dp, bounds.right - bounds.left)
        assertDpEquals(48.dp, bounds.bottom - bounds.top)
        for (host in listOf("a", "b")) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(treeHostEditTestTag(host)),
                ).assertCountEquals(0)
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(treeHostChannelAddTestTag(host)),
                ).assertCountEquals(0)
        }
        compose.onNodeWithText("b").performClick()
        assertEquals(emptyList<HostConversationTarget>(), picked)
        compose.onNodeWithText("b").performClick()
        compose.onNodeWithText("chat-b").performClick()
        assertEquals(listOf(HostConversationTarget("b", "same")), picked)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(true, cancelled)
    }

    @Test fun emptyTextOnlyPickerCanCancelAndSummaryChangesWithImageBatch() {
        val preview = mutableStateOf(SharedContent(1, "a shared link", emptyList(), false))
        var cancelled = false
        compose.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(HostChannelListState(), {}, shareHeader = { SharePickerHeader(preview.value) { cancelled = true } })
            }
        }
        compose.onNodeWithText("a shared link").assertIsDisplayed()
        compose.onNodeWithTag("channel-list").assertIsDisplayed()
        compose.runOnIdle { preview.value = SharedContent(2, "", listOf(file("a.png", "image/png")), false) }
        compose.onNodeWithText("1 image").assertIsDisplayed()
        compose.runOnIdle { preview.value = SharedContent(3, "", listOf(file("a.png", "image/png"), file("b.jpg", "image/jpeg")), false) }
        compose.onNodeWithText("2 images").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(true, cancelled)
    }

    @Test fun capturedImagePreviewUsesPrivateBytesWithAnUnavailableOriginal() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "share-preview-test").apply { mkdirs() }
        val bitmap =
            android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
            }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        var readable = true
        val reader = AttachmentReader { if (readable) AttachmentRead.Bytes(bytes) else AttachmentRead.Unreadable }
        val captured = runBlocking { OwnedPasteCopy.capture(root, reader, "content://gone/image.png") } as PasteCopyCapture.Captured
        readable = false
        assertEquals(AttachmentRead.Unreadable, runBlocking { reader.read("content://gone/image.png") })
        val content =
            SharedContent(
                1,
                "",
                listOf(PickedAttachment("content://gone/image.png", "image.png", "image/png", captured.size, captured.copy)),
                false,
            )
        var view: android.view.View? = null
        val preview = mutableStateOf(content)
        bitmap.eraseColor(Color.GREEN)
        val replacementBytes =
            ByteArrayOutputStream()
                .apply {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
                }.toByteArray()
        val replacement =
            runBlocking {
                OwnedPasteCopy.capture(
                    root,
                    AttachmentReader {
                        AttachmentRead.Bytes(replacementBytes)
                    },
                    "content://gone/image.png",
                )
            } as PasteCopyCapture.Captured
        try {
            compose.setContent {
                view = LocalView.current
                PyrycodeMobileTheme { SharePickerHeader(preview.value, {}) }
            }
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("share-image-preview"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val tile = compose.onNodeWithTag("share-preview").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val rootView = checkNotNull(view)
                val rendered = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
                try {
                    // Match the owned-strip pixel tests: captureToImage cannot settle on Robolectric.
                    rootView.draw(Canvas(rendered))
                    val pixel = rendered.getPixel(tile.center.x.toInt(), tile.center.y.toInt())
                    assertTrue(Color.red(pixel) > 240 && Color.green(pixel) < 15 && Color.blue(pixel) < 15)
                } finally {
                    rendered.recycle()
                }
            }
            compose.runOnIdle {
                preview.value =
                    SharedContent(
                        2,
                        "",
                        listOf(PickedAttachment("content://gone/image.png", "image.png", "image/png", replacement.size, replacement.copy)),
                        false,
                    )
            }
            compose.waitUntil(5_000) {
                var green = false
                compose.runOnIdle {
                    val rootView = checkNotNull(view)
                    val rendered = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
                    rootView.draw(Canvas(rendered))
                    val pixel = rendered.getPixel(tile.center.x.toInt(), tile.center.y.toInt())
                    green = Color.green(pixel) > 240 && Color.red(pixel) < 15
                    rendered.recycle()
                }
                green
            }
        } finally {
            replacement.copy.release()
            captured.copy.release()
            assertTrue(root.listFiles().orEmpty().isEmpty())
            root.delete()
        }
    }

    @Test fun productionNavigationTransfersIntoTheChosenThreadOnlyOnce() {
        val drafts = GlobalContext.get().get<ComposerDraftStore>()
        val target = HostConversationTarget("demo", "seed-channel-personal")
        drafts.setDraft(target.serverId, target.conversationId, "existing draft")
        val intake = ShareIntakeViewModel(drafts, { _, _ -> null })
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            PyrycodeMobileTheme {
                PyryNavHost(Routes.CHANNEL_LIST, navController = nav, shareIntake = intake)
            }
        }
        compose.runOnIdle { intake.accept(SharePayload("shared text", emptyList())) }
        compose.onNodeWithText("Share to…").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Personal")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Personal").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle {
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            assertEquals("existing draft\nshared text", drafts.draftFor(target.serverId, target.conversationId))
            assertEquals("", drafts.draftFor("another-host", target.conversationId))
        }
        compose.onNode(hasSetTextAction()).assertTextContains("existing draft\nshared text")
        compose.runOnIdle { nav.popBackStack() }
        compose.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        compose.runOnIdle { assertEquals("existing draft\nshared text", drafts.draftFor(target.serverId, target.conversationId)) }
    }

    private fun file(
        name: String,
        mime: String,
    ) = PickedAttachment("content://unreadable/$name", name, mime, 1)

    private fun entry(
        host: String,
        relay: RelayLinkStatus,
    ): HostChannelListEntry {
        val chat = Conversation("same", "chat-$host", "/tmp", "session", emptyList(), false, Instant.fromEpochSeconds(0))
        return HostChannelListEntry(
            HostConversationSnapshot(host, host, ConnectionStatus(relay, PyrycodeLinkStatus.Connected), chats = listOf(chat)),
            listOf(chat),
            1,
        )
    }
}
