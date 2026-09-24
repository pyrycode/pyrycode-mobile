package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.AttachmentStore
import java.io.File

/**
 * An [AttachmentStore] for tests that build a relay-mode Koin container without `androidContext()` and are
 * not about retrieval (#899). The real binding is rooted at `Context.noBackupFilesDir`; these containers
 * override it for the reasons [InertConversationCache] gives. The store touches its root only when a
 * retrieval runs, which none of those tests does. `AttachmentStoreTest` owns the store's proof.
 */
internal val InertAttachmentStore = AttachmentStore(File(System.getProperty("java.io.tmpdir"), "inert-attachment-store"))
