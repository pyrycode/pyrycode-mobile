package de.pyryco.mobile.di

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppPreferencesTeardownTest {
    @Test
    @Ignore("blocked on #1709: lazy preferences file callback consults a closed Koin scope")
    fun resolvedDataStoreDoesNotLookUpContextInAClosedGraph() =
        runBlocking {
            val app =
                KoinApplication.init().apply {
                    androidContext(ApplicationProvider.getApplicationContext<Application>())
                    modules(appModule)
                }
            val store =
                try {
                    app.koin.get<DataStore<Preferences>>()
                } finally {
                    app.close()
                }

            // DataStore's own I/O scope can initialize the file after the registry's graph closes.
            assertTrue(
                "preferences should initialize empty after graph close",
                store.data
                    .first()
                    .asMap()
                    .isEmpty(),
            )
        }
}
