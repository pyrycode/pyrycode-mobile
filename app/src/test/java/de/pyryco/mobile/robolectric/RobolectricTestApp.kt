package de.pyryco.mobile.robolectric

import android.app.Application
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Robolectric's stand-in for the instrumented test Application: screen tests run against the fake
 * repository binding, as they do on the emulator without e2e arguments. Robolectric builds a fresh
 * Application per test in one JVM, so the previous test's Koin is stopped first.
 */
class RobolectricTestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        stopKoin()
        startKoin {
            androidContext(this@RobolectricTestApp)
            modules(appModule, conversationRepositoryModule(useRelay = false))
        }
    }
}
