package de.pyryco.mobile.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnackbarRoutingGuardTest {
    @Test
    fun productionSourcesContainOnlyClassifiedNonErrorRoutes() {
        val roots = System.getProperty("snackbarProductionSourceRoots")
        require(!roots.isNullOrBlank()) { "Gradle must supply production source roots" }
        val files =
            roots
                .split(File.pathSeparator)
                .flatMap { root ->
                    File(root).walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java") }.toList()
                }.distinct()
        assertTrue("Production source scan must not be empty", files.isNotEmpty())
        SnackbarRoutingGuard().use { guard ->
            val violations = files.flatMap { guard.violations(it.invariantSeparatorsPath, it.readText()) }
            println("Snackbar routing guard: checked ${files.size} production sources; ${violations.size} unclassified routes")
            assertClassified(violations)
        }
    }

    @Test
    fun addedErrorRouteWithNoErrorKeywordsFails() {
        val violations = inspect("ui/NewScreen.kt", "effects.collect { host.showSnackbar(it.message) }")
        val failure = assertThrows(AssertionError::class.java) { assertClassified(violations) }
        assertTrue(failure.message.orEmpty().contains("unclassified showSnackbar"))
        println("Negative control: production gate rejected an added error route")
    }

    @Test
    fun permittedNonErrorRoutesPass() {
        accepted(threadPath, threadSaved + "\n" + dismissal)
        accepted(readerPath, readerSaved)
        accepted(archivePath, restore)
    }

    @Test
    fun errorAddedBesideAnApprovedRouteFails() {
        rejected(threadPath, threadSaved + "\nhost.showSnackbar(problem.label)")
        rejected(readerPath, readerSaved + "\nhost.showSnackbar(problem.label)")
        rejected(archivePath, restore.replace("\n}", "\nArchivedDiscussionsEffect.RestoreFailed -> host.showSnackbar(label)\n}"))
    }

    @Test
    fun reversedOrWidenedSavedGuardFails() {
        for (source in listOf(threadSaved, readerSaved)) {
            val path = if (source == threadSaved) threadPath else readerPath
            rejected(path, source.replace("==", "!="))
            rejected(path, source.replace("AttachmentNotice.SAVED)", "AttachmentNotice.SAVED || failed)"))
            rejected(path, source.replace("AttachmentNotice.SAVED", "AttachmentNotice.SAVE_FAILED"))
        }
    }

    @Test
    fun changedMessageDerivationOrSuccessBranchFails() {
        rejected(threadPath, threadSaved.replace("resources.getString(notice.message)", "problem.label"))
        rejected(readerPath, readerSaved.replace("notices.getValue(notice)", "problem.label"))
        rejected(archivePath, restore.replace("RestoreSucceeded", "RestoreFailed"))
        rejected(archivePath, restore.replace("R.string.restored_snackbar", "R.string.restore_failed"))
        rejected(threadPath, dismissal.replace("dismissReasonText(modalState.source)", "problem.label"))
        rejected(threadPath, dismissal.replace("when (modalState)", "when (anotherState)"))
    }

    @Test
    fun approvedSnippetInAnotherScreenOrFunctionFails() {
        rejected("ui/NewScreen.kt", threadSaved)
        rejected(threadPath, threadSaved, "AnotherScreen")
    }

    @Test
    fun referencesAliasesImplicitReceiversAndBackticksFail() {
        rejected("ui/NewScreen.kt", "val route = host::showSnackbar")
        rejected("ui/NewScreen.kt", "import example.showSnackbar as notify\nfun NewScreen() { notify(label) }")
        rejected("ui/NewScreen.kt", "with(host) { showSnackbar(label) }")
        rejected("ui/NewScreen.kt", "host.`showSnackbar`(label)")
        rejected("ui/NewScreen.java", "class NewScreen { void fail() { host.showSnackbar(label); } }")
    }

    @Test
    fun commentsAndFormattingDoNotChangeClassification() {
        accepted(threadPath, threadSaved.replace("if (", "if /* routing */ (\n"))
        accepted("ui/NewScreen.kt", "// host.showSnackbar(label)\nval copy = \"showSnackbar(label)\"")
        accepted("ui/NewScreen.java", "class NewScreen { /* showSnackbar */ String copy = \"Snackbar\"; }")
    }

    @Test
    fun interpolatedStringCannotHideARoute() {
        rejected("ui/NewScreen.kt", "val copy = \"\${host.showSnackbar(label)}\"")
    }

    @Test
    fun alternateSnackbarApisAlsoRequireClassification() {
        rejected("ui/NewScreen.kt", "Snackbar { Text(problem.label) }")
        rejected("ui/NewScreen.java", "class NewScreen { void fail() { Snackbar.make(view, label, 0).show(); } }")
    }

    private fun accepted(
        path: String,
        source: String,
    ) {
        assertEquals(emptyList<String>(), inspect(path, source))
    }

    private fun assertClassified(violations: List<String>) {
        assertEquals("Unclassified snackbar routes: $violations", emptyList<String>(), violations)
    }

    private fun rejected(
        path: String,
        source: String,
        function: String = screenName(path),
    ) {
        assertTrue("Negative control silently accepted: $source", inspect(path, source, function).isNotEmpty())
    }

    private fun inspect(
        path: String,
        source: String,
        function: String = screenName(path),
    ): List<String> =
        SnackbarRoutingGuard().use {
            val complete =
                when {
                    path.endsWith(".java") || source.startsWith("import ") -> source
                    else -> "fun $function() {\n$source\n}"
                }
            it.violations(path, complete)
        }

    private fun screenName(path: String): String = path.substringAfterLast('/').substringBefore('.')

    private val threadPath = "de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt"
    private val readerPath = "de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt"
    private val archivePath = "de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt"
    private val threadSaved =
        """
        val attachmentActions = rememberAttachmentActions(attachmentStates, onOpenMarkdownAttachment) { notice ->
            noticeScope.launch {
                val text = resources.getString(notice.message)
                if (notice == AttachmentNotice.SAVED) snackbarHostState.showSnackbar(text) else errorNotices.show(text)
            }
        }
        """.trimIndent()
    private val readerSaved =
        """
        val saveNote = rememberNoteSaver { notice ->
            scope.launch {
                val text = notices.getValue(notice)
                if (notice == AttachmentNotice.SAVED) snackbarHostState.showSnackbar(text) else errorNotices.show(text)
            }
        }
        """.trimIndent()
    private val dismissal =
        """
        when (modalState) {
            is ModalUiState.Dismissed -> {
                val reason = dismissReasonText(modalState.source)
                LaunchedEffect(modalState.modalId) { snackbarHostState.showSnackbar(reason) }
            }
        }
        """.trimIndent()
    private val restore =
        """
        when (effect) {
            is ArchivedDiscussionsEffect.RestoreSucceeded ->
                snackbarHostState.showSnackbar(resources.getString(R.string.restored_snackbar, effect.displayName),)
        }
        """.trimIndent()
}
