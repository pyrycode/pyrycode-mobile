package de.pyryco.mobile

import android.Manifest
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real CameraX and activity lifecycle are required to exercise the scanner's idle boundary. */
@RunWith(AndroidJUnit4::class)
class ScannerPasteTransitionDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun listScannerPaste_beforeAndAfterCameraInitialization_opensCodeForm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermission()
        val store = GlobalContext.get().get<PairedServerCollectionStore>()
        val serverId = "scanner-transition-1637"
        runBlocking {
            store.save(
                PairedServer(
                    serverId = serverId,
                    token = "test-only-token",
                    relayUrl = "wss://example.invalid/v1/client",
                    serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                ),
            )
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        // Reset CameraX so this remains a cold-start test even when another device class used it.
        ProcessCameraProvider
            .getInstance(context)
            .get(30, TimeUnit.SECONDS)
            .shutdownAsync()
            .get(30, TimeUnit.SECONDS)
        val cameraExecutor = HeldCameraExecutor()
        ProcessCameraProvider.configureInstance(
            CameraXConfig.Builder
                .fromConfig(Camera2Config.defaultConfig())
                .setCameraExecutor(cameraExecutor)
                .build(),
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val safetyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.waitUntil(15_000) {
                    rule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithContentDescription(context.getString(R.string.cd_pair_another_host)).performClick()
                rule.waitUntil(15_000) {
                    var mounted = false
                    scenario.onActivity { mounted = findPreview(it.window.decorView) != null }
                    mounted
                }
                assertFalse("provider must still be initializing", providerFuture.isDone)
                scenario.onActivity {
                    Log.i(
                        "ScannerTransition1637",
                        "phase=scanner lifecycle=${it.lifecycle.currentState} focus=${it.hasWindowFocus()} cameraInit=pending",
                    )
                }
                val safetyReleased = AtomicBoolean(false)
                // Cleanup fuse for the broken implementation: passing must happen while initialization is held.
                val safety =
                    safetyScope.launch {
                        delay(30_000)
                        safetyReleased.set(true)
                        cameraExecutor.release()
                    }
                try {
                    rule.onNode(hasText("Paste the pairing code instead", substring = true) and hasClickAction()).performClick()
                    rule.onNodeWithText("Pairing code").assertIsDisplayed()
                    assertFalse("scanner exit waited for camera initialization", safetyReleased.get())
                    assertFalse("code form must open before provider initialization", providerFuture.isDone)
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    Log.i("ScannerTransition1637", "phase=code-form lifecycle=RESUMED cameraInit=pending")
                } finally {
                    safety.cancel()
                    cameraExecutor.release()
                }
                providerFuture.get(30, TimeUnit.SECONDS)
                rule.onNodeWithText("Cancel").performClick()
                rule.onNodeWithContentDescription("Back").performClick()
                repeat(5) { iteration ->
                    rule.waitUntil(15_000) {
                        rule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
                    }
                    rule.onNodeWithContentDescription(context.getString(R.string.cd_pair_another_host)).performClick()
                    rule.waitUntil(15_000) {
                        var streaming = false
                        scenario.onActivity {
                            streaming = findPreview(it.window.decorView)?.previewStreamState?.value == PreviewView.StreamState.STREAMING
                        }
                        streaming
                    }
                    scenario.onActivity {
                        Log.i(
                            "ScannerTransition1637",
                            "phase=scanner iteration=$iteration lifecycle=${it.lifecycle.currentState} " +
                                "focus=${it.hasWindowFocus()} camera=${findPreview(it.window.decorView)?.previewStreamState?.value}",
                        )
                    }
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    val paste = hasText("Paste the pairing code instead", substring = true) and hasClickAction()
                    rule.onNode(paste).performClick()
                    rule.onNodeWithText("Pairing code").assertIsDisplayed()
                    rule.onNodeWithText("Host name").assertIsDisplayed()
                    rule.onNodeWithText("Cancel").performClick()
                    rule.onNodeWithContentDescription("Back").performClick()
                }
            }
        } finally {
            safetyScope.cancel()
            cameraExecutor.release()
            providerFuture.get(30, TimeUnit.SECONDS).shutdownAsync().get(30, TimeUnit.SECONDS)
            cameraExecutor.close()
            runBlocking { store.remove(serverId) }
        }
    }

    private class HeldCameraExecutor : Executor {
        private val delegate = Executors.newSingleThreadExecutor()
        private val pending = ArrayDeque<Runnable>()
        private var held = true

        @Synchronized
        override fun execute(command: Runnable) {
            if (held) pending.add(command) else delegate.execute(command)
        }

        @Synchronized
        fun release() {
            held = false
            while (pending.isNotEmpty()) delegate.execute(pending.removeFirst())
        }

        fun close() {
            delegate.shutdown()
            assertTrue("camera executor stopped", delegate.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPreview(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
