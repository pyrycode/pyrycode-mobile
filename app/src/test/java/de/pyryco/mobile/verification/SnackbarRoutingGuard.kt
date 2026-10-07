package de.pyryco.mobile.verification

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Fail closed: a new notice needs a reviewed routing classification, never a keyword exemption. */
internal class SnackbarRoutingGuard : AutoCloseable {
    private val lifetime = Disposer.newDisposable()
    private val environment =
        KotlinCoreEnvironment.createForProduction(lifetime, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
    private val factory = KtPsiFactory(environment.project, markGenerated = false)

    private val threadPath = "de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt"
    private val readerPath = "de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt"
    private val archivePath = "de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt"

    // Whole callback pins both the message's provenance and the SAVED-only guard.
    private val routes =
        listOf(
            route(
                threadPath,
                """
                val attachmentActions =
                    rememberAttachmentActions(attachmentStates, onOpenMarkdownAttachment) { notice ->
                        noticeScope.launch {
                            val text = resources.getString(notice.message)
                            if (notice == AttachmentNotice.SAVED) snackbarHostState.showSnackbar(text) else errorNotices.show(text)
                        }
                    }
                """,
            ),
            route(
                readerPath,
                """
                val saveNote =
                    rememberNoteSaver { notice ->
                        scope.launch {
                            val text = notices.getValue(notice)
                            if (notice == AttachmentNotice.SAVED) snackbarHostState.showSnackbar(text) else errorNotices.show(text)
                        }
                    }
                """,
            ),
            // Dismissal is a resolution notice; the branch pins its reason and effect key.
            route(
                threadPath,
                """
                when (modalState) {
                    is ModalUiState.Dismissed -> {
                        val reason = dismissReasonText(modalState.source)
                        LaunchedEffect(modalState.modalId) {
                            snackbarHostState.showSnackbar(reason)
                        }
                    }
                }
                """,
            ),
            // RestoreSucceeded alone supplies the success resource and the restored name.
            route(
                archivePath,
                """
                when (effect) {
                    is ArchivedDiscussionsEffect.RestoreSucceeded ->
                        snackbarHostState.showSnackbar(
                            resources.getString(R.string.restored_snackbar, effect.displayName),
                        )
                }
                """,
            ),
        )

    fun violations(
        path: String,
        source: String,
    ): List<String> {
        val file =
            if (path.endsWith(".java")) {
                PsiFileFactory.getInstance(environment.project).createFileFromText("Source.java", JavaFileType.INSTANCE, source)
            } else {
                factory.createFile(path.substringAfterLast('/'), source)
            }
        return descendants(file)
            .filter { node ->
                when (node) {
                    is KtSimpleNameExpression -> node.getReferencedName() in setOf("showSnackbar", "Snackbar")
                    is PsiIdentifier -> node.text in setOf("showSnackbar", "Snackbar")
                    else -> false
                }
            }.filterNot { reference -> routes.any { it.permits(path, reference) } }
            .map { "$path: offset ${it.textOffset}: unclassified ${it.text} route; use an Error pill for failures" }
            .toList()
    }

    override fun close() = Disposer.dispose(lifetime)

    private fun route(
        path: String,
        source: String,
    ): Route {
        val file = factory.createFile("fun classification() {\n${source.trimIndent()}\n}")
        val region = descendants(file).first { it is KtProperty || it is KtWhenEntry }
        return Route(path, region.javaClass, tokens(region), (region.parent as? KtWhenExpression)?.subjectExpression?.text)
    }

    private class Route(
        private val path: String,
        private val regionClass: Class<out PsiElement>,
        private val expectedTokens: List<String>,
        private val whenSubject: String?,
    ) {
        fun permits(
            candidatePath: String,
            reference: PsiElement,
        ): Boolean {
            if (candidatePath != path && !candidatePath.endsWith("/$path")) return false
            val parents = generateSequence(reference.parent) { it.parent }.toList()
            if (parents.filterIsInstance<KtNamedFunction>().firstOrNull()?.name != path.substringAfterLast('/').removeSuffix(".kt")) {
                return false
            }
            return parents.any { region ->
                region.javaClass == regionClass &&
                    tokens(region) == expectedTokens &&
                    (region.parent as? KtWhenExpression)?.subjectExpression?.text == whenSubject
            }
        }
    }

    private companion object {
        fun descendants(element: PsiElement): Sequence<PsiElement> =
            sequence {
                yield(element)
                for (child in children(element)) yieldAll(descendants(child))
            }

        fun tokens(element: PsiElement): List<String> =
            when {
                element is PsiWhiteSpace || element is PsiComment -> emptyList()
                element.firstChild == null -> listOf(element.text)
                else -> children(element).flatMap { tokens(it).asSequence() }.toList()
            }

        // PSI getChildren() can omit leaf tokens; sibling traversal includes operators and identifiers.
        fun children(element: PsiElement): Sequence<PsiElement> = generateSequence(element.firstChild) { it.nextSibling }
    }
}
