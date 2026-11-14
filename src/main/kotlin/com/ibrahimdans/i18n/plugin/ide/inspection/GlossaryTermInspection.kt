package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.check.GlossaryCheck
import com.ibrahimdans.i18n.plugin.translate.GlossaryTerm
import com.ibrahimdans.i18n.plugin.translate.MachineTranslationSettings
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a translation that does not render a term of the project's glossary as the glossary says:
 * `Welcome to Acme` becoming `Bienvenue chez Akmé` when `Acme` is kept as written, or `Your cart`
 * becoming `Votre chariot` when the glossary translates `cart` to `panier` in French.
 *
 * The glossary is the one of *Machine Translation* settings, read whether machine translation is on or
 * not: a value typed by hand or written by an agent ought to follow it as an engine does. The rules are
 * [GlossaryCheck]'s. Off by default; without a glossary, it does nothing. The reference file itself is
 * never reported: [TranslationFileKeys.referenceTranslations] has no counterpart for it.
 */
class GlossaryTermInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nGlossaryTerm"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val terms = MachineTranslationSettings.getInstance(holder.project).state.glossary
        if (terms.none { it.term.isNotBlank() }) return PsiElementVisitor.EMPTY_VISITOR
        val source = TranslationFileScope.sourceOf(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val locale = source.localeLabel()
        val glossary = GlossaryTerm.glossary(terms)
        val refTranslations: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) { TranslationFileKeys.referenceTranslations(holder.file) }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                val literal = entry.literal ?: return
                val value = entry.text ?: return
                val reference = refTranslations[TranslationFileKeys.keyOf(literal)] ?: return
                GlossaryCheck.breaches(glossary, reference, value, locale).forEach { breach ->
                    val message = if (breach.kept) PluginBundle.message("inspection.glossary.message.kept", breach.term)
                    else PluginBundle.message("inspection.glossary.message.translated", breach.term, breach.expected, locale)
                    holder.registerProblem(literal, message)
                }
            }
        }
    }
}
