package de.pyryco.mobile.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import de.pyryco.mobile.data.preferences.AppPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
        )
    }
    single { AppPreferences(get()) }
    // Bindings added by downstream tickets:
    //   #4  — FakeConversationRepository as ConversationRepository
    //   upcoming UI tickets — ViewModels via viewModel { }
}
