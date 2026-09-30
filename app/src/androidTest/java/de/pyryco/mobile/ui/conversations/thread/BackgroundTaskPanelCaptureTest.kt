package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Device captures of the four Figma panel readings using synthetic, fixed task data. */
class BackgroundTaskPanelCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 0)
    val viewport =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val size = overrideOf(shell("wm size"))
                    val density = overrideOf(shell("wm density"))
                    shell("wm density 160")
                    shell("wm size 412x892")
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

    private var roster by mutableStateOf<BackgroundTaskRoster?>(POPULATED)
    private var open by mutableStateOf(true)

    @Test
    fun referenceReadingsAt412By892() {
        showPanel()
        rule.onNodeWithText("Running · 2").assertIsDisplayed()
        capture("emulator-populated-412x892.png")

        roster = CAPPED
        rule.onNodeWithText("Partial list (3 not shown)").assertIsDisplayed()
        rule.onNodeWithText("Running · 8 shown").assertIsDisplayed()
        capture("emulator-capped-412x892.png")

        roster = BackgroundTaskRoster(emptyList(), 0)
        rule.onNodeWithText("No background tasks").assertIsDisplayed()
        capture("emulator-empty-412x892.png")

        roster = null
        rule.onNodeWithText("No background-task report yet").assertIsDisplayed()
        capture("emulator-unreported-412x892.png")
    }

    @Test
    fun compactLargeTextKeepsScrolledContentAndBothCloseRoutesReachable() {
        shell("wm size 320x640")
        instrumentation.waitForIdleSync()
        roster = CAPPED
        showPanel(fontScale = 1.5f)
        assertWithinCompactWidth("Partial list (3 not shown)")
        assertWithinCompactWidth("Running · 8 shown")
        rule.onNodeWithText("python3 scripts/replay_capture.py").performScrollTo()
        assertWithinCompactWidth("python3 scripts/replay_capture.py")
        rule.onNodeWithText("No change reported").performScrollTo().assertIsDisplayed()
        rule
            .onNode(hasText("Close") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        rule.onNodeWithText("Background tasks").assertDoesNotExist()

        open = true
        rule.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        rule.onNodeWithText("Background tasks").assertDoesNotExist()

        roster = null
        open = true
        rule
            .onNodeWithText("The daemon has not reported on this conversation since the app connected.")
            .performScrollTo()
            .assertIsDisplayed()
        roster = BackgroundTaskRoster(emptyList(), 0)
        rule
            .onNodeWithText("Claude has nothing running in the background for this conversation.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun assertWithinCompactWidth(text: String) {
        val bounds =
            rule
                .onNodeWithText(text, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("$text starts inside the compact viewport", bounds.left >= 0f)
        assertTrue("$text ends inside the compact viewport", bounds.right <= 320f)
    }

    private fun showPanel(fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    if (open) BackgroundTaskPanel(roster = roster, onDismiss = { open = false })
                }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        // The dialog's window animation can still be running after Compose becomes idle.
        SystemClock.sleep(600)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(412, image.width)
        assertEquals(892, image.height)
        val colors = (0 until image.height step 16).flatMap { y -> (0 until image.width step 16).map { x -> image.getPixel(x, y) } }
        val realPixels = colors.toSet().size > 10
        if (InstrumentationRegistry.getArguments().getString("requireRealSystemBars") == "true") {
            assertTrue("actual emulator capture must contain rendered content", realPixels)
        }
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "task-panel-1295/api-${Build.VERSION.SDK_INT}",
            ).apply { mkdirs() }
        File(output, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText(
            "api=${Build.VERSION.SDK_INT} sizeDp=412x892 density=1.0 fontScale=1.0 staticDark=true " +
                "nonblank=$realPixels design=568:877,568:932,568:981,568:997,563:1054 date=2026-09-30\n",
        )
        image.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String): String =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"

    private companion object {
        fun task(
            id: String,
            type: String,
            description: String,
            progress: BackgroundTaskProgress? = null,
            update: BackgroundTaskUpdate? = null,
            finish: BackgroundTaskUpdate? = null,
            cut: List<String>? = null,
        ) = BackgroundTask(id, "toolu_$id", type, description, cut, update, finish, finish != null, progress)

        val POPULATED =
            BackgroundTaskRoster(
                listOf(
                    task(
                        "t1",
                        "local_bash",
                        "go test ./internal/relay/... -run TestReconnect -count=20 -race",
                        progress = BackgroundTaskProgress("Running go test with the race detector", "", "Bash", 18_000, 4, 161_000, null),
                        update = BackgroundTaskUpdate("""{"output_tail":"--- PASS: TestReconnect/drop_mid_frame (0.84s)"}""", "", "", null),
                    ),
                    task(
                        "t2",
                        "local_agent",
                        "Review the relay reconnect diff for data races",
                        progress = BackgroundTaskProgress("Reading internal/relay/conn.go", "", "Read", 42_000, 7, 65_000, null),
                    ),
                    task(
                        "t3",
                        "local_bash",
                        "npm run build",
                        finish = BackgroundTaskUpdate("", "completed", "Build finished in 38s with no warnings.", null),
                    ),
                    task(
                        "t4",
                        "local_bash",
                        "docker compose up relay",
                        finish = BackgroundTaskUpdate("", "failed", "Exited with code 1: port 8443 is already in use.", null),
                    ),
                ),
                0,
            )

        val CAPPED =
            BackgroundTaskRoster(
                listOf(
                    task(
                        "t1",
                        "local_bash",
                        "for f in \$(git ls-files \"internal/**/*.go\"); do go vet \"\$f\" && staticcheck -checks all \"\$f\" >> /tmp/lint.txt; done; sort -u /tmp/lint.txt | head -n 400 > /tmp/li",
                        cut = listOf("description"),
                    ),
                    task(
                        "t2",
                        "local_bash",
                        "python3 scripts/replay_capture.py",
                        update = BackgroundTaskUpdate("""{"output_tail":"replayed 214 frames, 3 roste""", "", "", listOf("patch")),
                    ),
                    task(
                        "t3",
                        "local_agent",
                        "Summarise the open tickets that mention the relay",
                        update = BackgroundTaskUpdate("", "", "", null),
                    ),
                    task("t4", "local_bash", "checking another package"),
                    task("t5", "local_agent", "Reviewing the result"),
                    task("t6", "local_bash", "waiting for tests"),
                    task("t7", "local_agent", "Summarising open changes"),
                    task("t8", "local_bash", "finishing verification"),
                ),
                3,
            )
    }
}
