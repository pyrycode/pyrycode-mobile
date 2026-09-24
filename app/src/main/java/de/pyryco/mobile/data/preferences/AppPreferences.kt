package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

class AppPreferences(
    private val dataStore: DataStore<Preferences>,
) {
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

    /**
     * The phone's one remembered effort level (#686): the published level string of the last effort write
     * the daemon acknowledged, or `null` when none has succeeded yet. No fallback, and deliberately apart
     * from [defaultEffort], whose `HIGH` substitute is not evidence of a successful choice.
     */
    val rememberedEffort: Flow<String?> =
        dataStore.data.map { prefs -> prefs[REMEMBERED_EFFORT] }

    /** Stores [level] verbatim. Logs the outcome by static code only, never the level. */
    suspend fun setRememberedEffort(level: String): Result<Unit> =
        try {
            dataStore.edit { prefs -> prefs[REMEMBERED_EFFORT] = level }
            RelayLog.d { "event=remembered_effort_set outcome=success" }
            Result.success(Unit)
        } catch (error: IOException) {
            RelayLog.w { "event=remembered_effort_set outcome=io_failure" }
            Result.failure(error)
        }

    val defaultYolo: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[DEFAULT_YOLO] ?: false }

    suspend fun setDefaultYolo(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DEFAULT_YOLO] = enabled }
    }

    val notificationsEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[NOTIFICATIONS_ENABLED] = enabled }
    }

    /** Whether the app has ever shown Android's notification-permission prompt (#685); only ever set. */
    val notificationPermissionAsked: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[NOTIFICATION_PERMISSION_ASKED] ?: false }

    suspend fun setNotificationPermissionAsked() {
        dataStore.edit { prefs -> prefs[NOTIFICATION_PERMISSION_ASKED] = true }
    }

    val defaultWorkspace: Flow<String> =
        dataStore.data.map { prefs ->
            legacyWorkspaceKey(prefs)?.let { prefs[it] } ?: DEFAULT_SCRATCH_CWD
        }

    suspend fun setDefaultWorkspace(cwd: String) {
        dataStore.edit { prefs ->
            legacyWorkspaceKey(prefs)?.let { prefs[it] = cwd }
        }
    }

    /** [serverId] is the exact saved server identity, never its label or relay URL. */
    fun defaultWorkspace(serverId: String): Flow<String> =
        dataStore.data.map { prefs -> prefs[workspaceKey(serverId)] ?: DEFAULT_SCRATCH_CWD }

    suspend fun setDefaultWorkspace(
        serverId: String,
        cwd: String,
    ): Result<Unit> = editWorkspace("workspace_default_set") { prefs -> prefs[workspaceKey(serverId)] = cwd }

    suspend fun removeDefaultWorkspace(serverId: String): Result<Unit> =
        editWorkspace("workspace_default_removed") { prefs -> prefs.remove(workspaceKey(serverId)) }

    /**
     * Call with the initial saved-host snapshot. The first successful call permanently decides
     * legacy ownership, including when the old path is absent or no single owner exists.
     */
    suspend fun migrateDefaultWorkspace(initialServerIds: Set<String>): Result<Unit> {
        val owner = initialServerIds.singleOrNull()
        return editWorkspace("workspace_migration_checked") { prefs ->
            if (prefs[WORKSPACE_MIGRATED] == true) return@editWorkspace
            if (owner != null) {
                prefs[LEGACY_WORKSPACE_OWNER] = owner
                val key = workspaceKey(owner)
                val legacy = prefs[DEFAULT_WORKSPACE]
                if (prefs[key] == null && legacy != null) prefs[key] = legacy
            }
            prefs[WORKSPACE_MIGRATED] = true
            prefs.remove(DEFAULT_WORKSPACE)
        }
    }

    private fun legacyWorkspaceKey(prefs: Preferences): Preferences.Key<String>? =
        if (prefs[WORKSPACE_MIGRATED] == true) {
            prefs[LEGACY_WORKSPACE_OWNER]?.let(::workspaceKey)
        } else {
            DEFAULT_WORKSPACE
        }

    private suspend fun editWorkspace(
        event: String,
        transform: (MutablePreferences) -> Unit,
    ): Result<Unit> =
        try {
            dataStore.edit { transform(it) }
            RelayLog.d { "event=$event outcome=success" }
            Result.success(Unit)
        } catch (error: IOException) {
            RelayLog.w { "event=$event outcome=io_failure" }
            Result.failure(error)
        }

    val pushToken: Flow<String?> =
        dataStore.data.map { prefs -> prefs[PUSH_TOKEN] }

    suspend fun setPushToken(token: String) {
        dataStore.edit { prefs -> prefs[PUSH_TOKEN] = token }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_WALLPAPER_COLORS = booleanPreferencesKey("use_wallpaper_colors")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_EFFORT = stringPreferencesKey("default_effort")
        val REMEMBERED_EFFORT = stringPreferencesKey("remembered_effort")
        val DEFAULT_YOLO = booleanPreferencesKey("default_yolo")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val DEFAULT_WORKSPACE = stringPreferencesKey("default_workspace")
        val WORKSPACE_MIGRATED = booleanPreferencesKey("default_workspace_migrated")
        val LEGACY_WORKSPACE_OWNER = stringPreferencesKey("legacy_workspace_owner")
        val PUSH_TOKEN = stringPreferencesKey("push_token")

        fun workspaceKey(serverId: String) = stringPreferencesKey("default_workspace_host:$serverId")
    }
}
