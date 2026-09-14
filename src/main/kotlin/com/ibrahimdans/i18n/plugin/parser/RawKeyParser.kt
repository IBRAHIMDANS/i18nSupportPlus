package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.parser.KeyParserBuilder
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
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
        return when {
            syntax == KeySyntax.NoNamespace -> parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = false)
            firstComponent -> parser.parse(rawKey, emptyNamespace = true, firstComponentNamespace = true)
            syntax != null -> parser.parse(rawKey)
            else -> parser.parse(rawKey, false, config.firstComponentNs)
        }
    }

    /** The key syntax of the module holding [caller]'s file, or null to use the project settings. */
    private fun keySyntaxOf(config: Config, caller: PsiElement): KeySyntax? {
        if (config.modules.isEmpty()) return null
        val file = caller.containingFile?.originalFile?.virtualFile ?: return null
        val module = ModuleSources.owner(config.modules, ModuleSources.FilePath.of(file, project.basePath ?: "")) ?: return null
        return KeyTemplate.parse(module.keyTemplate)
    }
}
