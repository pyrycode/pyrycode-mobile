package de.pyryco.mobile.ui.conversations.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharePickerTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain =
        RuleChain
            .outerRule(
                object : ExternalResource() {
                    override fun before() {
                        ApplicationProvider.getApplicationContext<android.content.Context>()
                        clearDrafts()
                    }

                    override fun after() {
                        clearDrafts()
                    }
                },
            ).around(compose)

    private fun clearDrafts() {
        val drafts = GlobalContext.get().get<ComposerDraftStore>()
        // The outer rule cleans both text and files only after the thread composition has closed.
        for (host in listOf("demo", "another-host")) {
            drafts.clearConversation(host, "seed-channel-personal")
        }
    }

    @Test fun sharesReuseTheFoldedOfflineTreeAndOnlyRowsChooseTheirOwnHost() {
        val entries = listOf(entry("a", RelayLinkStatus.Connected), entry("b", RelayLinkStatus.Offline))
        val state = mutableStateOf(HostChannelListState(hosts = entries))
        val picked = mutableListOf<HostConversationTarget>()
        var cancelled = false
        val preview =
            mutableStateOf(SharedContent(1, "caption", listOf(file("one.png", "image/png"), file("two.pdf", "application/pdf")), true))
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
                }, shareHeader = {
                    SharePickerHeader(
                        preview.value,
                    ) { cancelled = true }
                }, conversationSelectionEnabled = !preview.value.capturing)
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
        compose.onNodeWithText("chat-a").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("chat-b").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(emptyList<HostConversationTarget>(), picked)
        compose.runOnIdle { preview.value = SharedContent(1, "caption", preview.value.files, false) }
        compose.onNodeWithText("chat-b").assertIsEnabled()
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

    @Test fun productionNavigationDisablesSelectionUntilCaptureThenTransfersIntoTheChosenThreadOnlyOnce() {
        val drafts = GlobalContext.get().get<ComposerDraftStore>()
        val target = HostConversationTarget("demo", "seed-channel-personal")
        drafts.setDraft(target.serverId, target.conversationId, "existing draft")
        val capture = CompletableDeferred<PickedAttachment>()
        val intake = ShareIntakeViewModel(drafts, { _, _ -> capture.await() }, Dispatchers.Main.immediate)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            PyrycodeMobileTheme {
                PyryNavHost(Routes.CHANNEL_LIST, navController = nav, shareIntake = intake)
            }
        }
        compose.runOnIdle { intake.accept(SharePayload("shared text", listOf(Uri.parse("content://foreign/document")))) }
        compose.onNodeWithText("Share to…").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Personal")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-capturing").assertIsDisplayed()
        compose.onNodeWithText("Personal").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Channels").assertIsEnabled().performClick()
        compose.onAllNodes(hasText("Personal")).assertCountEquals(0)
        compose.onNodeWithText("Channels").performClick()
        compose.onNodeWithText("Personal").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals("existing draft", drafts.draftFor(target.serverId, target.conversationId))
            assertTrue(drafts.attachmentsFor(target.serverId, target.conversationId).isEmpty())
            assertEquals(false, intake.select(target))
            capture.complete(file("document.pdf", "application/pdf"))
        }
        compose.onNodeWithTag("share-ready").assertIsDisplayed()
        compose.onNodeWithText("Personal").assertIsEnabled()
        compose.onNodeWithText("Personal").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle {
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            assertEquals("existing draft\nshared text", drafts.draftFor(target.serverId, target.conversationId))
            assertEquals("", drafts.draftFor("another-host", target.conversationId))
            assertEquals(listOf("document.pdf"), drafts.attachmentsFor(target.serverId, target.conversationId).map { it.displayName })
            assertTrue(drafts.attachmentsFor("another-host", target.conversationId).isEmpty())
            assertEquals(false, intake.select(target))
        }
        compose.onNode(hasSetTextAction()).assertTextContains("existing draft\nshared text")
        compose.runOnIdle { nav.popBackStack() }
        compose.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        compose.runOnIdle { assertEquals("existing draft\nshared text", drafts.draftFor(target.serverId, target.conversationId)) }
    }

    @Test fun directShareWaitsForCaptureAndMergesTheExactDraftWithoutPickerOrReplay() {
        val drafts = GlobalContext.get().get<ComposerDraftStore>()
        val target = HostConversationTarget("demo", "seed-channel-personal")
        val publisher = GlobalContext.get().get<SharingShortcuts>()
        runBlocking { publisher.opened(target) }
        val id = RecentShareTargets(4).id(target)
        drafts.setDraft(target.serverId, target.conversationId, "existing")
        val capture = CompletableDeferred<PickedAttachment>()
        val intake = ShareIntakeViewModel(drafts, { _, _ -> capture.await() }, Dispatchers.Main.immediate)
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            PyrycodeMobileTheme { PyryNavHost(Routes.CHANNEL_LIST, navController = nav, shareIntake = intake) }
        }
        compose.runOnIdle { intake.accept(SharePayload("direct", listOf(Uri.parse("content://foreign/file")), id)) }
        compose.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals("existing", drafts.draftFor(target.serverId, target.conversationId))
            capture.complete(file("direct.pdf", "application/pdf"))
        }
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle {
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            assertEquals("existing\ndirect", drafts.draftFor(target.serverId, target.conversationId))
            assertEquals(1, drafts.attachmentsFor(target.serverId, target.conversationId).size)
            assertEquals(false, intake.select(target))
        }
        compose.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).assertTextContains("existing\ndirect")
    }

    @Test fun unknownDirectShareFallsBackWithCapturedBatchAndDraftUnchanged() {
        val drafts = GlobalContext.get().get<ComposerDraftStore>()
        val target = HostConversationTarget("demo", "seed-channel-personal")
        val existingDraft = "existing draft\nkeep unchanged"
        drafts.setDraft(target.serverId, target.conversationId, existingDraft)
        val captured = file("keep.pdf", "application/pdf")
        val intake = ShareIntakeViewModel(drafts, { _, _ -> captured }, Dispatchers.Main.immediate)
        compose.setContent { PyrycodeMobileTheme { PyryNavHost(Routes.CHANNEL_LIST, shareIntake = intake) } }
        compose.runOnIdle { intake.accept(SharePayload("keep this", listOf(Uri.parse("content://foreign/keep")), "unknown")) }
        compose.waitUntil(5_000) { intake.state.value?.shortcutId == null }
        compose.onNodeWithText("Share to…").assertIsDisplayed()
        compose.onNodeWithTag("share-ready").assertIsDisplayed()
        compose.onNodeWithText("1 file").assertIsDisplayed()
        compose.onNodeWithText("keep.pdf").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("keep this", intake.state.value?.text)
            assertEquals(listOf(captured), intake.state.value?.files)
            assertEquals(existingDraft, drafts.draftFor(target.serverId, target.conversationId))
            assertTrue(drafts.attachmentsFor(target.serverId, target.conversationId).isEmpty())
        }
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
