package com.ibrahimdans.i18n.plugin.ide.references.translation

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PatternCondition
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext

/**
 * Keys named inside a translation value: vue-i18n's linked messages, `"Click @:common.save"`, and
 * i18next's nesting, `"$t(common.save) and close"`, render the message of `common.save` in their
 * place. The named key gets a reference to its translation — Ctrl+click, Find Usages, completion —
 * and [LinkedMessageAnnotator] reports a key no translation file declares.
 *
 * Registered for JSON in `plugin.xml` and for YAML in `ymlConfig.xml`; nothing here names a YAML
 * class ([TranslationPsi] tells a YAML value by its language).
 */
class LinkedMessageReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("linkedMessageValue") {
                override fun accepts(element: PsiElement, context: ProcessingContext?) = LinkedMessages.isTranslationValue(element)
            }),
            object : PsiReferenceProvider() {
                // An unknown key keeps its reference, soft: completion reads its variants.
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
                    LinkedMessages.linksOf(element).map { link ->
                        LinkedMessageReference(element, link.range, link.key, LinkedMessages.targetsOf(element, link.key))
                    }.toTypedArray()
            }
        )
    }
}

/**
 * A linked key's reference, resolving to the translation it names in the locale of the file
 * holding the link — vue-i18n and i18next read it in the locale being rendered — or in every
 * locale declaring it when that one does not.
 *
 * It also stands for the key-value holding its target, so that Find Usages on the key — which
 * searches the key-value, not its value — lists the link.
 */
class LinkedMessageReference(
    element: PsiElement,
    range: TextRange,
    val linkedKey: String,
    private val targets: List<PsiElement>,
) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {

    override fun resolve(): PsiElement? = targets.singleOrNull()

    /** An unknown key is the annotator's to report, not the platform's. */
    override fun isSoft(): Boolean = targets.isEmpty()

    /** The keys the one being typed may complete to, one level at a time, as in code. */
    override fun getVariants(): Array<Any> = LinkedMessages.completionsOf(element, linkedKey).toTypedArray()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        targets.map(::PsiElementResolveResult).toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean {
        val manager = element.manager
        return targets.any { target ->
            manager.areElementsEquivalent(target, element) ||
                manager.areElementsEquivalent(TranslationPsi.propertyOf(target, strict = false), element)
        }
    }
}

/** Reports a linked key no translation file declares, as the code's unknown keys are. */
class LinkedMessageAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        for (link in LinkedMessages.linksOf(element)) {
            // `$t(key` with no `)` is text to i18next: it is completed while typed, not reported.
            if (!link.closed || LinkedMessages.targetsOf(element, link.key).isNotEmpty()) continue
            holder.newAnnotation(HighlightSeverity.ERROR, PluginBundle.getMessage("annotator.unresolved.key"))
                .range(link.range.shiftRight(element.textRange.startOffset))
                .create()
        }
    }
}

/**
 * A key named in a value: the key, where it sits in its value element, and whether it is
 * [closed] — an i18next `$t(key` still being typed has no `)` yet.
 */
data class LinkedMessage(val key: String, val range: TextRange, val closed: Boolean = true)

/**
 * Reading the keys a translation value names — vue-i18n's linked messages and i18next's nesting —
 * shared by the reference, the annotator and the usage count
 * ([com.ibrahimdans.i18n.plugin.ide.inspection.IndirectKeyUsages]).
 */
object LinkedMessages : CompositeKeyResolver<PsiElement> {

    /** The preset whose modules read `@:key`; a module without preset reads it too. */
    private const val VUE_I18N = "vue-i18n"

    /** The preset whose modules read `$t(key)`; a module without preset reads it too. */
    private const val I18NEXT = "i18next"

    /**
     * The syntax of vue-i18n v9+'s message compiler (`packages/message-compiler/src/tokenizer.ts`):
     * `@`, an optional `.modifier` (an identifier: `lower`, `upper`, `capitalize` or a custom one),
     * `:`, then the key. The key is either a literal interpolation, `{'key'}`, or every character up
     * to a space, `{`, `@`, `|`, `(`, `)` or the end — dots and `:` included, so a trailing dot is
     * part of the key, as vue-i18n reads it. `@:(key)` is vue-i18n v8's form, which v9 no longer
     * reads but which v8 projects still write. A `@:{name}` naming a parameter is dynamic: no key
     * to read. `\@` is an escaped `@`.
     */
    private val LINK = Regex("""(?<!\\)@(?:\.[A-Za-z_][A-Za-z0-9_$]*)?:(?:\{'([^']+)'}|\(([^()\s]+)\)|([^\s{@|()]+))""")

    /**
     * i18next's nesting with its default `nestingPrefix` and `nestingSuffix`, `$t(` and `)`: the
     * key, trimmed, up to the `,` that starts its options (`$t(key, { "count": 2 })`) or the `)`.
     * A `$t(` left open at the end of the value is a key being typed.
     */
    private val NESTING = Regex("""\${'$'}t\(\s*([^,()\s][^,()]*?)\s*(?:,[^)]*)?(\)|$)""")

    /** The keys [text] names, each with its range in [text]; [reads] tells the presets whose syntax applies. */
    fun parse(text: String, reads: (String) -> Boolean = { true }): List<LinkedMessage> {
        val links = if (!reads(VUE_I18N)) emptySequence() else LINK.findAll(text).mapNotNull { match ->
            val group = match.groups[1] ?: match.groups[2] ?: match.groups[3] ?: return@mapNotNull null
            LinkedMessage(group.value, TextRange(group.range.first, group.range.last + 1))
        }
        val nested = if (!reads(I18NEXT)) emptySequence() else NESTING.findAll(text).map { match ->
            val group = match.groups[1]!!
            LinkedMessage(group.value, TextRange(group.range.first, group.range.last + 1), closed = match.groupValues[2] == ")")
        }
        return (links + nested).sortedBy { it.range.startOffset }.toList()
    }

    /** True when [element] is the string value of a key of a JSON or YAML file. */
    fun isTranslationValue(element: PsiElement): Boolean = when {
        element is JsonStringLiteral -> !element.isPropertyName
        TranslationPsi.isYaml(element) ->
            TranslationPsi.propertyOf(element)?.let { TranslationPsi.entryOf(it)?.literal } == element
        else -> false
    }

    /**
     * The links of [element], their ranges relative to it; none outside a translation file. A
     * module whose preset names a framework reads that framework's syntax only — `@:` means nothing
     * to i18next, `$t(` nothing to vue-i18n.
     */
    fun linksOf(element: PsiElement): List<LinkedMessage> {
        if (!isTranslationValue(element)) return emptyList()
        val text = element.text
        if (!text.contains('@') && !text.contains("\$t(")) return emptyList()
        val preset = ModulePresets.presetOf(element)
        if (!ModulePresets.allows(preset, VUE_I18N) && !ModulePresets.allows(preset, I18NEXT)) return emptyList()
        val file = element.containingFile ?: return emptyList()
        if (TranslationFileScope.sourceOf(file) == null) return emptyList()
        val content = ElementManipulators.getValueTextRange(element)
        return parse(content.substring(text)) { ModulePresets.allows(preset, it) }
            .map { it.copy(range = it.range.shiftRight(content.startOffset)) }
    }

    /**
     * The translations [key], linked from [element], names: read like a `$t('key')` written in the
     * same module, the ones of [element]'s own locale first.
     */
    fun targetsOf(element: PsiElement, key: String): List<PsiElement> {
        val project = element.project
        val fullKey = RawKeyParser(project).parse(RawKey(listOf(KeyElement.literal(key))), element) ?: return emptyList()
        if (fullKey.compositeKey.isEmpty()) return emptyList()
        val pluralSeparator = Settings.getInstance(project).config().pluralSeparator
        val resolved = project.service<LocalizationSourceService>().findReadSources(fullKey, element)
            .flatMap { source -> resolve(fullKey, source, pluralSeparator) }
            .filter { it.unresolved.isEmpty() && it.path.isNotEmpty() && it.element != null }
        val locale = element.containingFile?.let(TranslationFileScope::sourceOf)?.localeLabel()
        val preferred = resolved.filter { it.localizationSource.localeLabel() == locale }.ifEmpty { resolved }
        return preferred.mapNotNull { reference ->
            val tree = reference.element!!
            if (tree.isTree()) TranslationPsi.propertyOf(tree.value(), strict = false) ?: tree.value() else tree.value()
        }.distinct()
    }

    /**
     * The keys [key], named from [element] and being typed, may complete to: the names under its
     * last complete level, behind that level's path (`common.` + `save`), from every locale.
     */
    fun completionsOf(element: PsiElement, key: String): List<String> {
        val fullKey = RawKeyParser(element.project).parse(RawKey(listOf(KeyElement.literal(key))), element) ?: return emptyList()
        val last = fullKey.compositeKey.lastOrNull()
        val parent = if (last == null) emptyList() else fullKey.compositeKey.dropLast(1)
        val path = if (last == null) key else key.removeSuffix(last.text)
        return element.project.service<LocalizationSourceService>().findSources(fullKey.lookupNamespaces(), element)
            .flatMap { source -> resolveCompositeKeyProperty(parent, source)?.findChildren("").orEmpty() }
            .map { path + it.value().text.unQuote() }
            .distinct()
    }
}
