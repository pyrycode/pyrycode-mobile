package de.pyryco.mobile

import android.app.Application
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PyryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PyryApp)
            // The build selects the real repository by default; demo builds explicitly opt out.
            modules(appModule, conversationRepositoryModule())
        }
    }
}
