package de.pyryco.mobile

import android.app.Application
import android.content.Context
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.share.SharingShortcuts
import de.pyryco.mobile.ui.conversations.thread.OwnedPasteCopy
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import java.io.File

class PyryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OwnedPasteCopy.clearLeftovers(File(noBackupFilesDir, OwnedPasteCopy.DIRECTORY))
        startApplicationGraph(this)
    }
}

internal fun startApplicationGraph(
    context: Context,
    applicationModules: List<Module> = listOf(appModule, conversationRepositoryModule()),
): KoinApplication =
    startKoin {
        androidContext(context)
        modules(applicationModules)
    }.also { it.koin.get<SharingShortcuts>() }
