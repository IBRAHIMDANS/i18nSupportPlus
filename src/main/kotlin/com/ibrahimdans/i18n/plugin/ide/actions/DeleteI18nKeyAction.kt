package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.ide.toolwindow.OrphanKeyDeleter
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/**
 * Deletes the i18n key under the caret from every locale of its module, from the code
 * (`t('menu.home')`) or from a translation file.
 *
 * Renaming and moving a key already acted on every locale; deleting one meant opening each
 * translation file, and the table only deletes keys a scan found unused. A key still used in the
 * code is deleted only once the user has seen where — the calls stay in place and will point to a
 * missing key, which the annotator then reports. The deletion goes through [OrphanKeyDeleter],
 * the table's: one command, so one Ctrl+Z restores every locale.
 *
 * A plural is deleted as a group: from the code, `t('item')` resolves to `item_one` and
 * `item_other`; in a translation file, the caret on one form takes its sibling forms along.
 */
class DeleteI18nKeyAction : AnAction() {

    /** What the action deletes: [keys], spelled as the table spells them, in [module]. */
    internal data class Plan(
        val shownKey: String,
        val keys: List<String>,
        val usages: List<String>,
        val module: ModuleConfig?,
    )

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val element = elementAtCaret(e)
        e.presentation.isEnabledAndVisible = element != null && (referenceAt(element) != null || translationPathAt(element) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = elementAtCaret(e) ?: return
        val title = PluginBundle.message("action.delete.key.title")
        val plan = ProgressManager.getInstance().runProcessWithProgressSynchronously<Plan?, RuntimeException>(
            { ReadAction.compute<Plan?, RuntimeException> { planOf(element) } },
            title,
            true,
            project
        ) ?: return
        confirmAndDelete(project, plan, title)
    }

    private fun elementAtCaret(e: AnActionEvent): PsiElement? {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.EDITOR)?.caretModel?.offset ?: return null
        return file.findElementAt(offset) ?: file.findElementAt(offset - 1)
    }

    internal companion object {

        private const val USAGES_SHOWN = 5

        /**
         * Asks the user, then deletes [plan]: the one flow of the action and of the translation
         * dialog's *Delete* button. A key still used is deleted only once the user has seen where.
         * Returns whether anything was deleted.
         */
        fun confirmAndDelete(project: Project, plan: Plan, title: String): Boolean {
            if (plan.keys.isEmpty()) {
                Messages.showInfoMessage(project, PluginBundle.message("action.delete.key.notFound", plan.shownKey), title)
                return false
            }
            if (!confirmed(project, plan, title)) return false
            val config = Settings.getInstance(project).config()
            OrphanKeyDeleter(project, plan.module).delete(plan.keys.map { KeysSynchronizer().buildFullKey(it, config) })
            return true
        }

        private fun confirmed(project: Project, plan: Plan, title: String): Boolean {
            if (plan.usages.isEmpty()) {
                return Messages.showYesNoDialog(
                    project, PluginBundle.message("action.delete.key.confirm", plan.shownKey), title, Messages.getQuestionIcon()
                ) == Messages.YES
            }
            val shown = plan.usages.take(USAGES_SHOWN).joinToString("\n") { "  $it" }
            val more = plan.usages.size - USAGES_SHOWN
            val list = if (more > 0) shown + "\n  " + PluginBundle.message("action.delete.key.used.more", more) else shown
            return Messages.showYesNoDialog(
                project,
                PluginBundle.message("action.delete.key.used", plan.shownKey, plan.usages.size, list),
                title,
                PluginBundle.message("action.delete.key.anyway"),
                Messages.getCancelButton(),
                Messages.getWarningIcon()
            ) == Messages.YES
        }

        /** What deleting the key at [element] involves; needs a read action. */
        fun planOf(element: PsiElement): Plan? {
            val project = element.project
            val config = Settings.getInstance(project).config()
            val reference = referenceAt(element)
            val keys: List<String>
            val shownKey: String
            if (reference != null) {
                shownKey = reference.value
                keys = reference.multiResolve(false).mapNotNull { it.element?.let { target -> spelledKey(target, config) } }.distinct()
            } else {
                val path = translationPathAt(element) ?: return null
                val file = element.containingFile
                val ownKey = spelledKey(element, config) ?: return null
                shownKey = ownKey
                val siblings = pluralSiblings(file, path, config)
                keys = (listOf(ownKey) + siblings.mapNotNull { spelledPath(file, it, config) }).distinct()
            }
            if (keys.isEmpty()) return Plan(shownKey, emptyList(), emptyList(), null)
            return Plan(shownKey, keys, usagesOf(project, keys.toSet(), config), moduleOf(element, config))
        }

        /**
         * What deleting [key] — spelled as the table spells it (`common:menu.home`) — involves in
         * [module], for a caller holding a key rather than an element: the translation dialog.
         * A plural is a group here too: `item`, `item_one` or `item_other` each take every form
         * of `item` along. Needs a read action.
         */
        fun planOf(project: Project, key: String, module: ModuleConfig?): Plan {
            val config = Settings.getInstance(project).config()
            val base = PluralKey.stripSuffix(key, config.pluralSeparator)
            val keys = TranslationDataLoader.loadAllTranslations(project, module).keys
                .filter { it == key || PluralKey.stripSuffix(it, config.pluralSeparator) == base }
                .sorted()
            if (keys.isEmpty()) return Plan(key, emptyList(), emptyList(), module)
            return Plan(key, keys, usagesOf(project, keys.toSet(), config), module)
        }

        /**
         * The module all of [sources] belong to, or null when they span several or none: the
         * translation dialog's *Delete* acts on the files it shows, in one module when they all
         * sit in one.
         */
        fun moduleHolding(sources: Collection<LocalizationSource>, config: Config): ModuleConfig? {
            if (config.modules.isEmpty()) return null
            return sources.map { ModuleSources.owner(config.modules, TranslationDataLoader.projectPathOf(it)) }
                .distinct().singleOrNull()
        }

        /** The i18n reference of the code literal [element] belongs to, or null. */
        fun referenceAt(element: PsiElement): I18nReference? =
            listOfNotNull(element, element.parent).firstNotNullOfOrNull { candidate ->
                candidate.references.filterIsInstance<I18nReference>().firstOrNull()
            }

        /**
         * The path of the translation [element] sits in — its name or its value — or null outside
         * a translation file, on the root, or inside an object: deleting `menu` would take every
         * key below it.
         */
        fun translationPathAt(element: PsiElement): List<String>? {
            val file = element.containingFile ?: return null
            TranslationFileScope.sourceOf(file) ?: return null
            val path = TranslationFileKeys.pathOf(element).takeIf { it.isNotEmpty() } ?: return null
            val isObject = TranslationFileKeys.translationLeaves(file).keys.any { it.size > path.size && it.subList(0, path.size) == path }
            return path.takeUnless { isObject }
        }

        /** The other forms of the plural [path] is a form of, in [file]; none for a plain key. */
        private fun pluralSiblings(file: PsiFile, path: List<String>, config: Config): List<List<String>> {
            val base = PluralKey.stripSuffix(path.last(), config.pluralSeparator)
            if (base == path.last()) return emptyList()
            val parent = path.dropLast(1)
            return TranslationFileKeys.translationLeaves(file).keys.filter {
                it.size == path.size && it.dropLast(1) == parent && PluralKey.stripSuffix(it.last(), config.pluralSeparator) == base
            }
        }

        /** The key holding [translation], spelled as the table spells it (`common:menu.home`). */
        private fun spelledKey(translation: PsiElement, config: Config): String? {
            val file = translation.containingFile ?: return null
            val path = TranslationFileKeys.pathOf(translation).takeIf { it.isNotEmpty() } ?: return null
            return spelledPath(file, path, config)
        }

        private fun spelledPath(file: PsiFile, path: List<String>, config: Config): String? {
            val source = TranslationFileScope.sourceOf(file) ?: return null
            val defaultNamespaces = config.defaultNamespaces()
            val namespace = TranslationDataLoader.extractNamespace(source, defaultNamespaces.first())
            val prefix = if (namespace in defaultNamespaces) "" else namespace + KeySpelling.NAMESPACE_SEPARATOR
            return prefix + path.fold("") { spelled, segment -> KeySpelling.child(config, spelled, segment) }
        }

        /**
         * Where the code uses one of [keys], as `File.js:12`: the literals found by their last word,
         * kept when their reference lands on a translation of one of those keys — the check
         * *Rename* makes, which keeps a namesake in another namespace out.
         */
        private fun usagesOf(project: Project, keys: Set<String>, config: Config): List<String> {
            val words = keys.mapNotNull { key ->
                val last = KeySpelling.segmentsOf(key, config).lastOrNull() ?: return@mapNotNull null
                PluralKey.stripSuffix(last, config.pluralSeparator).split(NON_WORD).lastOrNull { it.isNotEmpty() }
            }.toSet()
            val languages = Extensions.LANG.extensionList
            val found = linkedSetOf<PsiElement>()
            for (word in words) {
                PsiSearchHelper.getInstance(project).processElementsWithWord(
                    { element, _ ->
                        val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) }
                        val reference = literal?.let(::referenceAt)
                        if (reference != null && reference.multiResolve(false).any { result ->
                                result.element?.let { spelledKey(it, config) } in keys
                            }) {
                            found += reference.element
                        }
                        true
                    },
                    config.searchScope(project),
                    word,
                    UsageSearchContext.ANY,
                    true
                )
            }
            return found.map(::locationOf)
        }

        private fun locationOf(element: PsiElement): String {
            val file = element.containingFile
            val line = PsiDocumentManager.getInstance(element.project).getDocument(file)
                ?.getLineNumber(element.textRange.startOffset)?.plus(1)
            return if (line != null) "${file.name}:$line" else file.name
        }

        private fun moduleOf(element: PsiElement, config: Config): ModuleConfig? {
            if (config.modules.isEmpty()) return null
            val file = element.containingFile?.virtualFile ?: return null
            return ModuleSources.owner(config.modules, ModuleSources.FilePath.of(file, element.project.basePath ?: ""))
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}_$]+")
    }
}
