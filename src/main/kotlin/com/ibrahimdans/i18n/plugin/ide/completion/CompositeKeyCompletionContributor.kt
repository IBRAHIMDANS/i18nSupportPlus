package com.ibrahimdans.i18n.plugin.ide.completion

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.preview.PreviewLocaleSwitcher
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.parser.RawKeyParser
import com.ibrahimdans.i18n.plugin.tree.CompositeKeyResolver
import com.ibrahimdans.i18n.plugin.tree.ContextKey
import com.ibrahimdans.i18n.plugin.tree.ContextOption
import com.ibrahimdans.i18n.plugin.tree.PluralGroup
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocaleMatching
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.nullableToList
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.ibrahimdans.i18n.plugin.utils.displayValue
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement

/**
 * Completion of i18n key.
 *
 * Each entry shows the key's translation in the preview locale — the one inlay hints, hover and
 * the status-bar widget show — and can be found by that text: keys used to be listed bare
 * (`menu.home`, `menu.help`), so one picked a key blind or went to open the file, while what one
 * has in mind is the text ("Accueil"), not the key.
 */
abstract class CompositeKeyCompletionContributor(private val lang: Lang): CompletionContributor(), CompositeKeyResolver<PsiElement> {
    private val DUMMY_KEY = CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if(parameters.position.text.unQuote().substringAfter(DUMMY_KEY).trim().isNotBlank()) return
        // Case-insensitive so that `acc` finds a key whose translation reads "Accueil".
        val resultSet = result.caseInsensitive()
        val fullKey = lang.extractRawKey(parameters.position)?.let{RawKeyParser(parameters.position.project).parse(it, parameters.position)}
        if (fullKey == null) {
            if (lang.canExtractKey(parameters.position.parent, Extensions.TECHNOLOGY.extensionList.flatMap { it.translationFunctionNames() })) {
                resultSet.addAllElements(findCompletions("", emptyList(), emptyList(), parameters.position))
                resultSet.stopHere()
            }
        } else {
            resultSet.addAllElements(processKey(fullKey, parameters.position))
            resultSet.stopHere()
        }
    }

    /**
     * Groups plural forms under their base key, so that a plural is offered once.
     * Returns each offered key paired with the keys it stands for.
     */
    private fun groupPlurals(completions: List<String>, pluralSeparator: String): List<Pair<String, List<String>>> {
        return completions
            .groupBy { key ->
                if (PluralKey.groupBase(key) != null) PluralKey.stripSuffix(key, pluralSeparator)
                else key.substringBeforeLast(pluralSeparator)
            }
            .entries.flatMap { entry ->
                val numericForms = PluralKey.numericForms(entry.key, pluralSeparator)
                val isNumericPlural = entry.value.size == numericForms.size && entry.value.containsAll(numericForms)
                val isCldrPlural = entry.value.isNotEmpty() && entry.value.all { PluralKey.groupBase(it) != null }
                if (isNumericPlural || isCldrPlural) listOf(entry.key to entry.value)
                else entry.value.map { it to listOf(it) }
            }
    }

    private fun processKey(fullKey: FullKey, element: PsiElement): List<LookupElementBuilder> =
        fullKey.compositeKey.lastOrNull().nullableToList().flatMap { last ->
            val source = fullKey.source.replace(last.text, "")
            // allNamespaces, not ns: a key written without one works in the namespaces its hook
            // declares (`useTranslation('auth')`), exactly as the annotator resolves it. Asking for
            // the explicit namespace alone offered the default namespace's keys — or every file's.
            findCompletions(source, fullKey.lookupNamespaces(), fullKey.compositeKey.dropLast(1), element, fullKey.context)
        }

    /**
     * Groups the context variants a call passing i18next's `context` reads under their base key:
     * `t('fr', { context: g })` is offered `friend` once, not `friend_male` and `friend_female`,
     * which the call would read as `friend_male_male`. A fixed context groups its own variant only.
     * Not when the context separator is also the key separator: the names hold no variant then.
     */
    private fun groupContextVariants(
        completions: List<Pair<String, List<String>>>,
        context: ContextOption?,
        keySeparator: String,
    ): List<Pair<String, List<String>>> {
        if (context == null || keySeparator == ContextKey.SEPARATOR) return completions
        return completions
            .groupBy({ (key, _) -> ContextKey.split(key)?.takeIf { ContextKey.reads(context, it.second) }?.first ?: key }, { it.second })
            .map { (key, forms) -> key to forms.flatten() }
    }

    /**
     * Every key under [compositeKey], with its translation in the preview locale.
     *
     * Not filtered by the typed prefix here: the result set's matcher does it, on the key *and*
     * on the translation — a key-name filter at this level would rule out a search by text.
     *
     * The values come from the same tree walk as the keys — each key's node is looked up in the
     * node already resolved for the listing — so completion reads no file more than it did.
     */
    private fun findCompletions(
        source: String,
        namespaces: List<String>,
        compositeKey: List<Literal>,
        element: PsiElement,
        context: ContextOption? = null,
    ): List<LookupElementBuilder> {
        val config = Settings.getInstance(element.project).config()
        val sources = element.project.service<LocalizationSourceService>().findSources(namespaces, element)
        // Matched through LocaleMatching like inlay hints and hover, so `en` finds `en-GB`.
        val previewLocale = LocaleMatching.pick(PreviewLocaleSwitcher.effective(config), sources.map { it.localeLabel() })
        val values = mutableMapOf<String, String>()
        val keys = sources.flatMap { localizationSource ->
            keysWithValues(compositeKey, localizationSource, localizationSource.localeLabel() == previewLocale, values)
        }
        return groupContextVariants(groupPlurals(keys, config.pluralSeparator), context, config.keySeparator).map { (key, forms) ->
            val value = (pluralFormOrder(key, config.pluralSeparator) + forms).firstNotNullOfOrNull { values[it] }
            val lookup = LookupElementBuilder.create(source + key)
            if (value == null) lookup
            else {
                // The whole value on one line: the type text may be cut, the search is not.
                val searchable = displayValue(value, Int.MAX_VALUE)
                lookup
                    .withTypeText(displayValue(value), true)
                    // The matcher's prefix is everything typed in the string, `menu.acc` included:
                    // the value alone matches at the root, behind the key's own path below it.
                    .withLookupStrings(listOf(searchable, source + searchable).distinct())
            }
        }
    }

    /**
     * The key names under [compositeKey] in [localizationSource]. When [inPreviewLocale], their
     * displayable values are recorded in [values]; a key without one (an object, another locale)
     * records nothing, so it is shown bare rather than with another locale's text.
     */
    private fun keysWithValues(
        compositeKey: List<Literal>,
        localizationSource: LocalizationSource,
        inPreviewLocale: Boolean,
        values: MutableMap<String, String>
    ): List<String> {
        val parent = resolveCompositeKeyProperty(compositeKey, localizationSource) ?: return emptyList()
        val names = parent.findChildren("").map { it.value().text.unQuote() }
        if (!inPreviewLocale) return names
        // One walk over the level where the format offers it: findChild per name rescans the
        // children each time, which is quadratic on a flat file of a few thousand keys.
        val nodeOf: (String) -> Tree<PsiElement>? = parent.entries()
            // First occurrence wins on a duplicated key, as findChild would answer.
            ?.let { entries -> buildMap { entries.forEach { (name, node) -> putIfAbsent(name, node) } } }
            ?.let { it::get }
            ?: parent::findChild
        names.forEach { name ->
            PluralGroup.displayableValue(nodeOf(name))
                ?.value()?.text?.unQuote()
                ?.let { values.putIfAbsent(name, it) }
        }
        return names
    }

    /**
     * The forms a plural key is tried in, in the resolver's order: the first one holding a value
     * is the one inlay hints show, so completion shows the same.
     */
    private fun pluralFormOrder(key: String, pluralSeparator: String): List<String> =
        listOf(key) + PluralKey.numericForms(key, pluralSeparator) + PluralKey.cldrForms(key)
}
