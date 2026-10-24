package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableGapIsolationTest {
    private val events = mutableListOf<String>()

    private fun fixture(
        restore: () -> Unit = { events += "restore" },
        peer: () -> Unit = { events += "peer" },
        probe: () -> Unit = { events += "probe" },
    ) = DurableGapIsolation(restore, peer, probe)

    @Test fun failureAfterSeverRestoresAndPreservesPrimary() {
        val primary = AssertionError("after sever")
        val thrown =
            assertThrows(AssertionError::class.java) {
                fixture().run {
                    sever { events += "sever" }
                    throw primary
                }
            }
        assertSame(primary, thrown)
        assertEquals(listOf("sever", "restore", "peer", "probe"), events)
    }

    @Test fun disconnectFailureStillRestoresAndCleans() {
        val primary = AssertionError("disconnect")
        assertSame(
            primary,
            assertThrows(AssertionError::class.java) {
                fixture().run {
                    sever {
                        events += "disconnect_attempt"
                        throw primary
                    }
                }
            },
        )
        assertEquals(listOf("disconnect_attempt", "restore", "peer", "probe"), events)
    }

    @Test fun offlineAndRestartFailuresRestoreBeforeCleanup() {
        listOf("offline_production", "daemon_restart").forEach { stage ->
            events.clear()
            val primary = AssertionError(stage)
            assertSame(
                primary,
                assertThrows(AssertionError::class.java) {
                    fixture().run {
                        sever { events += "sever" }
                        events += stage
                        throw primary
                    }
                },
            )
            assertEquals(listOf("sever", stage, "restore", "peer", "probe"), events)
        }
    }

    @Test fun catchUpFailureRestoresWithoutMaskingPrimary() {
        listOf(false, true).forEach { alreadyReconnected ->
            events.clear()
            val primary = AssertionError("catch-up")
            assertSame(
                primary,
                assertThrows(AssertionError::class.java) {
                    fixture().run {
                        sever { events += "sever" }
                        if (alreadyReconnected) events += "normal_restore"
                        throw primary
                    }
                },
            )
            assertEquals(
                listOf("sever") + (if (alreadyReconnected) listOf("normal_restore") else emptyList()) + listOf("restore", "peer", "probe"),
                events,
            )
        }
    }

    @Test fun restorationAndCleanupFailuresAreSuppressedInOrder() {
        val primary = AssertionError("normal restoration")
        val restoration = AssertionError("failure-path restoration")
        val peer = AssertionError("peer close")
        val probe = AssertionError("probe end")
        var attempts = 0
        val reconnect = {
            attempts++
            if (attempts == 1) throw primary
            events += "restore"
            throw restoration
        }
        assertSame(
            primary,
            assertThrows(AssertionError::class.java) {
                fixture(
                    restore = reconnect,
                    peer = {
                        events += "peer"
                        throw peer
                    },
                    probe = {
                        events += "probe"
                        throw probe
                    },
                ).run {
                    sever { events += "sever" }
                    reconnect()
                }
            },
        )
        assertEquals("one proof attempt and one failure-path attempt", 2, attempts)
        assertEquals(listOf(restoration, peer, probe), primary.suppressed.toList())
        assertEquals(listOf("sever", "restore", "peer", "probe"), events)
    }

    @Test fun successKeepsOfflineWindowAndDoesNotRestoreAgain() {
        fixture().run {
            sever { events += "sever" }
            events += "offline_production"
            events += "normal_restore"
            events += "catch_up_proof"
        }
        assertEquals(listOf("sever", "offline_production", "normal_restore", "catch_up_proof", "peer", "probe"), events)
    }

    @Test fun failureBeforeSeverOnlyCleans() {
        val primary = AssertionError("peer open")
        assertSame(primary, assertThrows(AssertionError::class.java) { fixture().run { throw primary } })
        assertEquals(listOf("peer", "probe"), events)
    }

    @Test fun cleanupFailureRemainsVisible() {
        val primary = AssertionError("peer close")
        val secondary = AssertionError("probe end")
        assertSame(
            primary,
            assertThrows(AssertionError::class.java) {
                fixture(peer = { throw primary }, probe = {
                    events += "probe"
                    throw secondary
                }).run { }
            },
        )
        assertEquals(listOf(secondary), primary.suppressed.toList())
        assertEquals(listOf("probe"), events)
    }

    @Test fun cleanupFailureAfterSuccessfulCatchUpStillRestores() {
        val primary = AssertionError("peer close after catch-up")
        assertSame(
            primary,
            assertThrows(AssertionError::class.java) {
                fixture(peer = {
                    events += "peer"
                    throw primary
                }).run {
                    sever { events += "sever" }
                    events += "normal_restore"
                    events += "catch_up_proof"
                }
            },
        )
        assertEquals(listOf("sever", "normal_restore", "catch_up_proof", "peer", "probe", "restore"), events)
    }

    @Test fun sameThrowableDoesNotPreventRemainingCleanup() {
        val primary = AssertionError("same failure")
        assertSame(
            primary,
            assertThrows(AssertionError::class.java) {
                fixture(restore = { throw primary }, peer = { throw primary }).run {
                    sever { }
                    throw primary
                }
            },
        )
        assertEquals(emptyList<Throwable>(), primary.suppressed.toList())
        assertEquals(listOf("probe"), events)
    }
}
