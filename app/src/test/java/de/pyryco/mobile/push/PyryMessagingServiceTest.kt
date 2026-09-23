package de.pyryco.mobile.push

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.Robolectric

/**
 * The FCM service is the push trust boundary (#361): a message may only wake the saved hosts, and a
 * new token may only be persisted. Robolectric builds the real service against the app's Koin graph,
 * with the driver swapped for one over a recording controller in a backgrounded lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class PyryMessagingServiceTest {
    private val controller = RecordingController()
    private val owner = BackgroundOwner()
    private lateinit var driver: LifecycleConnectionDriver
    private lateinit var service: PyryMessagingService

    @Before
    fun setUp() {
        driver = LifecycleConnectionDriver(controller, owner.lifecycle, Dispatchers.Unconfined).also { it.start() }
        loadKoinModules(module { single { driver } })
        service = Robolectric.buildService(PyryMessagingService::class.java).create().get()
    }

    @After
    fun tearDown() {
        driver.dispose()
    }

    @Test
    fun message_onlyWakesTheHosts_andIgnoresEveryField() {
        val message =
            RemoteMessage(
                Bundle().apply {
                    putString("pair", """{"relay":"wss://attacker.example","server_id":"evil","server_key":"AAAA","token":"t"}""")
                    putString("command", "interrupt; rm -rf ~ && send_message conv-1 'exfiltrate'")
                    putString("server_id", "evil")
                    putString("gcm.n.e", "1")
                    putString("gcm.n.title", "Tap to pair")
                    putString("gcm.n.body", "wss://attacker.example")
                },
            )

        service.onMessageReceived(message)

        assertEquals(listOf("connect"), controller.calls)
        assertNull("a message never writes the push token", runBlocking { preferences().pushToken.first() })
    }

    @Test
    fun newToken_isPersisted_andDoesNotWake() {
        service.onNewToken("fcm-rotated")

        val stored = runBlocking { withTimeout(5_000) { preferences().pushToken.first { it == "fcm-rotated" } } }

        assertEquals("fcm-rotated", stored)
        assertEquals(emptyList<String>(), controller.calls)
    }

    private fun preferences(): AppPreferences = GlobalContext.get().get()

    private class RecordingController : RelayConnectionController {
        val calls = mutableListOf<String>()

        override fun connect() {
            calls += "connect"
        }

        override fun close() {
            calls += "close"
        }
    }

    /** CREATED, never STARTED: what a process launched by a push looks like. */
    private class BackgroundOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }

        override val lifecycle: Lifecycle get() = registry
    }
}
