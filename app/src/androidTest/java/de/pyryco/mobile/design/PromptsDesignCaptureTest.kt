package de.pyryco.mobile.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.conversations.thread.QuestionModalEvent
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * Questions and permissions in the assembled app at the Figma board's viewports (design-1220/prompts, #1433).
 *
 * Both prompts set `FLAG_SECURE` on the activity window, which blacks out [DesignCapture.capture]'s
 * `UiAutomation` screenshot, so [secureCapture] draws the decor view instead, as #1305 and #1306 did.
 */
@RunWith(AndroidJUnit4::class)
class PromptsDesignCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    private val inputs get() = design.inputs
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun questionFrames() {
        val chat = openWithQuestion()
        secureCapture("question-unanswered", "636:3279")

        answer(chat)
        rule.onNodeWithText("Continue").assertIsDisplayed()
        secureCapture("question-answered", "636:3540")
    }

    /** 412x792 so the test IME's 240 px leaves the frame's 552 px of app above the keyboard. */
    @Viewport("412x792")
    @Test
    fun questionKeyboardFrame() {
        val chat = openWithQuestion()
        answer(chat)
        scrollTo(hasTestTag("question_other_1"))
        design.openKeyboard(rule.onNodeWithTag("question_other_1"))
        rule.onNodeWithTag("question_other_1").assertIsDisplayed()
        // The viewport leaves 552 px only above the 240 px test keyboard; one run showed a 332 px keyboard instead.
        assertEquals("the test IME's keyboard is open", 240, design.insets().getInsets(WindowInsetsCompat.Type.ime()).bottom)
        secureCapture("question-keyboard", "636:3803")
        assertAboveComposer(rule.onNodeWithTag("question_other_1"), rule.onNodeWithText("Cancel"), rule.onNodeWithText("Continue"))
        reachable("Cancel", "Continue")
    }

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun questionCompactFrame() {
        val chat = openWithQuestion()
        answer(chat)
        reachable("Claude has questions", "Which language should you learn next?", "Which targets matter?", "Cancel", "Continue")
        scrollTo(hasText("Continue"))
        secureCapture("question-compact", "636:4066")
    }

    @Test fun permissionFrames() {
        open(CLIENT)
        show(permission("safe-default", grant = false))
        rule.onNodeWithText("Reject once").assertIsDisplayed()
        secureCapture("permission-safe-default", "639:2242")

        show(permission("grant", grant = true))
        rule.onNodeWithText(GRANT_LABEL).assertIsDisplayed()
        secureCapture("permission-grant-offered", "639:2451")

        rule.onNodeWithText(GRANT_LABEL).performClick()
        rule.waitUntil(5_000) { thread().alwaysAllowAccepted.value }
        secureCapture("permission-grant-selected", "639:2666")

        rule.onNodeWithText("Allow once").performClick()
        rule.waitUntil(5_000) { thread().armedOptionId.value == "allow_once" }
        secureCapture("permission-armed", "639:2882")

        show(trust())
        rule.onNodeWithText("Trust folder").assertIsDisplayed()
        secureCapture("trust-safe-default", "639:3099")
    }

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun permissionCompactFrame() {
        open(CLIENT)
        show(permission("compact", grant = true))
        scrollTo(hasText(GRANT_LABEL))
        rule.onNodeWithText(GRANT_LABEL).performClick()
        scrollTo(hasText("Allow once"))
        rule.onNodeWithText("Allow once").performClick()
        rule.waitUntil(5_000) { thread().armedOptionId.value == "allow_once" }
        reachable("Permission required", "Allow Claude to read this project?", GRANT_LABEL, "Allow once", "Reject once", "Cancel")
        scrollTo(hasText("Cancel"))
        secureCapture("permission-compact", "639:3308")
    }

    /** `640:2437`: each prompt waits in its own chat while the list and another chat stay usable. */
    @Test fun promptsWaitInTheirOwnChats() {
        val ids = createChannels()
        inputs.questionBatch.value = batch(ids.getValue(CLIENT))
        inputs.hostModal.value = HostModalState(listOf(permission("switch", grant = true).copy(conversationId = ids.getValue(KITCHEN))))

        open(CLIENT, ids)
        awaitQuestion()
        assertNoPermission()
        secureCapture("switch-question-chat", "636:3279")

        back()
        secureCapture("switch-list", "640:2440", expectSecure = false)

        open(OTHER, ids, draft = null)
        assertNoQuestion()
        assertNoPermission()
        // The composer is the only text field in a chat with no prompt. Open the keyboard first so closing it
        // is awaited, rather than racing the IME that text input would show (#1433 rework).
        val composer = rule.onNode(hasSetTextAction())
        design.openKeyboard(composer)
        composer.performTextInput("Release notes draft")
        rule.waitUntil(5_000) { thread().draft.value == "Release notes draft" }
        design.closeKeyboard()
        assertFalse(design.insets().isVisible(WindowInsetsCompat.Type.ime()))
        secureCapture("switch-other-chat", "640:2646", expectSecure = false)

        back()
        open(KITCHEN, ids)
        rule.waitUntil(5_000) { thread().currentModal.value is ModalUiState.Open }
        rule.onNodeWithTag("permission-request-card").assertIsDisplayed()
        assertNoQuestion()
        secureCapture("switch-permission-chat", "639:2451")

        // `640:2838` item 3: switching away clears the arm.
        rule.onNodeWithText("Allow once").performClick()
        rule.waitUntil(5_000) { thread().armedOptionId.value == "allow_once" }
        back()
        open(OTHER, ids, draft = null)
        assertEquals("the composer draft stays with its chat", "Release notes draft", thread().draft.value)

        back()
        open(KITCHEN, ids, draft = null)
        rule.waitUntil(5_000) { thread().currentModal.value is ModalUiState.Open }
        assertNull("switching away clears the arm", thread().armedOptionId.value)

        back()
        open(CLIENT, ids, draft = null)
        awaitQuestion()
        assertNoPermission()
        assertEquals("the composer draft stays with its chat", "My message", thread().draft.value)
    }

    /** The view model's own state, not a lazily composed node, decides that no batch reached this chat. */
    private fun assertNoQuestion() {
        rule.waitForIdle()
        assertNull(thread().questionModal.value)
    }

    private fun assertNoPermission() {
        rule.waitForIdle()
        assertFalse(thread().currentModal.value is ModalUiState.Open)
    }

    /** The batch reached this chat: its actions show at the stream's end. */
    private fun awaitQuestion() {
        rule.waitUntil(5_000) { thread().questionModal.value != null }
        rule.waitForIdle()
        rule.onNodeWithText("Continue").assertIsDisplayed()
    }

    private fun openWithQuestion(): ThreadViewModel {
        val ids = open(CLIENT)
        inputs.questionBatch.value = batch(ids.getValue(CLIENT))
        rule.waitUntil(5_000) { thread().questionModal.value != null }
        rule.waitForIdle()
        return thread()
    }

    /** The answers `636:3540` shows: Kotlin, then Kotlin and Other "Web"; toggles by tap, text through the event. */
    private fun answer(chat: ThreadViewModel) {
        for (tag in listOf("question_control_0_0_row", "question_control_1_0_row")) {
            scrollTo(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
        }
        instrumentation.runOnMainSync { chat.onQuestionEvent(QuestionModalEvent.OtherTextChanged(1, "Web")) }
        rule.waitUntil(5_000) {
            chat.questionModal.value
                ?.selections
                ?.getOrNull(1)
                ?.otherText == "Web"
        }
        rule.waitForIdle()
    }

    /** The fake repository outlives each test in the process, so the three empty channels are created once. */
    private fun createChannels(): Map<String, String> {
        val repository = GlobalContext.get().get<FakeConversationRepository>()
        runBlocking {
            for (name in listOf(OTHER, KITCHEN, CLIENT)) {
                if (name !in channels) channels[name] = repository.createChannel(name, null).id
            }
        }
        return channels.toMap()
    }

    /**
     * Launches on the channel list, opens [name] and sets the frames' footer: 84 % context and, unless [draft] is
     * null, the composer draft [draft]. Pass null to open a chat with whatever draft it already holds.
     */
    private fun open(
        name: String,
        existing: Map<String, String>? = null,
        draft: String? = "My message",
    ): Map<String, String> {
        val ids = existing ?: createChannels()
        inputs.contextUsage.value = ContextUsage(totalTokens = 168_000, maxTokens = 200_000, percentage = 84, asOf = null)
        if (design.scenario == null) {
            design.paired = true
            design.launch()
        }
        val before = inputs.thread.value
        rule.onNodeWithText(name).performScrollTo().performClick()
        rule.waitUntil(5_000) { inputs.thread.value.let { it != null && it !== before } }
        val chat = thread()
        if (draft != null) {
            instrumentation.runOnMainSync { chat.onDraftChange(draft) }
            rule.waitUntil(5_000) { chat.draft.value == draft }
        }
        rule.waitForIdle()
        return ids
    }

    private fun back() {
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
    }

    private fun show(prompt: ModalUiState.Open) {
        val conversation = thread().state.value.conversationId
        inputs.hostModal.value = HostModalState(listOf(prompt.copy(conversationId = conversation)))
        rule.waitUntil(5_000) { (thread().currentModal.value as? ModalUiState.Open)?.modalId == prompt.modalId }
        rule.waitForIdle()
    }

    private fun thread(): ThreadViewModel = checkNotNull(inputs.thread.value)

    private fun scrollTo(matcher: SemanticsMatcher) {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        rule.waitForIdle()
    }

    /**
     * #1484: at the focus scroll position, with no scroll of its own, each node is displayed and lies wholly inside the
     * message region, which ends where the composer starts.
     */
    private fun assertAboveComposer(vararg nodes: SemanticsNodeInteraction) {
        val stream = rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        for (node in nodes) {
            val bounds = node.assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$bounds lies inside the stream $stream", bounds.top >= stream.top && bounds.bottom <= stream.bottom)
        }
    }

    /** Fails unless each text can be scrolled to and displayed: the index's reachability check. */
    private fun reachable(vararg texts: String) {
        for (text in texts) {
            scrollTo(hasText(text))
            rule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    /**
     * Draws the decor view into `<additionalTestOutputDir>/design-1220/prompts/<name>.png`, cropped above an
     * open keyboard, with a `.txt` of the same fields [DesignCapture.capture] writes plus the secure flag.
     * Fails unless the window's `FLAG_SECURE` is [expectSecure], on a blank or black frame, and under
     * `requireRealSystemBars=true` unless the platform reported real bars to the decor view.
     */
    private fun secureCapture(
        name: String,
        figmaNode: String,
        expectSecure: Boolean = true,
    ) {
        rule.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "design-1220/$FOLDER")
                .apply { mkdirs() }
        val root = design.view.rootView
        val metrics = root.resources.displayMetrics

        // insets() substitutes synthetic bars when the platform reports none; the decor view's own insets do not.
        // Setting FLAG_SECURE relays out the window, so they can read empty for a moment after a prompt arrives.
        fun platformBars() = rule.runOnIdle { ViewCompat.getRootWindowInsets(root) }?.getInsets(WindowInsetsCompat.Type.systemBars())
        // Right after launch the navigation bar can also report a taller inset for over a second before it settles,
        // so wait until the real bars have held for 1.5 s.
        var steady: Insets? = null
        var since = 0L
        runCatching {
            rule.waitUntil(10_000) {
                val now = platformBars()?.takeIf { it.top > 0 && it.bottom > 0 }
                if (now != steady) {
                    steady = now
                    since = SystemClock.uptimeMillis()
                }
                steady != null && SystemClock.uptimeMillis() - since >= 1_500
            }
        }
        val real = platformBars()
        val syntheticBars = real == null || (real.top == 0 && real.bottom == 0)
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
            assertTrue("real system bars required for design evidence", real != null && real.top > 0 && real.bottom > 0)
        }
        var secure = false
        rule.runOnIdle {
            design.scenario?.onActivity {
                secure = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            }
        }
        val image = drawSettled(root)
        val bars = design.insets().getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = design.insets().getInsets(WindowInsetsCompat.Type.ime())
        val colors = (0 until image.height step 8).flatMap { y -> (0 until image.width step 8).map { x -> image.getPixel(x, y) } }
        assertTrue("capture must contain rendered content", colors.toSet().size > 10)
        assertTrue("capture must not be black", colors.any { (it and 0xFFFFFF) > 0x101010 })
        assertEquals("FLAG_SECURE on the window while capturing $name", expectSecure, secure)
        assertEquals("capture matches the window width", metrics.widthPixels, image.width)
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The keyboard frame's viewport assumes the harness's 240 px test IME; record which keyboard showed.
        val keyboard = if (ime.bottom > 0) " keyboard=${shellOutput("settings get secure default_input_method")}" else ""
        // #1501: what the platform renders each type size and line height as at this font scale (non-linear from API 34).
        val spPx =
            listOf(
                14f,
                16f,
                20f,
                22f,
                24f,
                28f,
            ).joinToString(",") { sp -> "$sp:${TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)}" }
        File(output, "$name.txt").writeText(
            "activity=MainActivity figma=$figmaNode sizePx=${metrics.widthPixels}x${metrics.heightPixels} " +
                "capturePx=${image.width}x${image.height} density=${metrics.density} " +
                "fontScale=${root.resources.configuration.fontScale} staticDark=true secure=$secure decorViewDraw=true " +
                "syntheticBars=$syntheticBars systemBarsPx=$bars imePx=$ime spPx=$spPx$keyboard api=${Build.VERSION.SDK_INT} device=${Build.MODEL}\n",
        )
        image.recycle()
    }

    /**
     * Draws the decor view above the keyboard until two draws 250 ms apart agree, so the relayout that follows
     * `FLAG_SECURE` has settled (#1433: a capture caught it 19 px off). A few changed samples, such as a caret
     * blink, still count as agreement. Fails if the frame has not settled within 5 s.
     */
    private fun drawSettled(root: View): Bitmap {
        fun draw(): Bitmap =
            rule.runOnIdle {
                val ime = checkNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.ime())
                Bitmap.createBitmap(root.width, root.height - ime.bottom, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.BLACK) }.also { canvas -> root.draw(canvas) }
                }
            }
        var previous = draw()
        repeat(20) {
            SystemClock.sleep(250)
            val next = draw()
            val settled =
                next.width == previous.width &&
                    next.height == previous.height &&
                    (0 until next.height step 4).sumOf { y ->
                        (0 until next.width step 4).count { x -> next.getPixel(x, y) != previous.getPixel(x, y) }
                    } < SETTLED_SAMPLE_TOLERANCE
            previous.recycle()
            if (settled) return next
            previous = next
        }
        previous.recycle()
        throw AssertionError("the frame did not settle within 5 s")
    }

    private fun shellOutput(command: String): String =
        instrumentation.uiAutomation
            .executeShellCommand(command)
            .let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() } }

    private fun batch(conversationId: String): QuestionBatch {
        val choices = listOf(QuestionOption("Kotlin", "The JVM language"), QuestionOption("Rust", "A systems language"))
        return QuestionBatch(
            conversationId = conversationId,
            questionBatchId = "design-questions",
            questions =
                listOf(
                    Question("Which language should you learn next?", "Language", choices, multiSelect = false),
                    Question("Which targets matter?", "Targets", choices, multiSelect = true),
                ),
        )
    }

    /** The frames' permission: Allow once, then the safe default Reject once; a session grant when [grant]. */
    private fun permission(
        id: String,
        grant: Boolean,
    ) = ModalUiState.Open(
        modalId = "design-permission-$id",
        modalClass = "permission",
        title = "Permission required",
        prompt = "Allow Claude to read this project?",
        options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
        defaultOptionId = "reject_once",
        context = ModalContext(reason = "This folder is outside the allowed paths.", blockedPath = "/projects/client"),
        alwaysAllowRules = if (grant) listOf("Read files in this project") else emptyList(),
    )

    private fun trust() =
        ModalUiState.Open(
            modalId = "design-trust",
            modalClass = "trust",
            title = "Trust this folder?",
            prompt = "Allow the agent to work in this folder?",
            options = listOf(ModalOption("trust", "Trust folder"), ModalOption("dont_trust", "Don't trust")),
            defaultOptionId = "dont_trust",
            context = ModalContext(reason = "This folder has not been trusted yet.", blockedPath = "/projects/client"),
        )

    private companion object {
        val channels = mutableMapOf<String, String>()
        const val FOLDER = "prompts"
        const val SETTLED_SAMPLE_TOLERANCE = 50
        const val CLIENT = "Client planning"
        const val KITCHEN = "kitchenclaw refactor"
        const val OTHER = "Release notes"
        const val GRANT_LABEL = "Don't ask again this session for:"
    }
}
