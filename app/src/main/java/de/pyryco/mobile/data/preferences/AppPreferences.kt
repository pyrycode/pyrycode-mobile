package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val pairedServerExists: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[PAIRED_SERVER_EXISTS] ?: false }

    suspend fun setPairedServerExists(value: Boolean) {
        dataStore.edit { prefs -> prefs[PAIRED_SERVER_EXISTS] = value }
    }

    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs ->
            val stored = prefs[THEME_MODE]
            ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE] = mode.name }
    }

    val useWallpaperColors: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[USE_WALLPAPER_COLORS] ?: false }

    suspend fun setUseWallpaperColors(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[USE_WALLPAPER_COLORS] = enabled }
    }

    val defaultModel: Flow<Model> =
        dataStore.data.map { prefs ->
            val stored = prefs[DEFAULT_MODEL]
            Model.entries.firstOrNull { it.name == stored } ?: Model.OPUS_4_7
        }

    suspend fun setDefaultModel(model: Model) {
        dataStore.edit { prefs -> prefs[DEFAULT_MODEL] = model.name }
    }

    val defaultEffort: Flow<Effort> =
        dataStore.data.map { prefs ->
            val stored = prefs[DEFAULT_EFFORT]
            Effort.entries.firstOrNull { it.name == stored } ?: Effort.HIGH
        }

    suspend fun setDefaultEffort(effort: Effort) {
        dataStore.edit { prefs -> prefs[DEFAULT_EFFORT] = effort.name }
    }

    val defaultYolo: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[DEFAULT_YOLO] ?: false }

    suspend fun setDefaultYolo(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DEFAULT_YOLO] = enabled }
    }

    val defaultWorkspace: Flow<String> =
        dataStore.data.map { prefs -> prefs[DEFAULT_WORKSPACE] ?: DEFAULT_SCRATCH_CWD }

    suspend fun setDefaultWorkspace(cwd: String) {
        dataStore.edit { prefs -> prefs[DEFAULT_WORKSPACE] = cwd }
    }

    private companion object {
        val PAIRED_SERVER_EXISTS = booleanPreferencesKey("paired_server_exists")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_WALLPAPER_COLORS = booleanPreferencesKey("use_wallpaper_colors")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_EFFORT = stringPreferencesKey("default_effort")
        val DEFAULT_YOLO = booleanPreferencesKey("default_yolo")
        val DEFAULT_WORKSPACE = stringPreferencesKey("default_workspace")
    }
}
