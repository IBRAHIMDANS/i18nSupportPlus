package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.key.parser.KeyParser
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import com.ibrahimdans.i18n.plugin.utils.KeyElementType
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class RawKeyParser(private val project: Project) {

    /**
     * Parses [rawKey] with the project's separators — or, for a key written in [caller]'s file inside
     * a module declaring a `keyTemplate`, with the syntax that template states: `{ns}.{key}` reads
     * `common.title` as namespace `common`, key `title`; `{key}` reads no namespace at all.
     *
     * Flat keys (react-intl) are never split, whatever the template says.
     */
    fun parse(rawKey: RawKey, caller: PsiElement? = null): FullKey? {
        val config = Settings.getInstance(project).config()
        val flatKeys = config.usesFlatKeys()
        if (flatKeys) return KeyParserBuilder.withoutTokenizer().build().parse(rawKey, true, config.firstComponentNs)

        val syntax = caller?.let { keySyntaxOf(config, it) }
        val templateSeparator = (syntax as? KeySyntax.Namespaced)?.nsSeparator
        // A namespace separator equal to the key separator cannot be told apart by the tokenizer:
        // the first key segment is then the namespace, which is what firstComponentNamespace reads.
        val firstComponent = templateSeparator != null && templateSeparator == config.keySeparator
        val nsSeparator = templateSeparator?.takeUnless { firstComponent } ?: config.nsSeparator
        val parser = KeyParserBuilder
            .withSeparators(nsSeparator, config.keySeparator)
            .withDummyNormalizer()
            .withTemplateNormalizer()
            .build()
        val packaged = if (syntax == KeySyntax.NoNamespace) null else packageScoped(rawKey, parser, config.keySeparator)
        val key = when {
            packaged != null -> packaged
            syntax == KeySyntax.NoNamespace -> parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = false)
            firstComponent -> parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = true)
            syntax != null -> parser.parse(rawKey)
            config.firstComponentNs -> parser.parse(rawKey, false, true)
            else -> parser.parse(rawKey)?.let { key -> scoped(key, rawKey, parser) ?: key }
        }
        return key?.let { withFallbacks(it, config) }
    }

    /**
     * [key] with i18next's `fallbackNS` attached, when its namespaces come from its hook: under
     * `useTranslation('admin')`, `t('shared.ok')` is read from `common` too once `admin` lacks it.
     * A key writing its own namespace, or holding none, is left as it is.
     */
    private fun withFallbacks(key: FullKey, config: Config): FullKey {
        if (key.ns != null || key.namespaces.isNullOrEmpty()) return key
        val fallbacks = FallbackNamespaces.of(project, config)
        return if (fallbacks.isEmpty()) key else key.copy(fallbackNamespaces = fallbacks)
    }

    /**
     * [key] read again with its first segment as the namespace, when that segment names a scope a
     * technology declares ([com.ibrahimdans.i18n.Technology.keyScopes]): Transloco writes the key
     * `title` of the scope `admin` as `admin.title`. Null for any other key, which keeps its reading.
     */
    private fun scoped(key: FullKey, rawKey: RawKey, parser: KeyParser): FullKey? {
        if (key.ns != null || key.keyPrefix.isNotEmpty() || key.compositeKey.size < 2) return null
        val first = key.compositeKey.first().text
        if (Extensions.TECHNOLOGY.extensionList.none { first in it.keyScopes(project) }) return null
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
    private fun packageScoped(rawKey: RawKey, parser: KeyParser, keySeparator: String): FullKey? {
        if (rawKey.keyPrefix.isNotEmpty() || keySeparator.isEmpty()) return null
        val first = rawKey.keyElements.firstOrNull()?.takeIf { it.type == KeyElementType.LITERAL } ?: return null
        if (!first.text.contains(PACKAGE_SEPARATOR)) return null
        val scope = Extensions.TECHNOLOGY.extensionList.asSequence()
            .flatMap { it.keyScopes(project) }
            .filter { PACKAGE_SEPARATOR in it && first.text.startsWith(it + keySeparator) }
            .maxByOrNull { it.length } ?: return null
        val rest = KeyElement.literal(first.text.substring(scope.length + keySeparator.length))
        val key = parser.parse(rawKey.copy(keyElements = listOf(rest) + rawKey.keyElements.drop(1)), emptyNamespace = true)
            ?: return null
        return key.copy(source = scope + keySeparator + key.source, ns = Literal(scope))
    }

    /** The key syntax of the module holding [caller]'s file, or null to use the project settings. */
    private fun keySyntaxOf(config: Config, caller: PsiElement): KeySyntax? {
        if (config.modules.isEmpty()) return null
        // The host file: a key written in a `{{ … }}` interpolation sits in an injected fragment
        // owned by no module root, and used to be read with the project's separators instead of
        // the module's template — every dotted key written in a template was reported unresolved.
        val file = caller.hostVirtualFile() ?: return null
        val module = ModuleSources.owner(config.modules, ModuleSources.FilePath.of(file, project.basePath ?: "")) ?: return null
        return KeyTemplate.parse(module.keyTemplate)
    }

    private companion object {
        /** Laravel's separator between a package and its file: `courier::messages.welcome`. */
        const val PACKAGE_SEPARATOR = "::"
    }
}
