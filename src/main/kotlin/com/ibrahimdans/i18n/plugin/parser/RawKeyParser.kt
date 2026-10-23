package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.utils.FallbackNamespaces
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/** Reads keys of [project]: gathers their [KeyContext] from the IDE, and leaves the reading to [KeyResolver]. */
class RawKeyParser(private val project: Project) {

    /**
     * Parses [rawKey] with the project's settings, and with the key syntax of the module holding
     * [caller]'s file when that module declares a `keyTemplate` (see [KeyResolver.parse]).
     */
    fun parse(rawKey: RawKey, caller: PsiElement? = null): FullKey? {
        val config = Settings.getInstance(project).config()
        val context = KeyContext(
            config = config,
            keyScopes = { Extensions.TECHNOLOGY.extensionList.flatMapTo(mutableSetOf()) { it.keyScopes(project) } },
            fallbackNamespaces = { FallbackNamespaces.of(project, config) },
            callerSyntax = caller?.takeUnless { config.usesFlatKeys() }?.let { keySyntaxOf(config, it) }
        )
        return KeyResolver.parse(rawKey, context)
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
}
