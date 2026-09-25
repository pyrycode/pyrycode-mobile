package de.pyryco.mobile.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric starts the app without an initialised `FirebaseApp`, as a build without
 * `google-services.json` does (#1102). The source must then report itself unavailable, so the refresher
 * never reaches `FirebaseMessaging.getInstance()`, which would throw.
 */
@RunWith(AndroidJUnit4::class)
class FirebasePushTokenSourceTest {
    @Test
    fun withoutFirebaseApp_isUnavailable() {
        assertFalse(FirebasePushTokenSource(ApplicationProvider.getApplicationContext<Context>()).isAvailable())
    }
}
