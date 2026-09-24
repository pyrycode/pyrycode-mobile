package de.pyryco.mobile

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Android's notification-permission prompt (#685): shown only without the permission, recorded as asked,
 * and asked from the channel list only while the switch is on and the app never asked before.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionPromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val stored = MutableStateFlow(emptyPreferences())
    private val preferences =
        AppPreferences(
            object : DataStore<Preferences> {
                override val data = stored

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                    transform(stored.value).also { stored.value = it }
            },
        )

    @Test
    fun withoutThePermissionTheSystemPromptIsShownAndRecordedAsAsked() {
        request()

        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked?.requestedPermissions?.toList())
        assertTrue(runBlocking { preferences.notificationPermissionAsked.first() })
        // Asking never touches the saved switch.
        assertTrue(runBlocking { preferences.notificationsEnabled.first() })
    }

    @Test
    fun withThePermissionAlreadyGrantedNothingIsAsked() {
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        request()

        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        assertFalse(runBlocking { preferences.notificationPermissionAsked.first() })
    }

    @Test
    fun theChannelListAsksOnlyWhileOnWithoutPermissionAndNeverTwice() {
        assertTrue(shouldAskNotificationPermission(enabled = true, granted = false, asked = false))
        assertFalse(shouldAskNotificationPermission(enabled = false, granted = false, asked = false))
        assertFalse(shouldAskNotificationPermission(enabled = true, granted = true, asked = false))
        assertFalse(shouldAskNotificationPermission(enabled = true, granted = false, asked = true))
    }

    private fun request() {
        compose.setContent {
            val request = rememberNotificationPermissionRequest(preferences)
            LaunchedEffect(Unit) { request() }
        }
        compose.waitForIdle()
    }
}
