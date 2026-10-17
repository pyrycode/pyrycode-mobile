package de.pyryco.mobile.di

import android.content.Context
import androidx.room.Room
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import de.pyryco.mobile.data.cache.MigratingConversationCache
import java.io.File

/** Fixed app-private no-backup root also excludes every SQLite sidecar. */
fun openConversationCacheDatabase(context: Context): ConversationCacheDatabase =
    Room
        .databaseBuilder(
            context.applicationContext,
            ConversationCacheDatabase::class.java,
            File(context.noBackupFilesDir, "conversation-cache.db").absolutePath,
        ).build()

/** #2161: the app's one cache. Lists stay in files and saved thread files import into Room, all under noBackupFilesDir. */
fun openConversationCache(context: Context): ConversationCache =
    MigratingConversationCache(File(context.noBackupFilesDir, "conversations"), openConversationCacheDatabase(context))
