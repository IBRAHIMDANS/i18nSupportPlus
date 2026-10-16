package com.ibrahimdans.i18n.plugin.ide.diff

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ReferenceLocale
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

/**
 * The translation changes of the working copy, and the locales that did not follow them in
 * [referenceLocale]. [references] are the reference locales the comparison can take instead — the
 * project's first, then each other one a configured module translates from — empty when the
 * modules declare no other one.
 */
data class TranslationChanges(
    val changes: List<TranslationChange>,
    val lagging: List<LaggingLocale>,
    val referenceLocale: String?,
    val references: List<String> = emptyList(),
)

/**
 * The translations a set of VCS changes touches — by default the working copy's, read from the VCS
 * of the project — any VCS: the platform's change list gives each modified file's content before
 * the change.
 *
 * Only files the plugin reads as translation sources count. A deleted translation file is left
 * out: there is no source left to say which locale and namespace it held.
 */
object LocalTranslationChanges {

    /** The working copy's translation changes. Needs a read action. */
    fun collect(project: Project): TranslationChanges =
        collect(project, ChangeListManager.getInstance(project).allChanges)

    /**
     * The translation changes among [changes], whatever produced them: the working copy, or a
     * comparison with another branch. Needs a read action, and reads each before-content, which a
     * VCS may fetch on demand.
     */
    fun collect(project: Project, changes: Collection<Change>): TranslationChanges {
        val files = changes.mapNotNull { change ->
            val virtualFile = change.afterRevision?.file?.virtualFile ?: return@mapNotNull null
            val after = PsiManager.getInstance(project).findFile(virtualFile) ?: return@mapNotNull null
            versionsOf(after, change.beforeRevision?.content)
        }
        return compare(project, files)
    }

    /**
     * [after], a translation file of the project, against its [before] text — null for a file
     * the change adds. Null when [after] is not a translation source.
     */
    fun versionsOf(after: PsiFile, before: String?): FileVersions? {
        val source = TranslationFileScope.sourceOf(after) ?: return null
        val config = Settings.getInstance(after.project).config()
        val namespace = TranslationDataLoader.extractNamespace(source, config.defaultNamespaces().first())
        val beforeLeaves = before?.let {
            val copy = PsiFileFactory.getInstance(after.project).createFileFromText(after.name, after.fileType, it)
            TranslationFileKeys.translationLeaves(copy)
        }.orEmpty()
        return FileVersions(namespace, source.localeLabel(), beforeLeaves, TranslationFileKeys.translationLeaves(after))
    }

    /**
     * The changes of [files], and the locales of the project that did not follow them in
     * [reference] — by default the project's reference locale. Needs a read action.
     */
    fun compare(project: Project, files: List<FileVersions>, reference: String? = null): TranslationChanges =
        against(project, TranslationDiff.changes(files), reference)

    /**
     * [changes], and the locales of the project that did not follow them in [reference] — by
     * default the project's reference locale: what the changes dialog shows again when another
     * reference is chosen. Needs a read action.
     */
    fun against(project: Project, changes: List<TranslationChange>, reference: String? = null): TranslationChanges {
        val config = Settings.getInstance(project).config()
        val defaultNamespace = config.defaultNamespaces().first()
        val localesByNamespace = project.service<LocalizationSourceService>().findAllSources(project)
            .groupBy({ TranslationDataLoader.extractNamespace(it, defaultNamespace) }, { it.localeLabel() })
            .mapValues { (_, locales) -> locales.toSet() }
        val projectReference = ReferenceLocale.of(null, config, localesByNamespace.values.flatten().distinct())
            ?: return TranslationChanges(changes, emptyList(), null)
        val chosen = reference ?: projectReference
        val lagging = TranslationDiff.lagging(changes, chosen, localesByNamespace, config.pluralSeparator)
        return TranslationChanges(changes, lagging, chosen, referencesOf(project, projectReference, config))
    }

    /**
     * [projectReference], then each other locale a configured module translates from — empty with
     * fewer than two modules, where the project is one scope, or when they all share it.
     */
    private fun referencesOf(project: Project, projectReference: String, config: Config): List<String> {
        val modules = Settings.getInstance(project).modules.filter { it.rootDirectory.isNotBlank() }
        if (modules.size < 2) return emptyList()
        val references = (listOf(projectReference) + modules.mapNotNull { referenceOf(project, it, config) }).distinct()
        return if (references.size > 1) references else emptyList()
    }

    /** The locale [module] translates from, among its own files. */
    fun referenceOf(project: Project, module: ModuleConfig, config: Config): String? =
        ReadAction.compute<String?, RuntimeException> {
            ReferenceLocale.of(module, config, TranslationDataLoader.discoverLocales(project, module))
        }
}
