package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/**
 * Device evidence for the inline permission request (#1306): static-fixture captures from the activity view,
 * and the activity-surface hardening that replaced the dialog window's own. Device-only: window flags and real
 * `MotionEvent` dispatch.
 */
class ThreadPermissionCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 0)
    val viewport =
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    val size = overrideOf(shell("wm size"))
                    val density = overrideOf(shell("wm density"))
                    shell("wm density 160")
                    shell(if (description.methodName.contains("Compact")) "wm size 320x700" else "wm size 412x892")
                    try {
                        instrumentation.waitForIdleSync()
                        base.evaluate()
                    } finally {
                        shell("wm size $size")
                        shell("wm density $density")
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val allowOnce = "Allow once"
    private val rejectOnce = "Reject once"

    private var modal by mutableStateOf<ModalUiState>(ModalUiState.Hidden)
    private var accepted by mutableStateOf(false)
    private var armed by mutableStateOf<String?>(null)
    private val answers = mutableListOf<String>()
    private val grantToggles = mutableListOf<Boolean>()

    private fun fixture(): ModalUiState.Open =
        ModalUiState.Open(
            modalId = "capture",
            modalClass = "permission",
            title = "Permission required",
            prompt = "Claude wants to run npm test in the project directory.",
            options =
                listOf(
                    ModalOption("allow_once", allowOnce),
                    ModalOption("allow_always", "Allow always"),
                    ModalOption("reject_once", rejectOnce),
                    ModalOption("reject_always", "Reject always"),
                ),
            defaultOptionId = "reject_once",
            conversationId = "capture",
            context = ModalContext(reason = "Bash(npm test) is on the ask list", reasonType = "rule"),
            alwaysAllowRules = listOf("Bash(npm test)"),
        )

    private fun show(fontScale: Float = 1f) {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    ThreadScreen(
                        state = ThreadUiState("capture", "Client planning"),
                        onBack = {},
                        onSendMessage = {},
                        draft = "My message",
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        modalState = modal,
                        armedOptionId = armed,
                        // A stand-in for the ViewModel's rule: the default answers, a non-default arms first.
                        onModalOption = { _, id ->
                            if (id == "reject_once" || armed == id) answers += id else armed = id
                        },
                        alwaysAllowAccepted = accepted,
                        onAlwaysAllowChanged = { _, value ->
                            grantToggles += value
                            accepted = value
                        },
                    )
                }
            }
        }
    }

    @Test
    fun darkInlinePermissionAtFigmaViewport() {
        modal = fixture()
        show()
        val list = rule.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText(rejectOnce))
        rule.onNodeWithText(rejectOnce).assertIsDisplayed()
        capture("permission-unchecked-412x892.png", 412, 892, 1f)

        val offer = instrumentation.targetContext.getString(R.string.modal_always_allow_label)
        list.performScrollToNode(hasText(offer))
        rule.onNodeWithText(offer).assertIsOff().performTouch()
        rule.onNodeWithText(offer).assertIsOn()
        list.performScrollToNode(hasText(rejectOnce))
        capture("permission-checked-412x892.png", 412, 892, 1f)

        rule.onNodeWithText(allowOnce).performTouch()
        rule
            .onNodeWithText(allowOnce)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.modal_armed_option_desc)))
        capture("permission-armed-412x892.png", 412, 892, 1f)
        assertTrue("arming never answers", answers.isEmpty())
    }

    @Test
    fun darkCompactInlinePermissionAtLargeText() {
        modal = fixture()
        show(fontScale = 1.5f)
        val list = rule.onNode(hasScrollToNodeAction())
        listOf(fixture().prompt, allowOnce, "Allow always", rejectOnce, "Reject always", string(R.string.modal_cancel)).forEach {
            list.performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        capture("permission-compact-320x700.png", 320, 700, 1.5f)
    }

    @Test
    fun inlineRequestProtectsCaptureRejectsObscuredTouchesAndRestoresWindowPolicy() {
        show()
        rule.runOnIdle { assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        rule.runOnIdle { modal = fixture() }
        val list = rule.onNode(hasScrollToNodeAction())
        val offer = instrumentation.targetContext.getString(R.string.modal_always_allow_label)
        list.performScrollToNode(hasText(offer))
        rule.runOnIdle {
            val window = rule.activity.window
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(window.decorView.filterTouchesWhenObscured)
        }
        // An overlay-obscured tap reaches neither a decision nor the grant; an unobscured one on the same spot does.
        tapObscured(rule.onNodeWithText(rejectOnce).center())
        tapObscured(rule.onNodeWithText(offer).center())
        rule.runOnIdle {
            assertTrue("an obscured tap must not answer", answers.isEmpty())
            assertTrue("an obscured tap must not toggle the grant", grantToggles.isEmpty())
        }
        rule.onNodeWithText(offer).performTouch()
        rule.onNodeWithText(rejectOnce).performTouch()
        rule.runOnIdle {
            assertEquals(listOf(true), grantToggles)
            assertEquals(listOf("reject_once"), answers)
        }

        rule.runOnIdle { modal = ModalUiState.Hidden }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertTrue(!rule.activity.window.decorView.filterTouchesWhenObscured)
        }
        rule.onNodeWithTag("permission-request-card").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.center(): Offset = fetchSemanticsNode().boundsInRoot.center

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTouch(): androidx.compose.ui.test.SemanticsNodeInteraction {
        val point = center()
        rule.runOnIdle { dispatchTap(point, obscured = false) }
        rule.waitForIdle()
        return this
    }

    private fun tapObscured(point: Offset) {
        rule.runOnIdle { assertTrue("the protected surface rejects the overlay event", !dispatchTap(point, obscured = true)) }
        rule.waitForIdle()
    }

    /** A real pointer tap through the activity's decor view, optionally flagged as delivered under an overlay. */
    private fun dispatchTap(
        point: Offset,
        obscured: Boolean,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = point.x
                    y = point.y
                    pressure = 1f
                    size = 1f
                },
            )
        val flags = if (obscured) MotionEvent.FLAG_WINDOW_IS_OBSCURED else 0
        return listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)
            .map { action ->
                val event =
                    MotionEvent.obtain(
                        now,
                        now,
                        action,
                        1,
                        properties,
                        coordinates,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        flags,
                    )
                rule.activity.window.decorView
                    .dispatchTouchEvent(event)
                    .also { event.recycle() }
            }.any { it }
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        SystemClock.sleep(300)
        val view = rule.activity.window.decorView
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        rule.runOnIdle {
            assertTrue(
                "the request stays capture protected",
                rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
            view.draw(canvas)
        }
        val samples = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        assertTrue("real emulator capture must contain rendered content", samples.toSet().size > 10)
        val output =
            File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "permission-1306")
                .apply { mkdirs() }
        File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, name.replace(".png", ".txt"))
            .writeText("staticFixture=true width=$width height=$height fontScale=$fontScale secure=true\n")
        image.recycle()
    }

    private fun string(id: Int): String = instrumentation.targetContext.getString(id)

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
