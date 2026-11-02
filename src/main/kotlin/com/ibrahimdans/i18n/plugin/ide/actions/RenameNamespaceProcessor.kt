package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.command.undo.GlobalUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiFileProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import java.util.Collections

/**
 * Shift+F6 on a namespace — the file `locales/en/common.json`, or the folder `common/` of a module
 * whose template puts one folder per namespace — renames the namespace, not only the file:
 *  - the same file, or folder, in every other locale;
 *  - the `common:` prefix of the keys written with it, `useTranslation('common')` and its arrays,
 *    `withTranslation`, `getFixedT`, the `ns` option (`{ ns: 'common' }`, `<Trans ns="common">`) and
 *    the `defaultNS` / `fallbackNS` of an i18next initialisation;
 *  - the default and fallback namespaces of the settings.
 * All in the one command of the rename, so one undo takes everything back. The call sites are listed
 * in the rename preview, as the usages of the namespace.
 *
 * Driven by the rename dialog's *Rename namespace in every locale and call site* box
 * ([RenameNamespaceOption]), ticked by default. Unticked, or on a file the plugin does not read as a
 * namespace — a locale-named file (`en.json`), a file no translation source holds, a TS catalogue —
 * the rename is the IDE's own.
 *
 * In a monorepo, a call site in another module than the namespace's is left alone: its `common` is
 * that module's own.
 */
class RenameNamespaceProcessor : RenamePsiFileProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean =
        element is PsiFileSystemItem && NamespaceContainer.of(element) != null

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        super.prepareRenaming(element, newName, allRenames)
        if (!RenameNamespaceOption.isOn()) return
        val container = NamespaceContainer.of(element) ?: return
        val namespace = container.namespaceOf(newName)
        if (namespace.isBlank() || namespace == container.namespace) return
        container.siblings().forEach { sibling -> allRenames.putIfAbsent(sibling.item, sibling.nameFor(namespace)) }
    }

    /**
     * The IDE's references to the file, and the call sites of the namespace. Every locale's file is
     * renamed in the same refactoring: the call sites are given by one of them only, the [anchor]
     * ([NamespaceContainer.anchor]), so that each is rewritten once.
     *
     * They are searched in the plugin's scope, not in [searchScope]: the platform narrows that one to
     * the use scope of the element, and a folder's leaves out the code of the project.
     */
    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
        val own = super.findReferences(element, searchScope, searchInCommentsAndStrings)
        if (!RenameNamespaceOption.isOn()) return own
        val container = NamespaceContainer.of(element) ?: return own
        if (container.anchor() != container.item.virtualFile) return own
        return own + NamespaceCallSites(container).find()
    }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        // Read before the rename: once renamed, the file is no longer the namespace's.
        val container = if (RenameNamespaceOption.isOn()) NamespaceContainer.of(element) else null
        super.renameElement(element, newName, usages, listener)
        container?.let { NamespaceSettings.rename(element.project, it.namespace, it.namespaceOf(newName)) }
    }
}

/**
 * The rename dialog's *Rename namespace in every locale and call site* box, shown on a namespace
 * file or folder. Ticked by default, and remembered for the next rename.
 *
 * Only a box: [RenameNamespaceProcessor] does the renaming, so its renamer has nothing to offer.
 */
class RenameNamespaceOption : AutomaticRenamerFactory {

    override fun isApplicable(element: PsiElement): Boolean = element is PsiFileSystemItem && NamespaceContainer.of(element) != null

    override fun getOptionName(): String = PluginBundle.message("action.rename.namespace.option")

    override fun isEnabled(): Boolean = isOn()

    override fun setEnabled(enabled: Boolean) = PropertiesComponent.getInstance().setValue(OPTION, enabled, true)

    override fun createRenamer(element: PsiElement, newName: String, usages: Collection<UsageInfo>): AutomaticRenamer = NothingToRename

    private object NothingToRename : AutomaticRenamer() {
        override fun getDialogTitle(): String = PluginBundle.message("action.rename.namespace.option")
        override fun getDialogDescription(): String = PluginBundle.message("action.rename.namespace.option")
        override fun entityName(): String = PluginBundle.message("action.rename.namespace.option")
    }

    companion object {
        private const val OPTION = "i18n.rename.namespace.everywhere"

        fun isOn(): Boolean = PropertiesComponent.getInstance().getBoolean(OPTION, true)
    }
}

/**
 * A file or folder that holds a namespace in one locale: `locales/en/common.json` (a file named
 * after its namespace), or `common/` (a folder named after it, holding its files).
 *
 * [shape] is its path with the locale taken out: the same namespace in another locale has the same
 * shape, a namespace of the same name in another folder or module does not.
 */
internal class NamespaceContainer private constructor(
    val item: PsiFileSystemItem,
    val namespace: String,
    private val shape: String,
    val module: ModuleConfig?,
) {
    private val isFile get() = item is PsiFile

    /** The namespace [newName] — the file's new name, extension included, or the folder's — designates. */
    fun namespaceOf(newName: String): String = if (isFile) FileUtilRt.getNameWithoutExtension(newName) else newName

    /** The name this container takes for the namespace [namespace]: a file keeps its own extension. */
    fun nameFor(namespace: String): String = if (isFile) "$namespace.${item.virtualFile.extension}" else namespace

    /** The same namespace in the other locales. */
    fun siblings(): List<NamespaceContainer> = all().filter { it.item.virtualFile != item.virtualFile }

    /** The one container of the namespace, among every locale's, that hands out its call sites. */
    fun anchor(): VirtualFile? = all().map { it.item.virtualFile }.minByOrNull { it.path }

    private fun all(): List<NamespaceContainer> {
        val project = item.project
        val manager = PsiManager.getInstance(project)
        return sourcesOf(project, namespace).mapNotNull { source ->
            val file = fileOf(source) ?: return@mapNotNull null
            val candidate = if (isFile) file else folderNamed(file, namespace)
            val psi = candidate?.let { if (isFile) manager.findFile(it) else manager.findDirectory(it) } ?: return@mapNotNull null
            NamespaceContainer(psi, namespace, shapeOf(candidate, source), module).takeIf { it.shape == shape }
        }.distinctBy { it.item.virtualFile }
    }

    companion object {
        /** A TS or JS catalogue is code: its rename is the language's. */
        private val CODE_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts")
        private const val LOCALE_PLACEHOLDER = "{lang}"

        fun of(element: PsiElement): NamespaceContainer? = when (element) {
            is PsiFile -> ofFile(element)
            is PsiDirectory -> ofFolder(element)
            else -> null
        }

        private fun ofFile(file: PsiFile): NamespaceContainer? {
            val virtualFile = file.virtualFile ?: return null
            if (virtualFile.extension?.lowercase() in CODE_EXTENSIONS) return null
            val source = TranslationFileScope.sourceOf(file) ?: return null
            if (source.isLocaleNamedFile()) return null
            val namespace = namespaceOf(file.project, source)
            // `locales/common/en.json` holds `common` through its folder: the folder is what names it.
            if (namespace != virtualFile.nameWithoutExtension) return null
            return NamespaceContainer(file, namespace, shapeOf(virtualFile, source), moduleOf(file.project, virtualFile))
        }

        private fun ofFolder(folder: PsiDirectory): NamespaceContainer? {
            val namespace = folder.name
            val held = sourcesOf(folder.project, namespace).firstOrNull { source ->
                val file = fileOf(source) ?: return@firstOrNull false
                VfsUtilCore.isAncestor(folder.virtualFile, file, true) && folderNamed(file, namespace) == folder.virtualFile
            } ?: return null
            return NamespaceContainer(folder, namespace, shapeOf(folder.virtualFile, held), moduleOf(folder.project, folder.virtualFile))
        }

        private fun sourcesOf(project: Project, namespace: String): List<LocalizationSource> =
            project.service<LocalizationSourceService>().findAllSources(project)
                .filter { !it.isLocaleNamedFile() && namespaceOf(project, it) == namespace }

        private fun namespaceOf(project: Project, source: LocalizationSource): String =
            TranslationDataLoader.extractNamespace(source, Settings.getInstance(project).config().defaultNamespaces().first())

        private fun fileOf(source: LocalizationSource): VirtualFile? =
            (source.tree?.value() ?: source.host)?.containingFile?.originalFile?.virtualFile

        /** The nearest folder above [file] named [namespace], or null. */
        private fun folderNamed(file: VirtualFile, namespace: String): VirtualFile? =
            generateSequence(file.parent) { it.parent }.firstOrNull { it.name == namespace }

        /** [file]'s path, extension dropped, with the last segment naming [source]'s locale replaced by a placeholder. */
        private fun shapeOf(file: VirtualFile, source: LocalizationSource): String {
            val path = if (file.isDirectory) file.path else file.path.substringBeforeLast('.')
            val segments = path.split('/').toMutableList()
            val index = segments.lastIndexOf(source.localeLabel())
            if (index >= 0) segments[index] = LOCALE_PLACEHOLDER
            return segments.joinToString("/")
        }

        fun moduleOf(project: Project, file: VirtualFile): ModuleConfig? =
            ModuleSources.owner(Settings.getInstance(project).config().modules, ModuleSources.FilePath.of(file.path, project.basePath ?: ""))
    }
}

/**
 * The places the code names [container]'s namespace: the prefix of a key written with it
 * (`t('common:save')`), and the namespace arguments and options of i18next and react-i18next.
 *
 * Found by searching the namespace as a word, then kept only where it stands whole in one of those
 * places: `'common'` as a plain string, a key `common.title` without the namespace separator, or
 * `othercommon:save` are not the namespace.
 */
private class NamespaceCallSites(private val container: NamespaceContainer) {

    private val project = container.item.project
    private val config: Config = Settings.getInstance(project).config()
    private val namespace = container.namespace
    private val functionNames = Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() }

    /** The separators a key's namespace stands before: `:`, and `.` when the first component is the namespace. */
    private val prefixes: List<String> = listOfNotNull(
        config.nsSeparator.takeIf { it.isNotEmpty() },
        config.keySeparator.takeIf { config.firstComponentNs && it.isNotEmpty() },
    ).map { namespace + it }

    fun find(): List<PsiReference> {
        // The search hands files to several threads at once: the processor runs concurrently.
        val found: MutableMap<PsiElement, PsiReference> = Collections.synchronizedMap(linkedMapOf())
        val languages = Extensions.LANG.extensionList
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, _ ->
                if (inOwnModule(element)) {
                    val reference = keyPrefixAt(element, languages) ?: namespaceArgumentAt(element)
                    if (reference != null) found.putIfAbsent(reference.element, reference)
                }
                true
            },
            config.searchScope(project),
            searchWord,
            UsageSearchContext.ANY,
            true
        )
        return found.values.toList()
    }

    /** The namespace's longest word: `admin-panel` is no word of the index. */
    private val searchWord: String = namespace.split(NON_WORD).maxByOrNull { it.length }?.takeIf { it.isNotEmpty() } ?: namespace

    /** False for a file of another module than the namespace's: its own namespace may bear the same name. */
    private fun inOwnModule(element: PsiElement): Boolean {
        val own = container.module ?: return true
        val file = element.containingFile?.originalFile?.virtualFile ?: return true
        val module = NamespaceContainer.moduleOf(project, file) ?: return true
        return module == own
    }

    /** The namespace written at the head of a translation key: `common` in `t('common:save')`. */
    private fun keyPrefixAt(element: PsiElement, languages: List<Lang>): PsiReference? {
        val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) } ?: return null
        // Read as a key, whether it resolves or not: a key missing from the namespace still names it.
        val holder = listOfNotNull(literal, literal.parent).firstOrNull { candidate ->
            ElementManipulators.getManipulator(candidate) != null && languages.any { it.canExtractKey(candidate, functionNames) }
        } ?: return null
        val text = holder.text
        val start = if (text.length > 1 && text.first() in QUOTES) 1 else 0
        if (prefixes.none { text.startsWith(it, start) }) return null
        return NamespaceReference(holder, TextRange(start, start + namespace.length), container)
    }

    /**
     * The namespace given as such: `useTranslation('common')`, `useTranslation(['common', 'x'])`,
     * `withTranslation('common')`, `getFixedT(null, 'common')`, `{ ns: 'common' }`, an i18next
     * initialisation's `defaultNS` and `fallbackNS`, `<Trans ns="common">` and `<Trans ns={['common']}>`.
     */
    private fun namespaceArgumentAt(element: PsiElement): PsiReference? {
        val string = element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression
        if (string != null) {
            if (!string.isQuotedLiteral || string.stringValue != namespace || !isNamespacePlace(string)) return null
            return NamespaceReference(string, TextRange(1, 1 + namespace.length), container)
        }
        val value = element as? XmlAttributeValue ?: element.parent as? XmlAttributeValue ?: return null
        if (value.value != namespace || (value.parent as? XmlAttribute)?.name != NS) return null
        return NamespaceReference(value, value.valueTextRange.shiftLeft(value.textRange.startOffset), container)
    }

    private fun isNamespacePlace(string: JSLiteralExpression): Boolean {
        val place = string.parent.let { if (it is JSArrayLiteralExpression) it.parent else it }
        return when (place) {
            is JSArgumentList -> ((place.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression)?.referenceName in NAMESPACE_FUNCTIONS
            is JSProperty -> place.name in NAMESPACE_OPTIONS
            // `<Trans ns={'common'}>`, `<Trans ns={['common', 'other']}>`.
            else -> PsiTreeUtil.getParentOfType(string, XmlAttribute::class.java, true, JSCallExpression::class.java, JSProperty::class.java)?.name == NS
        }
    }

    private companion object {
        val QUOTES = setOf('"', '\'', '`')
        val NON_WORD = Regex("[^A-Za-z0-9_$]+")
        const val NS = "ns"
        val NAMESPACE_FUNCTIONS = setOf("useTranslation", "withTranslation", "getFixedT", "loadNamespaces", "setDefaultNamespace")
        val NAMESPACE_OPTIONS = setOf(NS, "defaultNS", "fallbackNS")
    }
}

/**
 * The namespace part of a call site, renamed with [container]: the reference the rename preview lists
 * and rewrites. It never resolves anywhere for the editor: only the rename asks for it.
 */
private class NamespaceReference(element: PsiElement, range: TextRange, private val container: NamespaceContainer) :
    PsiReferenceBase<PsiElement>(element, range, true) {

    override fun resolve(): PsiElement = container.item

    override fun handleElementRename(newElementName: String): PsiElement {
        if (rangeInElement.substring(element.text) != container.namespace) return element
        val manipulator = ElementManipulators.getManipulator(element) ?: return element
        return manipulator.handleContentChange(element, rangeInElement, container.namespaceOf(newElementName)) ?: element
    }
}

/**
 * The settings naming a renamed namespace: the default and fallback namespaces, as stored. A value
 * read from the project's i18next initialisation is not stored: the code, renamed with the call sites,
 * keeps saying it. Undone with the rest of the rename.
 */
private object NamespaceSettings {

    private val LIST_SEPARATOR = "[;|,\\s]"

    fun rename(project: Project, old: String, new: String) {
        if (new.isBlank() || new == old) return
        val settings = Settings.getInstance(project)
        val defaultNs = renamedIn(settings.defaultNs, old, new)
        val fallbackNs = renamedIn(settings.fallbackNs, old, new)
        if (defaultNs == null && fallbackNs == null) return
        val before = settings.config()
        val after = before.copy(defaultNs = defaultNs ?: before.defaultNs, fallbackNs = fallbackNs ?: before.fallbackNs)
        settings.setConfig(after)
        UndoManager.getInstance(project).undoableActionPerformed(object : GlobalUndoableAction() {
            override fun undo() = settings.setConfig(before)
            override fun redo() = settings.setConfig(after)
        })
    }

    /** [list] with the namespace [old] replaced by [new], or null when it does not name [old]. */
    private fun renamedIn(list: String, old: String, new: String): String? {
        val pattern = Regex("(?<=^|$LIST_SEPARATOR)${Regex.escape(old)}(?=$|$LIST_SEPARATOR)")
        return if (pattern.containsMatchIn(list)) pattern.replace(list, Regex.escapeReplacement(new)) else null
    }
}
