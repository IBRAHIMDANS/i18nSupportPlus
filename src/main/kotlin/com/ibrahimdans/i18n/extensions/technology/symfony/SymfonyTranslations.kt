package com.ibrahimdans.i18n.extensions.technology.symfony

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffLocalization
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffNaming
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffRole
import com.ibrahimdans.i18n.extensions.localization.xliff.XliffTree
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.ibrahimdans.i18n.plugin.utils.project
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Symfony's translations: one file per domain and locale in a `translations/` folder —
 * `messages.fr.yaml`, `validators+intl-icu.de.xlf`, `admin.en.php` — each declared with the locale and
 * the domain its name gives ([SymfonyCatalogue]). The domain is the namespace a call names
 * (`trans('key', [], 'admin')`), `messages` when it names none.
 *
 * Every format Symfony reads that the plugin reads too is declared: YAML, XLIFF 1.2 and 2.0, PHP, JSON.
 * A catalogue is read flattened, as Symfony reads it ([FlattenedTree]).
 *
 * Only in a Symfony project ([SymfonyProject]); publishes no call of its own — the PHP and Twig calls
 * are read by their own syntax, the domain being an argument no function name describes.
 */
class SymfonyTranslations : Technology {

    override fun frameworkId(): String = FRAMEWORK_ID

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> {
        val project = workspace.project
        if (DumbService.isDumb(project) || !SymfonyProject.isSymfony(project)) return emptyList()
        val basePath = project.basePath.orEmpty()
        val separator = Settings.getInstance(project).config().keySeparator
        return runReadAction {
            catalogueFiles(project).mapNotNull { file -> sourceOf(project, file, basePath, separator) }
        }
    }

    private fun sourceOf(project: Project, virtualFile: VirtualFile, basePath: String, separator: String): LocalizationSource? {
        val catalogue = SymfonyCatalogue.parse(virtualFile.name) ?: return null
        val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val localization = localizationOf(virtualFile) ?: return null
        val tree = treeOf(localization, file) ?: return null
        return LocalizationSource(
            FlattenedTree(tree, separator),
            file.name,
            virtualFile.parent.name,
            pathToRoot(basePath, virtualFile.parent.path).trim('/') + '/' + file.name,
            localization,
            locale = catalogue.locale,
            namespace = catalogue.domain,
        )
    }

    /** An XLIFF catalogue is keyed as Symfony keys it, by `resname`; any other as its format reads it. */
    private fun treeOf(localization: Localization<PsiElement>, file: PsiFile): Tree<PsiElement>? =
        if (localization is XliffLocalization) XliffTree(file, XliffRole.TARGET, XliffNaming.SYMFONY)
        else localization.elementsTree(file)

    companion object {
        const val FRAMEWORK_ID = "symfony"

        /** The folder Symfony reads translations from: `translations/` at the root, `Resources/translations/` in a bundle. */
        private const val FOLDER = "translations"

        /** Whether [file] is a catalogue of a Symfony project, which Symfony keys its own way. */
        internal fun isCatalogue(project: Project, file: VirtualFile): Boolean =
            file.parent?.name == FOLDER && SymfonyCatalogue.parse(file.name) != null && SymfonyProject.isSymfony(project)

        /**
         * The formats of Symfony's loaders the plugin reads, gettext's with the Localization plugin; Qt's `.ts`
         * is no TypeScript, and `.csv`, `.ini`, `.mo` are read by none.
         */
        private val FORMATS = setOf("yaml", "yml", "xlf", "xliff", "php", "json", "po")

        /** The localization reading [file], by its extension; null for a format the plugin does not read. */
        private fun localizationOf(file: VirtualFile): Localization<PsiElement>? {
            val extension = file.extension?.lowercase()?.takeIf { it in FORMATS } ?: return null
            return Extensions.LOCALIZATION.extensionList.firstOrNull { localization ->
                localization.types().any { extension in it.extensions() }
            }
        }

        /**
         * The files of every `translations/` folder of [project] outside `vendor/` and `node_modules/`
         * named as a catalogue in a format the plugin reads. Kept until a file is added, removed or moved.
         */
        private fun catalogueFiles(project: Project): List<VirtualFile> =
            CachedValuesManager.getManager(project).getCachedValue(project) {
                val folders = FilenameIndex.getVirtualFilesByName(FOLDER, GlobalSearchScope.projectScope(project))
                    .filter { it.isDirectory && "/vendor/" !in it.path && "/node_modules/" !in it.path }
                val files = folders.sortedBy { it.path }.flatMap { folder ->
                    folder.children.filter { !it.isDirectory && SymfonyCatalogue.parse(it.name) != null && localizationOf(it) != null }
                }
                CachedValueProvider.Result.create(files, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            }
    }
}
