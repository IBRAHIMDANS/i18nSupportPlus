package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a value whose final punctuation or surrounding whitespace differs from the reference
 * locale's: `Save.` translated `Enregistrer` loses its full stop, `Name:` translated `Nom` its
 * colon, and a stray space at either end shows on screen. These are the commonest translation
 * slips after placeholders, which *Placeholder consistency* already covers.
 *
 * Only the presence of a final mark is compared, never which language writes it how: `?` and the
 * Arabic `؟`, `.` and the CJK `。`, full-width `！` `？` `：` are the same mark, `...` is `…`, and
 * French typography (`Nom :`, with a space before the colon) ends with the same `:`. A mark present
 * on both sides is never reported, even when it differs (`!` for `.`): a translator's choice.
 *
 * Off by default, as such rules are noisy on some projects; each check can be turned off in the
 * inspection options. *Align with the reference locale* rewrites the value the way *Sync Keys*
 * writes one, for JSON and YAML alike.
 */
class ValueStyleInspection : LocalInspectionTool() {

    @JvmField
    var checkPunctuation: Boolean = true

    @JvmField
    var checkLeadingWhitespace: Boolean = true

    @JvmField
    var checkTrailingWhitespace: Boolean = true

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nValueStyle"

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.checkbox("checkPunctuation", PluginBundle.message("inspection.style.option.punctuation")),
        OptPane.checkbox("checkLeadingWhitespace", PluginBundle.message("inspection.style.option.leading")),
        OptPane.checkbox("checkTrailingWhitespace", PluginBundle.message("inspection.style.option.trailing")),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        referenceValueVisitor(holder) { literal, value, reference ->
            if (value.isBlank() || reference.isBlank()) return@referenceValueVisitor
            if (checkPunctuation) {
                val expected = finalMark(reference)
                val actual = finalMark(value)
                if (expected != null && actual == null) {
                    holder.registerProblem(
                        literal, PluginBundle.message("inspection.style.punctuation.missing", expected),
                        AlignWithReferenceFix(withFinalMark(value, reference))
                    )
                } else if (expected == null && actual != null) {
                    holder.registerProblem(
                        literal, PluginBundle.message("inspection.style.punctuation.extra", actual),
                        AlignWithReferenceFix(withoutFinalMark(value))
                    )
                }
            }
            val leading = checkLeadingWhitespace && leadingSpace(value) != leadingSpace(reference)
            val trailing = checkTrailingWhitespace && trailingSpace(value) != trailingSpace(reference)
            if (leading || trailing) {
                holder.registerProblem(
                    literal, PluginBundle.message("inspection.style.whitespace"),
                    AlignWithReferenceFix(leadingSpace(reference) + value.trim(::isSpace) + trailingSpace(reference))
                )
            }
        }

    internal companion object {

        /** Final marks, each language's spelling mapped to the one the messages show. */
        private val MARKS = mapOf(
            '.' to ".", '。' to ".", '．' to ".",
            '?' to "?", '؟' to "?", '？' to "?",
            '!' to "!", '！' to "!",
            ':' to ":", '：' to ":",
            '…' to "…",
        )

        /** Whitespace, the no-break spaces French typography puts before `:?!;` included. */
        fun isSpace(char: Char): Boolean = char.isWhitespace() || char == ' ' || char == ' '

        /** The final mark of [text] as the messages show it (`؟` is `?`, `...` is `…`), or null. */
        fun finalMark(text: String): String? {
            val trimmed = text.trimEnd(::isSpace)
            if (trimmed.endsWith("...")) return "…"
            return trimmed.lastOrNull()?.let(MARKS::get)
        }

        private fun leadingSpace(text: String): String = text.takeWhile(::isSpace)
        private fun trailingSpace(text: String): String = text.takeLastWhile(::isSpace)

        /** [value] ending with the reference's own final mark, before any trailing whitespace. */
        fun withFinalMark(value: String, reference: String): String {
            val trimmedReference = reference.trimEnd(::isSpace)
            val mark = if (trimmedReference.endsWith("...")) "..." else trimmedReference.last().toString()
            val body = value.trimEnd(::isSpace)
            return body + mark + value.substring(body.length)
        }

        /** [value] without its final mark, nor the space French typography puts before it. */
        fun withoutFinalMark(value: String): String {
            val body = value.trimEnd(::isSpace)
            val withoutMark = if (body.endsWith("...")) body.dropLast(3) else body.dropLast(1)
            return withoutMark.trimEnd(::isSpace) + value.substring(body.length)
        }
    }
}

/**
 * Writes [newValue] in place of the reported value, through the path *Sync Keys* and *Add missing
 * key* write with ([DialogViewModel.saveTranslation]), so JSON and YAML are handled alike.
 */
private class AlignWithReferenceFix(private val newValue: String) : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.style.fix.name")
    override fun getFamilyName(): String = name

    /** The fix writes through the localization source, i.e. the physical file: no preview on a copy. */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement ?: return
        val source = TranslationFileScope.sourceOf(literal.containingFile ?: return) ?: return
        val config = Settings.getInstance(project).config()
        val key = TranslationFileKeys.pathOf(literal).fold("") { spelled, segment -> KeySpelling.child(config, spelled, segment) }
        DialogViewModel(project).saveTranslation(source, KeysSynchronizer().buildFullKey(key, config), newValue)
    }
}
