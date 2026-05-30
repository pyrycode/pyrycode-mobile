package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
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

    @Test
    fun useWallpaperColors_defaultsToFalse() =
        runBlocking {
            assertEquals(false, prefs.useWallpaperColors.first())
        }

    @Test
    fun setUseWallpaperColors_roundTripsBothValues() =
        runBlocking {
            prefs.setUseWallpaperColors(false)
            assertEquals(false, prefs.useWallpaperColors.first())
            prefs.setUseWallpaperColors(true)
            assertEquals(true, prefs.useWallpaperColors.first())
            prefs.setUseWallpaperColors(false)
            assertEquals(false, prefs.useWallpaperColors.first())
        }

    @Test
    fun defaultModel_defaultsToOpus47() =
        runBlocking {
            assertEquals(Model.OPUS_4_7, prefs.defaultModel.first())
        }

    @Test
    fun setDefaultModel_roundTripsAllValues() =
        runBlocking {
            for (model in Model.entries) {
                prefs.setDefaultModel(model)
                assertEquals(model, prefs.defaultModel.first())
            }
        }

    @Test
    fun defaultModel_unparseableStoredValue_fallsBackToOpus47() =
        runBlocking {
            dataStore.edit { it[stringPreferencesKey("default_model")] = "GPT5" }
            assertEquals(Model.OPUS_4_7, prefs.defaultModel.first())
        }

    @Test
    fun defaultEffort_defaultsToHigh() =
        runBlocking {
            assertEquals(Effort.HIGH, prefs.defaultEffort.first())
        }

    @Test
    fun setDefaultEffort_roundTripsAllValues() =
        runBlocking {
            for (effort in Effort.entries) {
                prefs.setDefaultEffort(effort)
                assertEquals(effort, prefs.defaultEffort.first())
            }
        }

    @Test
    fun defaultEffort_unparseableStoredValue_fallsBackToHigh() =
        runBlocking {
            dataStore.edit { it[stringPreferencesKey("default_effort")] = "ULTRA" }
            assertEquals(Effort.HIGH, prefs.defaultEffort.first())
        }

    @Test
    fun defaultYolo_defaultsToFalse() =
        runBlocking {
            assertEquals(false, prefs.defaultYolo.first())
        }

    @Test
    fun setDefaultYolo_roundTripsBothValues() =
        runBlocking {
            prefs.setDefaultYolo(true)
            assertEquals(true, prefs.defaultYolo.first())
            prefs.setDefaultYolo(false)
            assertEquals(false, prefs.defaultYolo.first())
            prefs.setDefaultYolo(true)
            assertEquals(true, prefs.defaultYolo.first())
        }

    @Test
    fun notificationsEnabled_defaultsToTrue() =
        runBlocking {
            assertEquals(true, prefs.notificationsEnabled.first())
        }

    @Test
    fun setNotificationsEnabled_roundTripsBothValues() =
        runBlocking {
            prefs.setNotificationsEnabled(false)
            assertEquals(false, prefs.notificationsEnabled.first())
            prefs.setNotificationsEnabled(true)
            assertEquals(true, prefs.notificationsEnabled.first())
            prefs.setNotificationsEnabled(false)
            assertEquals(false, prefs.notificationsEnabled.first())
        }

    @Test
    fun defaultWorkspace_defaultsToScratchCwd() =
        runBlocking {
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
        }

    @Test
    fun setDefaultWorkspace_roundTripsCustomCwd() =
        runBlocking {
            prefs.setDefaultWorkspace("/home/user/code/myproj")
            assertEquals("/home/user/code/myproj", prefs.defaultWorkspace.first())
            prefs.setDefaultWorkspace(DEFAULT_SCRATCH_CWD)
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
        }
}
