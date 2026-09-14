package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement

/**
 * The framework a module's `preset` selects for the code files under its root directory.
 *
 * Every technology's function names used to apply to every file: in a monorepo holding a Vue app
 * and a React app, `t('x')` in the Vue app was claimed by i18next and reported as an unresolved key.
 * A module whose preset names a framework now recognises that framework's calls only.
 */
object ModulePresets {

    /** The preset of the module holding [element]'s file, or null when none applies (no module, empty preset). */
    fun presetOf(element: PsiElement): String? {
        val project = element.project
        val modules = Settings.getInstance(project).config().modules
        if (modules.none { it.preset.isNotBlank() }) return null
        // The host file, for a fragment injected into a Vue or Svelte component.
        val file = (InjectedLanguageManager.getInstance(project).getTopLevelFile(element) ?: element.containingFile)
            ?.originalFile?.virtualFile ?: return null
        return ModuleSources.owner(modules, ModuleSources.FilePath.of(file, project.basePath ?: ""))
            ?.preset?.trim()?.ifEmpty { null }
    }

    /** Whether [frameworkId]'s calls are recognised under [preset]; everything is when there is none. */
    fun allows(preset: String?, frameworkId: String?): Boolean = preset == null || frameworkId == preset

    /** [names] restricted to those the technology [preset] selects publishes; all of them without a preset. */
    fun restrict(names: List<String>, preset: String?): List<String> {
        if (preset == null) return names
        val published = Extensions.TECHNOLOGY.extensionList
            .filter { it.frameworkId() == preset }
            .flatMapTo(mutableSetOf()) { it.translationFunctionNames() }
        return names.filter { it in published }
    }
}
