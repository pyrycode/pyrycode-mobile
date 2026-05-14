package de.pyryco.mobile.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("app_prefs.preferences_pb") },
        )

    @Test
    fun initialState_emitsSystem_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = SettingsViewModel(prefs)
            val collector = launch { vm.themeMode.collect { } }
            advanceUntilIdle()
            assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
            collector.cancel()
        }

    @Test
    fun initialState_mirrorsPersistedValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setThemeMode(ThemeMode.DARK)
            advanceUntilIdle()
            val vm = SettingsViewModel(prefs)
            val collector = launch { vm.themeMode.collect { } }
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, vm.themeMode.value)
            collector.cancel()
        }

    @Test
    fun onSelectTheme_persistsLight() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = SettingsViewModel(prefs)
            vm.onSelectTheme(ThemeMode.LIGHT)
            advanceUntilIdle()
            assertEquals(ThemeMode.LIGHT, prefs.themeMode.first())
        }

    @Test
    fun onSelectTheme_persistsDark() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = SettingsViewModel(prefs)
            vm.onSelectTheme(ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, prefs.themeMode.first())
        }

    @Test
    fun onSelectTheme_persistsSystem() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setThemeMode(ThemeMode.DARK)
            advanceUntilIdle()
            val vm = SettingsViewModel(prefs)
            vm.onSelectTheme(ThemeMode.SYSTEM)
            advanceUntilIdle()
            assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        }

    @Test
    fun themeMode_flowReEmits_afterOnSelectTheme() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = SettingsViewModel(prefs)
            val collector = launch { vm.themeMode.collect { } }
            advanceUntilIdle()
            vm.onSelectTheme(ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, vm.themeMode.value)
            vm.onSelectTheme(ThemeMode.LIGHT)
            advanceUntilIdle()
            assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
            collector.cancel()
        }

    @Test
    fun useWallpaperColors_initialState_emitsFalse_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = SettingsViewModel(prefs)
            val collector = launch { vm.useWallpaperColors.collect { } }
            advanceUntilIdle()
            assertEquals(false, vm.useWallpaperColors.value)
            collector.cancel()
        }

    @Test
    fun useWallpaperColors_initialState_mirrorsPersistedTrue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setUseWallpaperColors(true)
            advanceUntilIdle()
            val vm = SettingsViewModel(prefs)
            val collector = launch { vm.useWallpaperColors.collect { } }
            advanceUntilIdle()
            assertEquals(true, vm.useWallpaperColors.value)
            collector.cancel()
        }

    @Test
    fun onToggleUseWallpaperColors_persistsAndFlowReEmits() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = SettingsViewModel(prefs)
            val collector = launch { vm.useWallpaperColors.collect { } }
            advanceUntilIdle()
            vm.onToggleUseWallpaperColors(true)
            advanceUntilIdle()
            assertEquals(true, prefs.useWallpaperColors.first())
            assertEquals(true, vm.useWallpaperColors.value)
            vm.onToggleUseWallpaperColors(false)
            advanceUntilIdle()
            assertEquals(false, prefs.useWallpaperColors.first())
            assertEquals(false, vm.useWallpaperColors.value)
            collector.cancel()
        }
}
