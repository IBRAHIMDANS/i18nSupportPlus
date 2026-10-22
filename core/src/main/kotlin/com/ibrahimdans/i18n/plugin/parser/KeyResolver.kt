package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.key.parser.KeyParser
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.KeyElementType

/**
 * Reads a [RawKey] into a [FullKey] from what its [KeyContext] states. Pure, so every reading is
 * testable without a project; [RawKeyParser] gathers the context from the IDE.
 */
object KeyResolver {

    /** The completion identifier the IDE inserts at the caret: `CompletionInitializationContext.DUMMY_IDENTIFIER`. */
    const val DUMMY_IDENTIFIER = "IntellijIdeaRulezzz "

    /** `CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED`. */
    const val DUMMY_IDENTIFIER_TRIMMED = "IntellijIdeaRulezzz"

    /** Laravel's separator between a package and its file: `courier::messages.welcome`. */
    private const val PACKAGE_SEPARATOR = "::"

    /**
     * Parses [rawKey] with the project's separators — or, for a key written in a module declaring
     * a `keyTemplate` ([KeyContext.callerSyntax]), with the syntax that template states: `{ns}.{key}`
     * reads `common.title` as namespace `common`, key `title`; `{key}` reads no namespace at all.
     *
     * Flat keys (react-intl) are never split, whatever the template says.
     */
    fun parse(rawKey: RawKey, context: KeyContext): FullKey? {
        val config = context.config
        if (config.usesFlatKeys()) return KeyParserBuilder.withoutTokenizer().build().parse(rawKey, true, config.firstComponentNs)
            ?.let { flatPrefixed(it, config.keySeparator) }

        val syntax = context.callerSyntax
        val templateSeparator = (syntax as? KeySyntax.Namespaced)?.nsSeparator
        // A namespace separator equal to the key separator cannot be told apart by the tokenizer:
        // the first key segment is then the namespace, which is what firstComponentNamespace reads.
        val firstComponent = templateSeparator != null && templateSeparator == config.keySeparator
        val nsSeparator = templateSeparator?.takeUnless { firstComponent } ?: config.nsSeparator
        val parser = KeyParserBuilder
            .withSeparators(nsSeparator, config.keySeparator)
            .withDummyNormalizer(DUMMY_IDENTIFIER, DUMMY_IDENTIFIER_TRIMMED)
            .withTemplateNormalizer()
            .build()
        val packaged = if (syntax == KeySyntax.NoNamespace) null else packageScoped(rawKey, parser, config.keySeparator, context)
        val key = when {
            packaged != null -> packaged
            syntax == KeySyntax.NoNamespace -> parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = false)
            firstComponent -> parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = true)
            syntax != null -> parser.parse(rawKey)
            config.firstComponentNs -> parser.parse(rawKey, false, true)
            else -> parser.parse(rawKey)?.let { key -> scoped(key, rawKey, parser, context) ?: key }
        }
        return key?.let { withFallbacks(it, context) }
    }

    /**
     * [key] with its hook's prefix joined to it into one flat key: under next-intl's
     * `useTranslations('OverviewActions')` or react-i18next's `keyPrefix`, `t('help.caption')` names
     * the property `OverviewActions.help.caption` of a flat file. The prefix led the composite key
     * as a segment of its own, so the key was looked up as `help.caption` nested in an
     * `OverviewActions` object, which a flat file never holds.
     */
    private fun flatPrefixed(key: FullKey, keySeparator: String): FullKey {
        if (key.keyPrefix.isEmpty() || keySeparator.isEmpty()) return key
        return key.copy(compositeKey = listOf(Literal(key.compositeKey.joinToString(keySeparator) { it.text })))
    }

    /**
     * [key] with i18next's `fallbackNS` attached, when its namespaces come from its hook: under
     * `useTranslation('admin')`, `t('shared.ok')` is read from `common` too once `admin` lacks it.
     * A key writing its own namespace, or holding none, is left as it is.
     */
    private fun withFallbacks(key: FullKey, context: KeyContext): FullKey {
        if (key.ns != null || key.namespaces.isNullOrEmpty()) return key
        val fallbacks = context.fallbackNamespaces
        return if (fallbacks.isEmpty()) key else key.copy(fallbackNamespaces = fallbacks)
    }

    /**
     * [key] read again with its first segment as the namespace, when that segment names a scope a
     * technology declares ([KeyContext.keyScopes]): Transloco writes the key `title` of the scope
     * `admin` as `admin.title`. Null for any other key, which keeps its reading.
     */
    private fun scoped(key: FullKey, rawKey: RawKey, parser: KeyParser, context: KeyContext): FullKey? {
        if (key.ns != null || key.keyPrefix.isNotEmpty() || key.compositeKey.size < 2) return null
        if (key.compositeKey.first().text !in context.keyScopes) return null
        return parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = true)
    }

    /**
     * [rawKey] read with a package scope as its namespace, when its text starts with one followed by
     * the key separator: Laravel writes the key `welcome` of a package's `messages` file
     * `courier::messages.welcome`, and declares `courier::messages` a key scope. The tokenizer splits
     * on every character of the namespace separator, so `::` cannot be read by it: the scope is cut
     * off first, the rest is parsed as a key without namespace.
     *
     * Asked only of a key holding `::` — which no other syntax parses — and answered only for a
     * scope a technology declares, so any other key keeps its reading.
     */
    private fun packageScoped(rawKey: RawKey, parser: KeyParser, keySeparator: String, context: KeyContext): FullKey? {
        if (rawKey.keyPrefix.isNotEmpty() || keySeparator.isEmpty()) return null
        val first = rawKey.keyElements.firstOrNull()?.takeIf { it.type == KeyElementType.LITERAL } ?: return null
        if (!first.text.contains(PACKAGE_SEPARATOR)) return null
        val scope = context.keyScopes
            .filter { PACKAGE_SEPARATOR in it && first.text.startsWith(it + keySeparator) }
            .maxByOrNull { it.length } ?: return null
        val rest = KeyElement.literal(first.text.substring(scope.length + keySeparator.length))
        val key = parser.parse(rawKey.copy(keyElements = listOf(rest) + rawKey.keyElements.drop(1)), emptyNamespace = true)
            ?: return null
        return key.copy(source = scope + keySeparator + key.source, ns = Literal(scope))
    }
}
