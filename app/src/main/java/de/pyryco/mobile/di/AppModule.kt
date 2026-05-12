package de.pyryco.mobile.di

import org.koin.dsl.module

val appModule = module {
    // Bindings added by downstream tickets:
    //   #11 — AppPreferences (DataStore)
    //   #4  — FakeConversationRepository as ConversationRepository
    //   upcoming UI tickets — ViewModels via viewModel { }
}
