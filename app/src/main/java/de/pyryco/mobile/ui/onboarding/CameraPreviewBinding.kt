package de.pyryco.mobile.ui.onboarding

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Owns a scanner's provider binding; listener and disposal run on the main executor. */
internal class CameraPreviewBinding<T : Any>(
    private val future: ListenableFuture<T>,
    executor: Executor,
    private val bind: (T) -> Unit,
    private val unbind: (T) -> Unit,
    private val onError: () -> Unit,
) {
    private var disposed = false
    private var initializedProvider: T? = null

    init {
        future.addListener(
            ready@{
                if (disposed) return@ready
                try {
                    val provider = future.get()
                    initializedProvider = provider
                    bind(provider)
                } catch (_: Exception) {
                    onError()
                }
            },
            executor,
        )
    }

    fun dispose() {
        disposed = true
        // Never wait for initialization on main. The provider future is shared by CameraX;
        // leave it running and ignore its listener if this scanner has already left.
        initializedProvider?.let { provider -> runCatching { unbind(provider) } }
        initializedProvider = null
    }
}
