package de.pyryco.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScannerTransitionCleanupTest {
    @Test
    fun setupFailureStillRemovesTheSavedHost() {
        val events = mutableListOf<String>()
        val setupFailure = IllegalStateException("camera setup")

        val result =
            runCatching {
                ScannerTransitionCleanup().use { cleanup ->
                    cleanup.onClose { events.add("remove host") }
                    throw setupFailure
                }
            }

        assertSame(setupFailure, result.exceptionOrNull())
        assertEquals(listOf("remove host"), events)
    }

    @Test
    fun cameraShutdownFailureStillClosesExecutorAndRemovesHost() {
        val events = mutableListOf<String>()
        val shutdownFailure = IllegalStateException("camera shutdown")

        val result =
            runCatching {
                ScannerTransitionCleanup().use { cleanup ->
                    cleanup.onClose { events.add("remove host") }
                    cleanup.onClose { events.add("close executor") }
                    cleanup.onClose { throw shutdownFailure }
                }
            }

        assertSame(shutdownFailure, result.exceptionOrNull())
        assertEquals(listOf("close executor", "remove host"), events)
    }

    @Test
    fun transitionFailureRemainsPrimaryWhenCameraCleanupAlsoFails() {
        val events = mutableListOf<String>()
        val transitionFailure = AssertionError("scanner transition")
        val shutdownFailure = IllegalStateException("camera shutdown")

        val result =
            runCatching {
                ScannerTransitionCleanup().use { cleanup ->
                    cleanup.onClose { events.add("remove host") }
                    cleanup.onClose { throw shutdownFailure }
                    throw transitionFailure
                }
            }

        assertSame(transitionFailure, result.exceptionOrNull())
        assertEquals(listOf(shutdownFailure), transitionFailure.suppressed.toList())
        assertEquals(listOf("remove host"), events)
    }

    @Test
    fun multipleCleanupFailuresDoNotPreventHostRemovalOrHideEachOther() {
        val events = mutableListOf<String>()
        val shutdownFailure = IllegalStateException("camera shutdown")
        val executorFailure = IllegalStateException("executor close")

        val result =
            runCatching {
                ScannerTransitionCleanup().use { cleanup ->
                    cleanup.onClose { events.add("remove host") }
                    cleanup.onClose { throw executorFailure }
                    cleanup.onClose { throw shutdownFailure }
                }
            }

        assertSame(shutdownFailure, result.exceptionOrNull())
        assertEquals(listOf(executorFailure), shutdownFailure.suppressed.toList())
        assertEquals(listOf("remove host"), events)
    }
}
