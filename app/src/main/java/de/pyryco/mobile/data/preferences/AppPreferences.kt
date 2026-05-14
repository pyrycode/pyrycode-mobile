package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    private companion object {
        val PAIRED_SERVER_EXISTS = booleanPreferencesKey("paired_server_exists")
    }
}
