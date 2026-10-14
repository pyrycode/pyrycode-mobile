package de.pyryco.mobile.di

import android.content.Context
import androidx.room.Room
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import java.io.File

/** Fixed app-private no-backup root also excludes every SQLite sidecar. Application binding is #2161. */
fun openConversationCacheDatabase(context: Context): ConversationCacheDatabase =
    Room
        .databaseBuilder(
            context.applicationContext,
            ConversationCacheDatabase::class.java,
            File(context.noBackupFilesDir, "conversation-cache.db").absolutePath,
        ).build()
