package com.ibrahimdans.i18n.plugin.ide.inspection

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
            val expected = tagsOf(reference)
            val actual = tagsOf(value)
            for (tag in (expected - actual).keys) {
                holder.registerProblem(literal, PluginBundle.message("inspection.markup.missing", tag))
            }
            for (tag in (actual - expected).keys) {
                holder.registerProblem(literal, PluginBundle.message("inspection.markup.extra", tag))
            }
        }

    internal companion object {

        private val TAG = Regex("""<(/?)([A-Za-z0-9_.-]+)(?:\s[^<>]*?)?\s*(/?)>""")

        /** The tags of [text], normalised (`<1>`, `</1>`, `<br/>`), with how many times each appears. */
        fun tagsOf(text: String): Map<String, Int> =
            TAG.findAll(text)
                .map { match ->
                    val (closing, name, selfClosing) = match.destructured
                    if (selfClosing.isNotEmpty()) "<$name/>" else "<$closing$name>"
                }
                .groupingBy { it }
                .eachCount()

        /** The tags [this] holds more often than [other], with how many more. */
        private operator fun Map<String, Int>.minus(other: Map<String, Int>): Map<String, Int> =
            mapValues { (tag, count) -> count - (other[tag] ?: 0) }.filterValues { it > 0 }
    }
}
