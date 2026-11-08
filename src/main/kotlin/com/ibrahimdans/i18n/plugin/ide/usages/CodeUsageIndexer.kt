package com.ibrahimdans.i18n.plugin.ide.usages

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.extensions.technology.paraglide.ParaglideOutdir
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationKeyUsages
import com.ibrahimdans.i18n.plugin.ide.references.translation.LinkedMessages
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.usages.CallSiteIndex
import com.ibrahimdans.i18n.plugin.usages.DynamicReach
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.impl.source.tree.LeafPsiElement

/** One place a code file names a key, filed under the key path it writes; [host] is the file's path. */
internal sealed interface CodeSite : CallSiteIndex.Site {
    val host: String
}

/**
 * A literal whose text may name a key: what the word search of `IndirectKeyUsages.textUsages` matches,
 * read once. [path] is the text after its namespace, split at the key separator; [namespace] the one
 * written before it; [declared] the namespaces of the hook the call sits under when it writes none.
 * An [identifier] is a Paraglide or typesafe-i18n member name, counted only in a project whose code
 * names keys that way.
 */
internal class TextSite(
    override val path: List<String>,
    val written: String,
    val namespace: String?,
    val declared: List<String>?,
    val identifier: Boolean,
    val usage: KeyUsage,
    override val host: String,
) : CodeSite

/**
 * A call reading its key through what it does not write: a hook's key prefix, a key scope, an i18next
 * context — what `IndirectKeyUsages.prefixedUsages` matches. [path] is the key resolved, prefix applied;
 * [scope] the single namespace of its hook when the project declares it a key scope.
 */
internal class PrefixedSite(
    override val path: List<String>,
    val scope: String?,
    val keyPrefixed: Boolean,
    val namespaces: List<String>,
    val context: ContextOption?,
    val usage: KeyUsage,
    override val host: String,
) : CodeSite

/** A key literal built at runtime, by its [text] unquoted and the [shape] of the keys it may produce. */
internal class DynamicSite(
    val text: String,
    val shape: DynamicReach.Shape,
    override val host: String,
) : CodeSite {
    override val path: List<String> get() = emptyList()
}

/** A translation value linking to [key] — vue-i18n's `@:key`, i18next's `$t(key)` — whose [text] names it. */
internal class LinkedSite(val key: String, val text: String, val usage: KeyUsage, val file: VirtualFile)

/**
 * Reads the key usages of one file from its PSI, the way the usage searches read the elements they
 * find: every element of the file and of the languages injected into it, once, whatever key is asked
 * later. Settings-dependent: an indexer reads with the [config] and the [keyScopes] it was made with.
 */
internal class CodeUsageIndexer(private val project: Project, private val config: Config, private val keyScopes: Set<String>) {

    private val languages = Extensions.LANG.extensionList
    private val parser = RawKeyParser(project)

    /** The sites of [file], read in the caller's read action. */
    fun sitesOf(file: PsiFile): List<CodeSite> {
        val host = file.viewProvider.virtualFile.path
        val sites = mutableListOf<CodeSite>()
        val literals = HashSet<PsiElement>()
        val injected = HashSet<PsiFile>()
        val injection = InjectedLanguageManager.getInstance(project)

        fun walk(root: PsiElement) {
            // Paraglide's generated code names every message: never a use (see ParaglideOutdir).
            val generated = root.containingFile?.virtualFile?.let(ParaglideOutdir::contains) == true
            root.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    read(element, generated, host, literals, sites)
                    if (element is PsiLanguageInjectionHost) {
                        // A Blade fragment spans several hosts: walked once.
                        injection.enumerate(element) { fragment, _ -> if (injected.add(fragment)) walk(fragment) }
                    }
                    super.visitElement(element)
                }
            })
        }
        file.viewProvider.allFiles.forEach(::walk)
        return sites
    }

    private fun read(element: PsiElement, generated: Boolean, host: String, literals: MutableSet<PsiElement>, sites: MutableList<CodeSite>) {
        // An Angular marker names its key inside metadata (`:@@home.title:Welcome`): its id is compared.
        val angularId = languages.firstNotNullOfOrNull { it.metadataKeyOf(element) }
        val literal = languages.firstNotNullOfOrNull { it.resolveLiteral(element) }
        if (angularId == null && literal == null) return
        // Read once when the element is its own literal, as a JS string is: the hook is resolved for both reads.
        val ownKey = lazy { rawKeyOf(element) }
        if (!generated) {
            textSite(element, angularId ?: element.text.unQuote(), angularId?.let { element } ?: literal!!, host, ownKey)?.let(sites::add)
        }
        if (literal != null && literals.add(literal)) {
            prefixedSite(literal, host, if (literal === element) ownKey.value else rawKeyOf(literal))?.let(sites::add)
            DynamicReach.shapeOf(literal.text.unQuote())?.let { sites += DynamicSite(literal.text.unQuote(), it, host) }
        }
    }

    private fun rawKeyOf(element: PsiElement): RawKey? = languages.firstNotNullOfOrNull { it.extractRawKey(element) }

    private fun textSite(entry: PsiElement, text: String, counted: PsiElement, host: String, rawKey: Lazy<RawKey?>): TextSite? {
        val ns = config.nsSeparator
        val namespaced = text.contains(ns)
        val written = if (namespaced) text.substringAfter(ns) else text
        if (written.isEmpty()) return null
        val usage = usageOf(counted) ?: return null
        val declared = if (namespaced) null else rawKey.value?.arguments?.ifEmpty { null }
        val identifier = entry is LeafPsiElement && entry.node.elementType.toString().endsWith("IDENTIFIER")
        return TextSite(pathOf(written, config), written, if (namespaced) text.substringBefore(ns) else null, declared, identifier, usage, host)
    }

    /**
     * Parsed only when the call reads through what it does not write — a key prefix, a context, a hook
     * naming a key scope: the parse is most of what reading a file costs, and every other call is
     * the text scan's.
     */
    private fun prefixedSite(literal: PsiElement, host: String, rawKey: RawKey?): PrefixedSite? {
        if (rawKey == null) return null
        if (rawKey.keyPrefix.isEmpty() && rawKey.context == null && rawKey.arguments.singleOrNull() !in keyScopes) return null
        val fullKey = parser.parse(rawKey) ?: return null
        val scope = fullKey.namespaces?.singleOrNull()?.takeIf { fullKey.ns == null && it in keyScopes }
        val keyPrefixed = fullKey.keyPrefix.isNotEmpty()
        if (!keyPrefixed && fullKey.context == null && scope == null) return null
        val usage = usageOf(literal) ?: return null
        return PrefixedSite(fullKey.compositeKey.map { it.text }, scope, keyPrefixed, fullKey.lookupNamespaces(), fullKey.context, usage, host)
    }

    /** The values of the translation file [file] linking to a key, each with the key it reaches. */
    fun linkedSitesOf(file: PsiFile): List<LinkedSite> {
        val text = file.viewProvider.contents
        if (!text.contains('@') && !text.contains("\$t(")) return emptyList()
        val sites = mutableListOf<LinkedSite>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val links = LinkedMessages.linksOf(element)
                if (links.isNotEmpty()) {
                    val usage = usageOf(element)
                    if (usage != null) {
                        // Its targets in every locale: `fr.json`'s link uses the key of `en.json` too.
                        links.flatMap { LinkedMessages.allTargetsOf(element, it.key) }
                            .map { TranslationKeyUsages.keyOf(it, config) }
                            .distinct()
                            .forEach { sites += LinkedSite(it, element.text, usage, file.viewProvider.virtualFile) }
                    }
                }
                super.visitElement(element)
            }
        })
        return sites
    }

    companion object {
        /** [written] split at the key separator: the path a call writing it names, the paths above it included. */
        fun pathOf(written: String, config: Config): List<String> =
            if (config.keySeparator.isEmpty()) listOf(written) else written.split(config.keySeparator)

        fun usageOf(element: PsiElement): KeyUsage? {
            val path = element.containingFile?.virtualFile?.path ?: return null
            val range = element.textRange ?: return null
            return KeyUsage(path, TextSpan(range.startOffset, range.endOffset))
        }
    }
}
