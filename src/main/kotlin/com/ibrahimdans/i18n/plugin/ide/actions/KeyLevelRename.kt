package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.lang.js.extractors.ParaglideMessageExtractor
import com.ibrahimdans.i18n.extensions.technology.paraglide.ParaglideOutdir
import com.ibrahimdans.i18n.extensions.technology.paraglide.ParaglideTechnology
import com.ibrahimdans.i18n.plugin.ide.edit.TranslationEditorBridge
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.references.code.I18nReference
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.settings.searchScope
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.review.ReviewMigration
import com.ibrahimdans.i18n.plugin.review.ReviewMove
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil

/**
 * Renames an intermediate level of a key path — `button` in `button.save`, so that every key under
 * `button.*` moves to `actions.*` — in every locale and at every call site, in one undo.
 *
 * A level is [path] in [namespaces] (the key's own, or the default ones for a key naming none). What
 * changes:
 *  - the property at [path] in every translation file of those namespaces (JSON and YAML alike);
 *  - each code literal whose key resolves under that property, rewritten at that one segment, however
 *    it is written: `common:button.save`, `button.save` under `useTranslation('common')`;
 *  - a hook's `keyPrefix` going through the level (`useTranslation('common', { keyPrefix: 'button' })`),
 *    so the `t('save')` calls under it keep resolving without being touched.
 * Nothing else: `other:button.save` and `form.button.save` resolve elsewhere and are left alone.
 *
 * Keys built at runtime (`` t(`button.${action}`) ``) cannot be rewritten: they are listed in the
 * confirmation. A level whose new name already exists next to it is refused rather than merged.
 *
 * The reviews of every key under the level follow it ([Plan.reviews]), per locale and module.
 *
 * A level naming a plural group (`itemCount` for `itemCount_one` / `itemCount_other`) moves every
 * form, suffixes kept. Where keys are flat, a level is a whole key (`home.title`): it is never cut
 * at the key separator.
 */
internal class KeyLevelRename(
    private val project: Project,
    private val namespaces: List<String>,
    val path: List<String>,
    private val config: Config,
    private val caller: PsiElement,
) {

    /** One text replacement, gathered under a read action and applied in the write action. */
    data class Edit(val document: Document, val range: TextRange, val text: String)

    /**
     * What renaming the level to a new name does, or why it cannot. [reviews] moves the reviews of
     * the keys under the level, to be applied in the same command as [edits].
     */
    data class Plan(
        val edits: List<Edit>,
        val files: Int,
        val callSites: Int,
        val dynamicUsages: List<String>,
        val conflict: Boolean,
        val reviews: List<ReviewMove> = emptyList(),
    )

    val levelName: String get() = path.last()

    private val sources: List<LocalizationSource> by lazy { project.service<LocalizationSourceService>().findSources(namespaces, caller) }

    /** The level's property value in each source holding it: the nodes the rename moves. */
    private val levelNodes: List<PsiElement> by lazy {
        sources.flatMap { source -> nodesAt(source, path) }
    }

    /** The separator a written key is cut at, or null when keys are flat and a key is one segment. */
    private val separator: String? = config.keySeparator.takeUnless { config.usesFlatKeys() || it.isEmpty() }

    /** Builds the plan for [newName]; needs a read action. */
    fun plan(newName: String): Plan {
        val conflict = sources.any { nodesAt(it, path.dropLast(1) + newName).isNotEmpty() }
        if (conflict || levelNodes.isEmpty()) return Plan(emptyList(), levelNodes.size, 0, emptyList(), conflict)
        val fileEdits = levelNodes.mapNotNull { renamedProperty(it, newName) }
        val found = codeEdits(newName)
        val reviews = levelNodes.mapNotNull { node -> ReviewMigration.moveOf(node) { it.dropLast(1) + renamed(it.last(), newName) } }.distinct()
        return Plan((fileEdits + found.edits).distinct(), fileEdits.map { it.document }.distinct().size, found.edits.size, found.dynamic, false, reviews)
    }

    /** The node at [keyPath] in [source], or the forms of the plural group it names. */
    private fun nodesAt(source: LocalizationSource, keyPath: List<String>): List<PsiElement> =
        Resolver.resolve(keyPath.map { Literal(it) }, source, config.pluralSeparator)
            .filter { it.unresolved.isEmpty() }
            .mapNotNull { it.element?.value() }

    /** A property [name] of the level renamed: the level's own, or one of its plural forms, suffix kept. */
    private fun renamed(name: String, newName: String): String =
        if (name != levelName && name.startsWith(levelName)) newName + name.removePrefix(levelName) else newName

    private fun renamedProperty(node: PsiElement, newName: String): Edit? {
        val property = TranslationPsi.propertyOf(node, strict = false) ?: node.parent ?: return null
        val nameElement = TranslationPsi.entryOf(property)?.keyElement ?: return null
        val document = documentOf(nameElement) ?: return null
        val raw = nameElement.text
        val quote = raw.firstOrNull()?.takeIf { it in QUOTES && raw.length > 1 && raw.last() == it }
        val renamed = renamed(if (quote != null) raw.substring(1, raw.length - 1) else raw, newName)
        TranslationEditorBridge.rename(property, renamed)?.let { edit ->
            return Edit(document, TextRange(edit.span.start, edit.span.end), edit.newText)
        }
        return Edit(document, nameElement.textRange, if (quote != null) "$quote$renamed$quote" else renamed)
    }

    private class Found(val edits: List<Edit>, val dynamic: List<String>)

    /**
     * The code literals and key prefixes to rewrite, found by searching the level's name as a word —
     * every spelling of a key under the level writes it, as does a key prefix going through it.
     */
    private fun codeEdits(newName: String): Found {
        val edits = linkedSetOf<Edit>()
        val dynamic = linkedSetOf<String>()
        val languages = Extensions.LANG.extensionList
        val parser = RawKeyParser(project)
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, _ ->
                // Paraglide's generated code names every message: rewritten at its next compilation, never edited.
                if (element.containingFile?.virtualFile?.let(ParaglideOutdir::contains) == true) return@processElementsWithWord true
                // Paraglide's `m.hello` holds no reference: its key, read from the member, is rewritten in place.
                paraglideMemberAt(element)?.let { member ->
                    paraglideEdit(member, newName)?.let(edits::add)
                    return@processElementsWithWord true
                }
                // A key prefix is no key: no language resolves it as one, so it is looked at as found.
                (element as? JSLiteralExpression ?: element.parent as? JSLiteralExpression)
                    ?.let { keyPrefixEdit(it, newName) }?.let(edits::add)
                val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) }
                if (literal != null) {
                    val reference = listOfNotNull(literal, literal.parent)
                        .firstNotNullOfOrNull { candidate -> candidate.references.filterIsInstance<I18nReference>().firstOrNull() }
                    if (reference != null) {
                        if (reference.element.text.contains(DYNAMIC)) {
                            if (writesLevel(reference.element.text.unQuote())) dynamic += locationOf(reference.element)
                        } else if (reference.multiResolve(false).any { result -> result.element?.let(::underLevel) == true }) {
                            literalEdit(reference.element, newName, languages, parser)?.let(edits::add)
                        }
                    }
                }
                true
            },
            config.searchScope(project),
            searchWord,
            UsageSearchContext.ANY,
            true
        )
        return Found(edits.toList(), dynamic.toList())
    }

    /**
     * The word the code search looks for: the level's name, or its longest word when it holds
     * other characters — a flat key such as `home.title` is no word of the index.
     */
    private val searchWord: String get() = levelName.split(NON_WORD).maxByOrNull { it.length }?.takeIf { it.isNotEmpty() } ?: levelName

    private fun underLevel(element: PsiElement): Boolean = levelNodes.any { PsiTreeUtil.isAncestor(it, element, false) }

    /** True when a dynamic key's static text spells the level at its place: `button.${action}`. */
    private fun writesLevel(key: String): Boolean {
        val bare = if (config.nsSeparator.isNotEmpty() && key.contains(config.nsSeparator)) key.substringAfter(config.nsSeparator) else key
        val segments = separator?.let { bare.split(it) } ?: listOf(bare)
        return segments.size > path.size - 1 && segments.take(path.size) == path
    }

    /**
     * The edit rewriting [literal]'s segment at the level, or null when the level is not written in
     * it — under a key prefix going through the level, the prefix is what changes.
     */
    private fun literalEdit(literal: PsiElement, newName: String, languages: List<Lang>, parser: RawKeyParser): Edit? {
        val rawKey = languages.firstNotNullOfOrNull { it.extractRawKey(literal) } ?: return null
        val fullKey = parser.parse(rawKey, literal) ?: return null
        val prefixSize = fullKey.keyPrefix.size
        val levelIndex = path.size - 1
        val fullPath = fullKey.keyPrefix.map { it.text } + fullKey.compositeKey.map { it.text }
        if (fullPath.size <= levelIndex || fullPath.take(path.size) != path || levelIndex < prefixSize) return null

        val text = literal.text
        val quoted = text.length > 1 && text.first() in QUOTES && text.last() == text.first()
        val key = if (quoted) text.substring(1, text.length - 1) else text
        val nsPart = if (fullKey.ns != null && config.nsSeparator.isNotEmpty() && key.contains(config.nsSeparator)) key.substringBefore(config.nsSeparator) + config.nsSeparator else ""
        val written = key.removePrefix(nsPart)
        val segments = (separator?.let { written.split(it) } ?: listOf(written)).toMutableList()
        val writtenIndex = levelIndex - prefixSize
        if (segments.getOrNull(writtenIndex) != levelName) return null
        segments[writtenIndex] = newName
        val renamed = nsPart + segments.joinToString(separator ?: "")
        val document = documentOf(literal) ?: return null
        return Edit(document, literal.textRange, if (quoted) "${text.first()}$renamed${text.last()}" else renamed)
    }

    /**
     * The edit rewriting a react-i18next `keyPrefix` going through the level — `{ keyPrefix: 'button' }`
     * or `'button.primary'` — in a hook of one of [namespaces]; null for any other literal.
     */
    private fun keyPrefixEdit(literal: PsiElement, newName: String): Edit? {
        val string = literal as? JSLiteralExpression ?: return null
        if (!string.isQuotedLiteral) return null
        val property = string.parent as? JSProperty ?: return null
        if (property.name != KEY_PREFIX) return null
        val call = (property.parent as? JSObjectLiteralExpression)?.parent?.parent as? JSCallExpression ?: return null
        if (call.methodExpression?.text != USE_TRANSLATION) return null
        val hookNamespace = when (val first = call.arguments.firstOrNull()) {
            is JSLiteralExpression -> first.stringValue
            is JSArrayLiteralExpression -> (first.expressions.firstOrNull() as? JSLiteralExpression)?.stringValue
            else -> null
        }
        val inNamespace = if (hookNamespace == null) namespaces.any { it in config.defaultNamespaces() } else hookNamespace in namespaces
        if (!inNamespace) return null
        val prefix = string.stringValue ?: return null
        val segments = prefix.split(config.keySeparator).toMutableList()
        if (segments.size < path.size || segments.take(path.size) != path) return null
        segments[path.size - 1] = newName
        val text = string.text
        val document = documentOf(string) ?: return null
        return Edit(document, string.textRange, "${text.first()}${segments.joinToString(config.keySeparator)}${text.last()}")
    }

    /**
     * The edit rewriting the Paraglide member [member] (`m.button.save`'s key is `button.save`) when
     * its key goes through the level: `m.hello` → `m.greeting`, and to the index form `m["a.b"]`
     * when the renamed key is no identifier — or back to `m.key` when it becomes one.
     */
    private fun paraglideEdit(member: PsiElement, newName: String): Edit? {
        val key = PARAGLIDE_MESSAGE.extract(member).keyElements.singleOrNull()?.text ?: return null
        val segments = if (config.keySeparator.isEmpty()) mutableListOf(key) else key.split(config.keySeparator).toMutableList()
        if (segments.size < path.size || segments.take(path.size) != path) return null
        segments[path.size - 1] = newName
        val renamed = segments.joinToString(config.keySeparator)
        val qualifier = when (member) {
            is JSReferenceExpression -> member.qualifier
            is JSIndexedPropertyAccessExpression -> member.qualifier
            else -> null
        }?.text ?: return null
        val text = if (IDENTIFIER.matches(renamed)) "$qualifier.$renamed" else "$qualifier[\"$renamed\"]"
        val document = documentOf(member) ?: return null
        return Edit(document, member.textRange, text)
    }

    private fun documentOf(element: PsiElement): Document? =
        element.containingFile?.let { PsiDocumentManager.getInstance(element.project).getDocument(it) }

    private fun locationOf(element: PsiElement): String {
        val file = element.containingFile
        val line = documentOf(element)?.getLineNumber(element.textRange.startOffset)?.plus(1)
        return if (line != null) "${file.name}:$line" else file.name
    }

    private object Resolver : CompositeKeyResolver<PsiElement>

    companion object {
        private val QUOTES = setOf('"', '\'', '`')
        private const val DYNAMIC = "\${"
        private const val KEY_PREFIX = "keyPrefix"
        private const val USE_TRANSLATION = "useTranslation"
        private val NON_WORD = Regex("[^A-Za-z0-9_$]+")
        private const val PARAGLIDE = "paraglide"
        private val PARAGLIDE_MESSAGE = ParaglideMessageExtractor()
        private val IDENTIFIER = Regex("[A-Za-z_$][\\w$]*")

        /** The Paraglide member at or just above [element] — its name, its index literal — or null. */
        private fun paraglideMemberAt(element: PsiElement): PsiElement? =
            generateSequence(element) { it.parent }.take(3).firstOrNull(PARAGLIDE_MESSAGE::canExtract)
                ?.takeIf { ModulePresets.allows(ModulePresets.presetOf(it), PARAGLIDE) }

        /** The leaf the Paraglide member at [element] names, to rename; null off a member. */
        fun ofParaglideMember(element: PsiElement): KeyLevelRename? {
            val member = paraglideMemberAt(element) ?: return null
            val key = PARAGLIDE_MESSAGE.extract(member).keyElements.singleOrNull()?.text ?: return null
            val config = Settings.getInstance(member.project).config()
            val path = if (config.keySeparator.isEmpty()) listOf(key) else key.split(config.keySeparator)
            // A message file has no namespace: an empty list lets the lookup reach every message file.
            return KeyLevelRename(member.project, emptyList(), path, config, member)
        }

        /** Whether [file] belongs to a Paraglide project: its module's preset says so, or an inlang project exists. */
        fun isParaglideProject(file: PsiFile): Boolean =
            ModulePresets.presetOf(file) == PARAGLIDE || ParaglideTechnology.projects(file.project).isNotEmpty()

        /**
         * The level [property] stands for — a key-value of a translation file, holding other keys or
         * a value — in that file's namespace, or null when the file is no translation source.
         * [plural] names the group of a plural form instead: `itemCount` for `itemCount_one`.
         */
        fun inTranslationFile(property: PsiElement, plural: Boolean = false): KeyLevelRename? {
            val file = property.containingFile ?: return null
            val source = TranslationFileScope.sourceOf(file) ?: return null
            val config = Settings.getInstance(file.project).config()
            val path = TranslationFileKeys.pathOf(property).takeIf { it.isNotEmpty() }
                ?.let { if (plural) it.dropLast(1) + PluralKey.stripSuffix(it.last(), config.pluralSeparator) else it } ?: return null
            // A Paraglide message file is named after its locale, not a namespace: every locale's file must follow.
            if (isParaglideProject(file)) return KeyLevelRename(file.project, emptyList(), path, config, file)
            val namespace = TranslationDataLoader.extractNamespace(source, config.defaultNamespaces().first())
            return KeyLevelRename(file.project, listOf(namespace), path, config, file)
        }
    }
}
