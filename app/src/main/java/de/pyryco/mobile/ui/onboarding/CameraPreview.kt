package de.pyryco.mobile.ui.onboarding

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Live CameraX preview that fills its bounds, with the [QrCodeAnalyzer] (#333) bound to a parallel
 * `ImageAnalysis` use case so a real scan surfaces a decode. The pairing scanner renders this
 * **behind** the locked atmosphere/reticle overlay (Figma `13:2`); the preview uses
 * [PreviewView.ImplementationMode.COMPATIBLE] (TextureView) so it composites in the Compose
 * hierarchy and honours the parent rounded-corner clip + over-drawn overlay rather than punching
 * through it.
 *
 * Not unit-tested — it needs a physical camera / [ProcessCameraProvider]; its collaborators (the
 * analyzer decode/debounce, the VM transitions) are covered in #333/#326.
 *
 * @param onQrDecoded fires at most once per composition (the analyzer's once-per-scan latch).
 * @param onCameraError fires on bind failure with a fixed, detail-free message (AC4).
 */
@Composable
fun CameraPreview(
    onQrDecoded: (String) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The bind listener and the long-lived analyzer both outlive a single recomposition; route
    // through the latest lambdas so neither invokes a stale capture.
    val currentOnQrDecoded by rememberUpdatedState(onQrDecoded)
    val currentOnCameraError by rememberUpdatedState(onCameraError)

    // Off-main executor for the analyzer — MlKitQrFrameDecoder.decode blocks on Tasks.await (#333).
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // Fresh analyzer per composition entry → resets the once-per-scan latch on each scanner entry.
    val analyzer = remember { QrCodeAnalyzer(onQrDecoded = { currentOnQrDecoded(it) }) }

    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    val preview =
                        Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                    val imageAnalysis =
                        ImageAnalysis
                            .Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .apply { setAnalyzer(analysisExecutor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                } catch (_: Exception) {
                    // Never interpolate the exception — a fixed message keeps device/stack detail
                    // out of the Error UI (AC5 / security review).
                    currentOnCameraError(CAMERA_BIND_ERROR_MESSAGE)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

private const val CAMERA_BIND_ERROR_MESSAGE =
    "Couldn't start the camera. Paste the pairing code instead."
