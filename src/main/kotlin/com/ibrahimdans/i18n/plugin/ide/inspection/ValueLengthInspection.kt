package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.codeInspection.options.OptPane.number
import com.intellij.codeInspection.options.OptPane.pane
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import kotlin.math.roundToInt

/**
 * Flags a translation much longer than the reference locale's value of the same key: `Save
 * changes` becoming `Enregistrer les modifications` overflows the button it was sized for.
 *
 * Off by default, and both thresholds are options, since the length a design tolerates is the
 * project's: a value is reported when it is longer than [maxRatioPercent] % of the reference,
 * and only when the reference holds at least [minReferenceLength] characters — `OK` → `D'accord`
 * is twice as long and fits anywhere.
 *
 * Lengths are counted without the message's variables (`{{name}}`, `{name}`, `%{name}`, `%s`,
 * read as the translation dialog reads them): what they will hold is unknown, and both sides
 * write the same ones. They are counted in code points, so an accented letter or an emoji is one
 * character, as on screen.
 *
 * The reference file itself is never reported: [TranslationFileKeys.referenceTranslations] has
 * no counterpart for it.
 */
class ValueLengthInspection : LocalInspectionTool() {

    /** Above this percentage of the reference's length, a value is reported. */
    @JvmField
    var maxRatioPercent: Int = DEFAULT_MAX_RATIO_PERCENT

    /** References shorter than this are never compared: a short word has no length to keep. */
    @JvmField
    var minReferenceLength: Int = DEFAULT_MIN_REFERENCE_LENGTH

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nValueLength"

    override fun getOptionsPane(): OptPane = pane(
        number("maxRatioPercent", PluginBundle.message("inspection.length.option.ratio"), 101, 1000),
        number("minReferenceLength", PluginBundle.message("inspection.length.option.min"), 1, 1000),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val file = holder.file
        if (TranslationFileScope.sourceOf(file) == null) return PsiElementVisitor.EMPTY_VISITOR
        val refTranslations: Map<String, String> by lazy { TranslationFileKeys.referenceTranslations(file) }

        return object : PsiElementVisitor() {
            // YAML types stay inside this visitor: the platform reflects on the inspection
            // class's own members to save the profile (see InspectionYamlIsolationTest).
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        val literal = element.value as? JsonStringLiteral ?: return
                        check(literal, literal.value, holder, refTranslations)
                    }
                    is YAMLKeyValue -> {
                        val scalar = element.value as? YAMLScalar ?: return
                        check(scalar, scalar.textValue, holder, refTranslations)
                    }
                }
            }
        }
    }

    /** Compares the [value] written in [literal] with the same key's value in [refTranslations]. */
    private fun check(literal: PsiElement, value: String, holder: ProblemsHolder, refTranslations: Map<String, String>) {
        val reference = refTranslations[TranslationFileKeys.keyOf(literal)] ?: return
        val ratio = lengthRatioPercent(value, reference, minReferenceLength) ?: return
        if (ratio <= maxRatioPercent) return
        holder.registerProblem(
            literal,
            PluginBundle.message("inspection.length.message", ratio, measuredLength(value), measuredLength(reference))
        )
    }

    internal companion object {
        const val DEFAULT_MAX_RATIO_PERCENT = 150
        const val DEFAULT_MIN_REFERENCE_LENGTH = 10

        /**
         * The length of [value] as a percentage of [reference]'s, both measured by
         * [measuredLength], or null when the reference is shorter than [minReferenceLength].
         */
        fun lengthRatioPercent(value: String, reference: String, minReferenceLength: Int): Int? {
            val referenceLength = measuredLength(reference)
            if (referenceLength < minReferenceLength || referenceLength == 0) return null
            return (measuredLength(value) * 100.0 / referenceLength).roundToInt()
        }

        /**
         * The code points of [text] once its variables are removed, its runs of whitespace
         * collapsed — a removed variable leaves two spaces behind — and its ends trimmed.
         */
        fun measuredLength(text: String): Int {
            val withoutVariables = StringBuilder(text)
            DialogViewModel.variableRanges(text).asReversed().forEach { withoutVariables.delete(it.first, it.last + 1) }
            val visible = withoutVariables.toString().replace(WHITESPACE, " ").trim()
            return visible.codePointCount(0, visible.length)
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
