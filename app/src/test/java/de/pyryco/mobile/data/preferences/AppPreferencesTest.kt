package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tmp.newFile("app_prefs.preferences_pb") },
            )
        prefs = AppPreferences(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun pairedServerExists_defaultsToFalse() =
        runBlocking {
            assertEquals(false, prefs.pairedServerExists.first())
        }

    @Test
    fun setPairedServerExists_true_isReflectedInNextEmit() =
        runBlocking {
            prefs.setPairedServerExists(true)
            assertEquals(true, prefs.pairedServerExists.first())
        }

    @Test
    fun themeMode_defaultsToSystem() =
        runBlocking {
            assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        }

    @Test
    fun setThemeMode_roundTripsAllValues() =
        runBlocking {
            for (mode in ThemeMode.entries) {
                prefs.setThemeMode(mode)
                assertEquals(mode, prefs.themeMode.first())
            }
        }

    @Test
    fun themeMode_unparseableStoredValue_fallsBackToSystem() =
        runBlocking {
            dataStore.edit { it[stringPreferencesKey("theme_mode")] = "PURPLE" }
            assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        }
}
