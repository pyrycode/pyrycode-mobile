package de.pyryco.mobile

import android.app.Application
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.thread.OwnedPasteCopy
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class PyryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OwnedPasteCopy.clearLeftovers(File(noBackupFilesDir, OwnedPasteCopy.DIRECTORY))
        startKoin {
            androidContext(this@PyryApp)
            // The build selects the real repository by default; demo builds explicitly opt out.
            modules(appModule, conversationRepositoryModule())
        }
    }
}
