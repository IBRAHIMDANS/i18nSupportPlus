package com.ibrahimdans.i18n.extensions.technology.typesafei18n

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.SOURCE_ROOT
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.ibrahimdans.i18n.plugin.utils.project
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * typesafe-i18n: one TypeScript module per locale, `src/i18n/<locale>/index.ts`, default-exporting
 * its messages (`const en = { home: { title: 'Home' } } satisfies BaseTranslation`), read in code
 * as `LL.home.title()` — no call names a key as a string, so the technology publishes no function.
 *
 * The locale folders sit in typesafe-i18n's output folder: the `outputPath` of `.typesafe-i18n.json`
 * (`./src/i18n/` by default), or wherever the generator wrote `i18n-types.ts` / `i18n-util.ts`. Each
 * one yields a source holding the default namespace; nothing else is scanned, so a project without
 * typesafe-i18n pays two file-name lookups.
 */
class TypesafeI18nTechnology : Technology {
    override fun frameworkId(): String = "typesafe-i18n"

    override fun translationFunctionNames(): List<String> = emptyList()

    override fun initialize(workspace: Workspace) {}

    override fun cfgNamespaces(): List<String> = emptyList()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> {
        val project = workspace.project
        if (DumbService.isDumb(project)) return emptyList()
        return runReadAction {
            // Asked for every key resolved: kept until any PSI changes, a catalogue being a TS file.
            CachedValuesManager.getManager(project).getCachedValue(project) {
                CachedValueProvider.Result.create(computeSources(project), PsiModificationTracker.MODIFICATION_COUNT)
            }
        }
    }

    companion object {

        private val INDEX_FILES = listOf("index.ts", "index.js", "index.mjs")

        /** The files typesafe-i18n's generator writes next to the locale folders. */
        private val GENERATED_FILES = listOf("i18n-types.ts", "i18n-util.ts", "i18n-util.js")

        private const val CONFIG_FILE = ".typesafe-i18n.json"
        private const val DEFAULT_OUTPUT = "src/i18n"
        private val OUTPUT_PATH = Regex(""""outputPath"\s*:\s*"([^"]+)"""")

        private fun computeSources(project: Project): List<LocalizationSource> {
            val localization = Extensions.LOCALIZATION.extensionList.firstOrNull { it.config().id() == "ts" } ?: return emptyList()
            val basePath = project.basePath.orEmpty()
            return outputFolders(project).flatMap { folder ->
                folder.children.filter { it.isDirectory && LocalizationSourceService.looksLikeLocale(it.name) }
                    .mapNotNull { localeFolder -> sourceOf(project, localeFolder, localization, basePath) }
            }
        }

        private fun sourceOf(project: Project, localeFolder: VirtualFile, localization: Localization<PsiElement>, basePath: String): LocalizationSource? {
            val virtualFile = INDEX_FILES.firstNotNullOfOrNull { localeFolder.findChild(it) } ?: return null
            val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
            val messages = messagesOf(file) ?: return null
            // Lets TsReferenceAssistant offer translation -> code references from this file.
            file.putUserData(SOURCE_ROOT, true)
            return LocalizationSource(
                localization.elementsTree(messages),
                file.name,
                localeFolder.name,
                pathToRoot(basePath, virtualFile.path).trim('/'),
                localization,
                locale = localeFolder.name,
            )
        }

        /** Every output folder of [project]: named by a `.typesafe-i18n.json`, or holding the generated files. */
        internal fun outputFolders(project: Project): List<VirtualFile> {
            val scope = GlobalSearchScope.projectScope(project)
            val configured = FilenameIndex.getVirtualFilesByName(CONFIG_FILE, scope).mapNotNull { config ->
                val text = runCatching { VfsUtilCore.loadText(config) }.getOrNull() ?: return@mapNotNull null
                val output = OUTPUT_PATH.find(text)?.groupValues?.get(1) ?: DEFAULT_OUTPUT
                config.parent?.findFileByRelativePath(output.removePrefix("./").trimEnd('/'))
            }
            val generated = GENERATED_FILES.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }.mapNotNull { it.parent }
            return (configured + generated).filter { "/node_modules/" !in it.path }.distinct().sortedBy { it.path }
        }

        /**
         * The messages object of a locale module: the object literal standing at its top level —
         * `const en = {…} satisfies BaseTranslation`, `const en: BaseTranslation = {…}`, `export default {…}`
         * — not one nested in another or passed to a call. Null when the module holds none.
         */
        internal fun messagesOf(file: PsiFile): JSObjectLiteralExpression? =
            PsiTreeUtil.findChildrenOfType(file, JSObjectLiteralExpression::class.java).firstOrNull {
                PsiTreeUtil.getParentOfType(it, JSObjectLiteralExpression::class.java) == null &&
                    PsiTreeUtil.getParentOfType(it, JSArgumentList::class.java) == null
            }
    }
}
