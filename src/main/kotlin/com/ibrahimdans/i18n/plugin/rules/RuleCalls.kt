package com.ibrahimdans.i18n.plugin.rules

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * What the configured *Key assistance rules* decide about the call holding a key: the bridge
 * between a language integration's PSI and [KeyRules], which knows none.
 */
object RuleCalls {

    /**
     * The decision for the key [element], passed to a call to [callee] in [language].
     *
     * [imports] reads the module specifiers of the file; it is only asked when a rule constrains
     * on imports. With no rule configured this is a single settings read.
     */
    fun decide(element: PsiElement, language: String, callee: String, imports: (PsiFile) -> Set<String>): RuleDecision {
        val rules = Settings.getInstance(element.project).config().rules
        if (rules.isEmpty() || rules.none { it.trigger.trim() == callee }) return RuleDecision.NONE
        // The host file, for a fragment injected into a Vue or Svelte component.
        val file = InjectedLanguageManager.getInstance(element.project).getTopLevelFile(element) ?: element.containingFile
        val context = RuleContext(
            language = language,
            callee = callee,
            filePath = file?.let { projectPath(it) } ?: "",
            imports = if (file != null && rules.any { it.constraintType == KeyRules.Constraint.IMPORT }) imports(file) else emptySet(),
            key = element.text.unQuote(),
        )
        return KeyRules.decide(rules, context)
    }

    /** [file]'s path relative to the project directory when it lives under it, its full path otherwise. */
    private fun projectPath(file: PsiFile): String {
        val path = file.originalFile.virtualFile?.path ?: return file.name
        val basePath = file.project.basePath
        return if (basePath != null && path.startsWith("$basePath/")) path.removePrefix("$basePath/") else path.trimStart('/')
    }
}
