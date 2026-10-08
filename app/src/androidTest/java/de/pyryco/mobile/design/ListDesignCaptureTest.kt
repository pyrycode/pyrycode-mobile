package de.pyryco.mobile.design

import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.ThreadDestinationFactory
import de.pyryco.mobile.ui.components.CHANNEL_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.CHANNEL_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHAT_ROW_TEST_TAG
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * List-side surfaces of the assembled app (design-1220/list): the channel list, Settings, Archive, Edit host,
 * Edit channel, the thread overflow menu and Channel Info, and the List states `670:5299` (#1504): Create
 * channel, Archive's empty tabs, Rename, Save as channel, the Delete confirmation and the disconnected,
 * re-pair and update host rows. At the frames' 412x892 viewport and again at 320x700 with 150 % font scale.
 */
@RunWith(AndroidJUnit4::class)
class ListDesignCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    @Test fun listFramesAt412By892() = walk(suffix = "")

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun listFramesAt320By700LargeText() = walk(suffix = "-compact")

    /** Real bars, hardware pixels and a settled dialog IME require the full device image. */
    @Test
    fun promptFailedFramesAt412By892() {
        design.paired = true
        val koin = GlobalContext.get()
        val previous = koin.get<HostConversationSource>()
        val fake = koin.get<FakeConversationRepository>()
        val createdIds = MutableStateFlow<List<String>>(emptyList())
        val previousFailure = design.inputs.failSystemPromptWrites
        val failing =
            object : ConversationRepository by fake {
                override suspend fun createChannel(
                    name: String,
                    workspace: String?,
                ): Conversation = fake.createChannel(name, workspace).also { createdIds.value += it.id }

                override suspend fun setSystemPrompt(
                    conversationId: String,
                    systemPrompt: String?,
                ) {
                    assertEquals(FRAME_PROMPT, systemPrompt)
                    val confirmed = fake.observeConversations(ConversationFilter.Channels).first().single { it.id == conversationId }
                    assertTrue(confirmed.isPromoted)
                    error("deterministic prompt write failure")
                }
            }
        val source =
            HostConversationSource(
                MutableStateFlow(
                    listOf(
                        HostConversationConnection(
                            HostConversationSource.DEMO_SERVER_ID,
                            "Demo",
                            MutableStateFlow(failing),
                            MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                        ),
                    ),
                ),
                { serverId -> failing.takeIf { serverId == HostConversationSource.DEMO_SERVER_ID } },
                viewing = koin.get(),
            )
        loadKoinModules(module { single { source } })
        try {
            design.inputs.failSystemPromptWrites = true
            relaunch()
            rule.onNodeWithContentDescription("New channel on Demo").performClick()
            capturePromptFailure(
                "Create channel",
                R.string.create_channel_prompt_failed,
                "create-channel-prompt-failed",
                "784:7095",
            )
            val created =
                runBlocking {
                    fake.observeConversations(ConversationFilter.Channels).first().single {
                        it.id ==
                            createdIds.value.single()
                    }
                }
            assertEquals(FRAME_NAME, created.name)
            assertTrue(created.isPromoted)
            // Keep the same-name channel present to exercise duplicate-name routing.
            design.scenario?.close()

            val sameNameChat = runBlocking { fake.createDiscussion(null) }
            createdIds.value += sameNameChat.id
            runBlocking { fake.rename(sameNameChat.id, FRAME_NAME) }
            val chat = runBlocking { fake.createDiscussion(null) }
            createdIds.value += chat.id
            val routeName = "Prompt capture ${chat.id}"
            runBlocking { fake.rename(chat.id, routeName) }
            relaunch()
            val chatRow = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(routeName)
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(chatRow)
            rule.onNode(chatRow).assertIsDisplayed().performClick()
            rule.waitUntil(5_000) {
                design.inputs.thread.value
                    ?.state
                    ?.value
                    ?.conversationId == chat.id
            }
            design.openHeaderMenu()
            rule.onNodeWithText("Save as channel…").performClick()
            capturePromptFailure(
                "Save as channel",
                R.string.save_as_channel_prompt_failed,
                "save-as-channel-prompt-failed",
                "784:7134",
            )
            val channels = runBlocking { fake.observeConversations(ConversationFilter.Channels).first() }
            val saved = channels.single { it.id == chat.id }
            assertEquals(FRAME_NAME, saved.name)
            assertTrue(saved.isPromoted)
            assertEquals(created, channels.single { it.id == created.id })
            val decoy =
                runBlocking { fake.observeConversations(ConversationFilter.Discussions).first().single { it.id == sameNameChat.id } }
            assertEquals(FRAME_NAME, decoy.name)
        } finally {
            design.scenario?.close()
            design.inputs.failSystemPromptWrites = previousFailure
            loadKoinModules(module { single { previous } })
            source.dispose()
            runBlocking { createdIds.value.forEach { fake.delete(it) } }
        }
    }

    private fun capturePromptFailure(
        title: String,
        errorResource: Int,
        name: String,
        figmaNode: String,
    ) {
        awaitText(title)
        awaitText("Channel name:")
        val modal = awaitModalFocus(rule.onNodeWithText(title))
        openChannelFormKeyboard(modal)
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextReplacement(FRAME_NAME)
        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextReplacement(FRAME_PROMPT)
        shell("input keyevent KEYCODE_BACK")
        awaitModalKeyboard(modal, visible = false)
        assertModalKeyboardClosed(modal)
        rule.onNodeWithText("OK").assertIsEnabled().performClick()
        val error = InstrumentationRegistry.getInstrumentation().targetContext.getString(errorResource)
        awaitText(error)
        val field = rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG)
        field.assertIsDisplayed().assertTextEquals(FRAME_NAME).assertIsNotEnabled()
        val prompt = rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG)
        prompt.assertIsDisplayed().assertTextEquals(FRAME_PROMPT).assertIsEnabled()
        rule.onNodeWithText("OK").assertIsDisplayed().assertIsEnabled()
        val errorNode = rule.onNodeWithText(error).fetchSemanticsNode()
        assertTrue("error ends the form content", errorNode.boundsInRoot.top >= prompt.fetchSemanticsNode().boundsInRoot.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val nameStyle = layouts.single().layoutInput.style
        assertEquals("locked name text opacity", 0.38f, nameStyle.color.alpha, 0.01f)
        assertEquals(modal, (checkNotNull(errorNode.root) as ViewRootForTest).view)
        awaitModalKeyboard(modal, visible = false)
        assertModalKeyboardClosed(modal)
        design.capture(FOLDER, name, figmaNode)
        val location = IntArray(2)
        rule.runOnIdle { modal.getLocationOnScreen(location) }
        val dialogInsets = rule.runOnIdle { checkNotNull(ViewCompat.getRootWindowInsets(modal)) }
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "design-1220/$FOLDER/$name.txt",
            )
        output.appendText(
            "dialogWindowFocused=true keyboardOpenObserved=true backKeptForm=true " +
                "dialogImeVisible=${dialogInsets.isVisible(WindowInsetsCompat.Type.ime())} " +
                "dialogImePx=${dialogInsets.getInsets(WindowInsetsCompat.Type.ime())} lockedNameAlpha=${nameStyle.color.alpha}\n",
        )
        listOf("name" to field, "prompt" to prompt, "error" to rule.onNodeWithText(error)).forEach { (label, node) ->
            val bounds = node.fetchSemanticsNode().boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
            output.appendText("$label screenBoundsPx=$bounds\n")
        }
        assertModalKeyboardClosed(modal)
    }

    /** Real bars and hardware pixels are the reason this notice capture is device-only. */
    @Test
    fun failedCreateChatNoticeAt412By892() {
        design.paired = true
        val koin = GlobalContext.get()
        val previous = koin.get<HostConversationSource>()
        val fake = koin.get<FakeConversationRepository>()
        val failing =
            object : ConversationRepository by fake {
                override suspend fun createDiscussion(workspace: String?): Conversation = error("deterministic create failure")
            }
        val source =
            HostConversationSource(
                MutableStateFlow(
                    listOf(
                        HostConversationConnection(
                            HostConversationSource.DEMO_SERVER_ID,
                            "Demo",
                            MutableStateFlow(failing),
                            MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                        ),
                    ),
                ),
                { serverId -> failing.takeIf { serverId == HostConversationSource.DEMO_SERVER_ID } },
                viewing = koin.get(),
            )
        loadKoinModules(module { single { source } })
        try {
            relaunch()
            rule.onAllNodes(hasText("Couldn’t create the chat. Try again.")).assertCountEquals(0)
            rule.onNodeWithContentDescription("New chat on Demo").performClick()
            awaitText("Couldn’t create the chat. Try again.")
            // Reuse the Error pill and placement, not the thread frame's unrelated contents.
            design.capture(FOLDER, "create-chat-failed", "685:4337")
        } finally {
            loadKoinModules(module { single { previous } })
            source.dispose()
        }
    }

    /** Real bars and hardware pixels are the reason this restore notice capture is device-only. */
    @Test
    fun failedRestoreNoticeAt412By892() {
        design.paired = true
        val koin = GlobalContext.get()
        val fake = koin.get<FakeConversationRepository>()
        val failing =
            object : ConversationRepository by fake {
                override suspend fun unarchive(conversationId: String): Unit = error("deterministic restore failure")
            }
        // Archive's demo destination resolves the fake directly, rather than through the list source.
        loadKoinModules(module { viewModel { ArchivedDiscussionsViewModel(failing, flowOf("Demo")) } })
        try {
            archiveChannels {
                relaunch()
                rule.onNodeWithContentDescription("Open menu").performClick()
                rule.onNodeWithText("Archive").performClick()
                awaitText("Archived", substring = true, count = it + 1)
                rule.onAllNodesWithTag("archive-restore-error").assertCountEquals(0)
                // Hold the transient notice while hardware rendering and capture settle on a cold device.
                // Its real lifetime is tested with the controlled clock in ArchiveRestoreNoticeTest.
                rule.mainClock.autoAdvance = false
                try {
                    rule.onAllNodes(hasContentDescription("Restore", substring = true)).onFirst().performClick()
                    rule.waitUntil(5_000) {
                        rule.mainClock.advanceTimeByFrame()
                        rule.onAllNodesWithTag("archive-restore-error").fetchSemanticsNodes().isNotEmpty()
                    }
                    rule.onNodeWithText("Couldn't restore this conversation. Try again.").assertIsDisplayed()
                    // #1604 authorises the Error pill and placement reuse, not a replacement Archive layout.
                    design.capture(FOLDER, "restore-failed", "685:4337")
                } finally {
                    rule.mainClock.autoAdvance = true
                }
            }
        } finally {
            loadKoinModules(module { viewModel { get<ThreadDestinationFactory>().archive(get()) } })
        }
    }

    private fun walk(suffix: String) {
        design.paired = true
        relaunch()
        assertNoWorkspaceText()
        design.capture(FOLDER, "channel-list$suffix", "15:8")

        rule.onNodeWithContentDescription("New channel on Demo").performClick()
        awaitText("Create channel")
        design.capture(FOLDER, "create-channel$suffix", "671:5558")
        relaunch()

        rule.onNodeWithContentDescription("Open menu").performClick()
        rule.onNodeWithText("Settings").performClick()
        awaitText("Notification sound")
        awaitModalFocus(rule.onNodeWithText("Notification sound"))
        assertNoWorkspaceText()
        design.capture(FOLDER, "settings$suffix", "17:2")

        // Before the walk archives any channel, Archive's Channels tab is the empty frame.
        relaunch()
        rule.onNodeWithContentDescription("Open menu").performClick()
        rule.onNodeWithText("Archive").performClick()
        awaitText("No archived channels")
        design.capture(FOLDER, "archive-empty-channels$suffix", "673:3577")

        archiveChannels {
            relaunch()
            rule.onNodeWithContentDescription("Open menu").performClick()
            rule.onNodeWithText("Archive").performClick()
            awaitText("Archived")
            // Archive opens on Channels, as 18:2 does (#1487), so the frame's state needs no tap.
            awaitText("Archived", substring = true, count = it + 1)
            design.capture(FOLDER, "archive$suffix", "18:2")
            tap(rule.onNodeWithText("Discussions (", substring = true))
            awaitText("Untitled discussion")
            design.capture(FOLDER, "archive-discussions$suffix", "none")
            withoutArchivedDiscussions {
                awaitText("No archived discussions")
                design.capture(FOLDER, "archive-empty-discussions$suffix", "673:3621")
            }
        }

        // The startup store answers list() alone; the host editor also reads loadById, so it needs the demo entry.
        val startup = GlobalContext.get().get<PairedServerCollectionStore>()
        loadKoinModules(
            module {
                single<PairedServerCollectionStore> {
                    object : PairedServerCollectionStore by startup {
                        override suspend fun loadById(serverId: String) = DEMO_HOST.takeIf { serverId == it.record.serverId }
                    }
                }
            },
        )
        relaunch()
        rule.onNodeWithContentDescription("Edit host", substring = true).performClick()
        awaitText("Host name:")
        assertIdentityLabelsWrapOnlyBetweenWords()
        design.capture(FOLDER, "edit-host$suffix", "533:2369")
        val modal = awaitModalFocus(rule.onNode(hasSetTextAction()))
        rule.onNode(hasSetTextAction()).performClick()
        rule.runOnIdle { modal.windowInsetsController?.show(WindowInsets.Type.ime()) }
        awaitModalKeyboard(modal, visible = true)
        design.capture(FOLDER, "edit-host-keyboard$suffix", "533:2369")
        // At 320x700 the keyboard pushes Unpair under the action bar; it must stay reachable by scrolling.
        rule.onNodeWithText("Unpair host").performScrollTo().assertIsDisplayed()
        Espresso.pressBack()
        awaitModalKeyboard(modal, visible = false)
        rule.onNodeWithText("Unpair host").performScrollTo().performClick()
        awaitText("Unpair host?")
        design.capture(FOLDER, "edit-host-unpair$suffix", "671:5620")
        relaunch()

        // No conversation row draws a pen (#1563): Edit channel opens from the thread's More actions, Edit (#1561).
        openFirst(TREE_CHANNEL_ROW_TEST_TAG)
        design.openHeaderMenu()
        design.capture(FOLDER, "thread-menu$suffix", "none")
        rule.onNodeWithText("Edit").performClick()
        awaitText("Channel name:")
        val editModal = awaitModalFocus(rule.onNodeWithText("Edit channel"))
        openChannelFormKeyboard(editModal)
        // At 320x700 the keyboard pushes Mute and Archive channel below the window; they must stay reachable.
        rule.onNodeWithText("Archive channel").performScrollTo().assertIsDisplayed()
        captureChannelForm(editModal, "Edit channel", "edit-channel$suffix", "671:5415")
        relaunch()

        // Rename and Save as channel are on an unpromoted conversation's menu only; a channel's opens Edit (#1561).
        openFirst(TREE_CHAT_ROW_TEST_TAG)
        design.openHeaderMenu()
        rule.onNodeWithText("Rename").performClick()
        awaitText("Name")
        design.capture(FOLDER, "rename$suffix", "671:5664")
        relaunch()

        openFirst(TREE_CHAT_ROW_TEST_TAG)
        design.openHeaderMenu()
        rule.onNodeWithText("Save as channel…").performClick()
        awaitText("Save as channel")
        awaitText("Channel name:")
        val saveModal = awaitModalFocus(rule.onNodeWithText("Save as channel"))
        openChannelFormKeyboard(saveModal)
        captureChannelForm(saveModal, "Save as channel", "save-as-channel$suffix", "671:5718")
        relaunch()

        openFirst(TREE_CHANNEL_ROW_TEST_TAG)
        design.openHeaderMenu()
        rule.onNodeWithText("Channel info").performClick()
        awaitText("About")
        design.capture(FOLDER, "channel-info$suffix", "668:5355")
        val thread = checkNotNull(design.inputs.thread.value)
        val originalName = thread.state.value.displayName
        val fake = GlobalContext.get().get<FakeConversationRepository>()
        // Only Default Delete uses the frame's name; earlier modal captures keep their existing fixture.
        runBlocking { fake.rename(thread.state.value.conversationId, "kitchenclaw refactor") }
        try {
            awaitText("kitchenclaw refactor")
            // Delete sits below the sheet's fold.
            rule.onNodeWithText("Delete").performScrollTo().performClick()
            awaitText("Delete conversation?")
            rule.onNodeWithText("About").assertDoesNotExist()
            design.capture(FOLDER, "delete-confirmation$suffix", "673:3665")
            assertDeleteGeometry(compact = suffix.isNotEmpty())
            // Real dialog-window dismissal: none of these routes reopens the sheet or deletes the channel.
            rule.onNodeWithText("Cancel").performClick()
            assertDeleteDismissed()
            reopenDelete()
            shell("input keyevent KEYCODE_BACK")
            assertDeleteDismissed()
            reopenDelete()
            shell("input tap 8 100")
            assertDeleteDismissed()
            for (left in listOf(true, false)) {
                reopenDelete()
                val surface = deleteSurfaceBounds()
                val margin = 12f * rule.density.density
                val x = if (left) surface.left - margin else surface.right + margin
                shell("input tap ${x.toInt()} ${surface.center.y.toInt()}")
                assertDeleteDismissed()
            }
            assertEquals("kitchenclaw refactor", thread.state.value.displayName)
        } finally {
            runBlocking { fake.rename(thread.state.value.conversationId, originalName) }
        }

        hostStates {
            relaunch()
            awaitText("Update Pyrycode to use this host.")
            // 672:3493 draws every folder collapsed except Pyry's Channels.
            val tree = rule.onNode(hasScrollToNodeAction())
            HOST_FOLDS_COLLAPSED.forEach { fold ->
                tree.performScrollToNode(hasContentDescription("Collapse $fold"))
                rule.onNodeWithContentDescription("Collapse $fold").performClick()
                rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Expand $fold")).fetchSemanticsNodes().isNotEmpty() }
            }
            tree.performScrollToIndex(0)
            design.capture(FOLDER, "host-rows$suffix", "672:3493")
        }
    }

    private fun reopenDelete() {
        design.openHeaderMenu()
        rule.onNodeWithText("Channel info").performClick()
        awaitText("About")
        rule.onNodeWithText("Delete").performScrollTo().performClick()
        awaitText("Delete conversation?")
        awaitModalFocus(rule.onNodeWithText("Delete conversation?"))
    }

    private fun assertDeleteDismissed() {
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Delete conversation?")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("About").assertDoesNotExist()
        assertTrue(!checkNotNull(design.inputs.thread.value).state.value.deleteConfirmVisible)
    }

    private fun deleteSurfaceBounds(): Rect {
        val node = rule.onNodeWithTag("delete-dialog-surface").fetchSemanticsNode()
        val location = IntArray(2)
        (checkNotNull(node.root) as ViewRootForTest).view.getLocationOnScreen(location)
        return node.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
    }

    private fun assertDeleteGeometry(compact: Boolean) {
        val surface = deleteSurfaceBounds()
        val body = rule.onNodeWithText("This permanently deletes", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(!layouts.single().hasVisualOverflow)
        for (label in listOf("Cancel", "Delete")) {
            val action = rule.onNodeWithText(label).assertIsDisplayed()
            val actionLayouts = mutableListOf<TextLayoutResult>()
            action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(actionLayouts) }
            val layout = actionLayouts.single()
            assertTrue("$label lineRight=${layout.getLineRight(0)} size=${layout.size}", layout.getLineRight(0) <= layout.size.width + 1f)
            assertTrue(layout.getLineLeft(0) >= -1f)
            assertTrue(!layout.isLineEllipsized(0))
            assertTrue(!layout.didOverflowHeight)
            assertEquals(1, layout.lineCount)
            assertTrue(action.fetchSemanticsNode().touchBoundsInRoot.height >= 48f)
        }
        if (compact) {
            assertEquals(
                1.5f,
                layouts
                    .single()
                    .layoutInput.density.fontScale,
                0f,
            )
            assertTrue(surface.width <= 320f)
            assertTrue(surface.height > 220f && surface.height < 700f)
        } else {
            assertEquals(316f, surface.width, 2f)
            assertEquals(220f, surface.height, 2f)
            assertEquals(48f, surface.left, 2f)
            // Dialog windows use screen coordinates; remove the Activity's effective real or synthetic inset.
            val topInset = design.insets().getInsets(WindowInsetsCompat.Type.statusBars()).top
            assertEquals(312f, surface.top - topInset, 2f)
            assertEquals(3, layouts.single().lineCount)
        }
    }

    private fun openFirst(rowTag: String) {
        rule
            .onAllNodesWithTag(rowTag)
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitUntil(5_000) { design.inputs.thread.value != null }
    }

    /** Each surface starts from a fresh channel list, so one surface's dismissal path cannot steer the next. */
    private fun relaunch() {
        design.launch()
        awaitText("Channels")
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
        count: Int = 1,
    ) {
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring)).fetchSemanticsNodes().size >= count }
        rule.onAllNodes(hasText(text, substring)).onFirst().assertIsDisplayed()
    }

    /**
     * The modal's own dialog window, once it holds focus. [DesignCapture.openKeyboard] waits on the activity
     * window, which a modal's dialog keeps unfocused, so the modal's keyboard is driven here.
     */
    private fun awaitModalFocus(node: SemanticsNodeInteraction): View {
        val view = (checkNotNull(node.fetchSemanticsNode().root) as ViewRootForTest).view
        rule.waitUntil(10_000) {
            val focused = rule.runOnIdle { view.hasWindowFocus() }
            // A system crash dialog can steal window focus on the emulator, as DesignCapture.openKeyboard notes.
            if (!focused) shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            focused
        }
        return view
    }

    private fun awaitModalKeyboard(
        modal: View,
        visible: Boolean,
    ) {
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(modal)
                insets != null &&
                    insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0) == visible
            }
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        rule.waitForIdle()
    }

    private fun assertModalKeyboardClosed(modal: View) {
        rule.runOnIdle {
            assertTrue("the form's dialog window keeps focus", modal.hasWindowFocus())
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(modal))
            assertTrue("keyboard-closed capture requires hidden dialog IME", !insets.isVisible(WindowInsetsCompat.Type.ime()))
            assertEquals(
                "keyboard-closed capture requires zero dialog IME inset",
                0,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
        }
    }

    private fun openChannelFormKeyboard(modal: View) {
        rule.runOnIdle {
            assertTrue("channel form must own a dialog window", modal.parent is DialogWindowProvider)
        }
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performClick()
        rule.runOnIdle { modal.windowInsetsController?.show(WindowInsets.Type.ime()) }
        awaitModalKeyboard(modal, visible = true)
    }

    private fun captureChannelForm(
        modal: View,
        title: String,
        name: String,
        figmaNode: String,
    ) {
        // Send Back to the focused dialog; Espresso can instead select the unfocused Activity root.
        shell("input keyevent KEYCODE_BACK")
        awaitModalKeyboard(modal, visible = false)
        rule.onNodeWithText(title).assertIsDisplayed()
        assertEquals(modal, (checkNotNull(rule.onNodeWithText(title).fetchSemanticsNode().root) as ViewRootForTest).view)
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
        assertModalKeyboardClosed(modal)
        design.capture(FOLDER, name, figmaNode)
        // Activity insets in capture() cannot establish a separate dialog's IME state.
        val dialogInsets = rule.runOnIdle { checkNotNull(ViewCompat.getRootWindowInsets(modal)) }
        File(
            checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "design-1220/$FOLDER/$name.txt",
        ).appendText(
            "dialogWindowFocused=true keyboardOpenObserved=true backKeptForm=true " +
                "dialogImeVisible=${dialogInsets.isVisible(WindowInsetsCompat.Type.ime())} " +
                "dialogImePx=${dialogInsets.getInsets(WindowInsetsCompat.Type.ime())}\n",
        )
        assertModalKeyboardClosed(modal)
    }

    /**
     * Taps [node] through the device's input, as a finger would, so the capture shows the state a real tap leaves.
     * `input tap` returns before the app sees the event, so wait for the tab to report selected, then for Compose
     * idle, which outlasts the press ripple's frames (#1487 measured it fading out by about 800 ms).
     */
    private fun tap(node: SemanticsNodeInteraction) {
        val target = node.fetchSemanticsNode()
        val center = target.boundsInWindow.center
        shell("input tap ${center.x.toInt()} ${center.y.toInt()}")
        rule.waitUntil(5_000) {
            rule
                .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .fetchSemanticsNodes()
                .any { it.id == target.id }
        }
        rule.waitForIdle()
    }

    /**
     * At 320x700 and 150 % the labels wrap in their compact column; a word too wide for it breaks inside, as
     * "address" over a lone ":" (#1489). Device fonts only: Robolectric's metrics do not reproduce the break.
     */
    private fun assertIdentityLabelsWrapOnlyBetweenWords() {
        listOf("Server identity:", "Relay address:").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            val layout = layouts.single()
            (0 until layout.lineCount - 1).forEach { line ->
                assertTrue("$label breaks inside a word after line $line", label[layout.getLineEnd(line) - 1].isWhitespace())
            }
        }
    }

    /**
     * The retired workspace product leaves no grouping, label or control behind on the list or in Settings. A
     * returning workspace row would show only its folder name, so its controls' descriptions are checked too.
     */
    private fun assertNoWorkspaceText() {
        rule.onAllNodes(hasText("workspace", substring = true, ignoreCase = true)).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("workspace", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    /**
     * Archives the demo host's first [ARCHIVED_CHANNELS] channels for [block], which receives that count, and
     * restores them after, so Archive's Channels tab has rows like the frame and later surfaces keep the full list.
     */
    private fun archiveChannels(block: (Int) -> Unit) {
        val fake = GlobalContext.get().get<FakeConversationRepository>()
        val ids =
            runBlocking {
                fake
                    .observeConversations(ConversationFilter.Channels)
                    .first()
                    .take(ARCHIVED_CHANNELS)
                    .map { it.id }
            }
        try {
            runBlocking { ids.forEach { fake.archive(it) } }
            block(ids.size)
        } finally {
            runBlocking { ids.forEach { fake.unarchive(it) } }
        }
    }

    /** Unarchives the demo's archived discussions for [block] and archives them again after, so Discussions is empty. */
    private fun withoutArchivedDiscussions(block: () -> Unit) {
        val fake = GlobalContext.get().get<FakeConversationRepository>()
        val ids =
            runBlocking {
                fake
                    .observeConversations(ConversationFilter.Archived)
                    .first()
                    .filterNot { it.isPromoted }
                    .map { it.id }
            }
        try {
            runBlocking { ids.forEach { fake.unarchive(it) } }
            block()
        } finally {
            runBlocking { ids.forEach { fake.archive(it) } }
        }
    }

    /**
     * Swaps the harness's one connected demo host for the three hosts `672:3493` draws for [block]: the demo
     * rows on a disconnected host, and two row-less hosts whose relay refused the pairing or this app build.
     * The harness's own source is restored afterwards, as the Edit host walk restores the store.
     */
    private fun hostStates(block: () -> Unit) {
        val koin = GlobalContext.get()
        val previous = koin.get<HostConversationSource>()
        val fake = koin.get<FakeConversationRepository>()

        fun host(
            serverId: String,
            name: String,
            repository: ConversationRepository?,
            relay: RelayLinkStatus,
        ) = HostConversationConnection(
            serverId,
            name,
            MutableStateFlow(repository),
            MutableStateFlow(ConnectionStatus(relay, PyrycodeLinkStatus.Down)),
        )
        val source =
            HostConversationSource(
                MutableStateFlow(
                    listOf(
                        host(HostConversationSource.DEMO_SERVER_ID, "Pyry", fake, RelayLinkStatus.Offline),
                        host("second-brain", "MB Second brain", null, RelayLinkStatus.PairingRejected),
                        host("game-dev", "MB Game dev", null, RelayLinkStatus.UpdateRequired(minClientVersion = null)),
                    ),
                ),
                { if (it == HostConversationSource.DEMO_SERVER_ID) fake else null },
                viewing = koin.get(),
            )
        loadKoinModules(module { single { source } })
        try {
            block()
        } finally {
            loadKoinModules(module { single { previous } })
            source.dispose()
        }
    }

    private companion object {
        const val FOLDER = "list"
        const val ARCHIVED_CHANNELS = 3
        const val FRAME_NAME = "Release notes"
        const val FRAME_PROMPT = "Summarise each merged pull request in one plain sentence for the release notes."
        val HOST_FOLDS_COLLAPSED =
            listOf(
                "Chats on Pyry",
                "Channels on MB Second brain",
                "Chats on MB Second brain",
                "Channels on MB Game dev",
                "Chats on MB Game dev",
            )
        val DEMO_HOST = PairedServerEntry(PairedServer("demo", "unused", "wss://demo.invalid", "unused"), "Demo")
    }
}
