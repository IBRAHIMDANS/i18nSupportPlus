package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.check.ValueCheck
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a translation whose markup differs from the reference locale's: `Voir <1>les CGU</1>`
 * translated `See <1>the terms` breaks react-i18next's `<Trans>`, and a lost `<b>…</b>` breaks
 * react-intl's rich text. *Placeholder consistency* compares variables, not tags.
 *
 * Tags are compared as a multiset — `<1>`, `</1>`, `<br/>`, attributes dropped — since a language
 * may reorder them: only a tag missing from the translation, or one the reference does not have,
 * is reported. A `<` followed by a space (`a < b`) is no tag.
 */
class MarkupConsistencyInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nMarkupConsistency"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        referenceValueVisitor(holder) { literal, value, reference ->
            for (tag in ValueCheck.missingTags(value, reference)) {
                holder.registerProblem(literal, PluginBundle.message("inspection.markup.missing", tag))
            }
            for (tag in ValueCheck.extraTags(value, reference)) {
                holder.registerProblem(literal, PluginBundle.message("inspection.markup.extra", tag))
            }
        }
}
