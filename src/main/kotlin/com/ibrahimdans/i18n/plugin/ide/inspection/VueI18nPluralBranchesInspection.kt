package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.extensions.technology.vuei18n.VueI18nPlurals
import com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Flags, in a vue-i18n locale file, a plural value with fewer `|` branches than its language has
 * forms: `ru` writing `{n} предмет | {n} предметов`, where Russian needs `one`, `few` and `many`
 * ([VueI18nPlurals.formsOf]). Only branches missing are reported, never one too many.
 *
 * `|` is a plural in vue-i18n only: a file is read when its module's preset is vue-i18n, or, in a
 * project without modules, when the `package.json` at its root depends on vue-i18n. Elsewhere a
 * pipe is text (`Home | Docs`).
 */
class VueI18nPluralBranchesInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nVuePluralBranches"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val source = TranslationFileScope.sourceOf(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!readsVueI18n(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        val locale = source.localeLabel()
        val forms = VueI18nPlurals.formsOf(locale)

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                val branches = VueI18nPlurals.branchesOf(entry.text ?: return).size
                if (branches == 0 || branches >= forms.size) return
                holder.registerProblem(
                    entry.keyElement,
                    PluginBundle.message("inspection.vue.plural.message", TranslationFileKeys.pathOf(entry.keyElement).joinToString("."), branches, locale, forms.size, forms.joinToString(", "))
                )
            }
        }
    }

    /** Whether [file] belongs to vue-i18n: its module's preset, or the project's `package.json` without one. */
    private fun readsVueI18n(file: PsiFile): Boolean {
        ModulePresets.presetOf(file)?.let { return it == VUE_I18N }
        val manifest = file.project.guessProjectDir()?.findChild("package.json") ?: return false
        return VUE_I18N in FrameworkDetector.detect(String(manifest.contentsToByteArray(), manifest.charset))
    }

    private companion object {
        const val VUE_I18N = "vue-i18n"
    }
}
