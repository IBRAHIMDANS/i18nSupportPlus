package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.actions.KeyLevelRename
import com.ibrahimdans.i18n.plugin.ide.actions.RenameI18nKeyHandler
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import java.text.Normalizer

/**
 * Flags a key of a translation file that does not follow the project's naming convention:
 * `user_name` in a camelCase project, `userName` in a snake_case one.
 *
 * Each segment is checked on its own — the levels of a nested file, the parts of a property name
 * holding the key separator — and a plural form is checked without its suffix, so `itemCount_one`
 * passes in camelCase. A segment made of digits only (`404`) passes every convention.
 *
 * Off by default: a project picks its convention in the inspection options, or a pattern of its
 * own, which a segment must match whole. An invalid pattern reports nothing.
 *
 * *Rename to '…'…* opens the key rename with the name converted to the convention filled in: the key
 * moves in every locale and at every call site, as Shift+F6 does. Not offered on a plural form,
 * whose siblings must move together, nor where keys are flat.
 */
class KeyNamingInspection : LocalInspectionTool() {

    @JvmField
    var convention: String = Convention.CAMEL_CASE.name

    @JvmField
    var customPattern: String = ""

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nKeyNaming"

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.dropdown(
            "convention", PluginBundle.message("inspection.keyNaming.option.convention"),
            *Convention.entries.map { OptPane.option(it.name, it.label ?: PluginBundle.message("inspection.keyNaming.option.custom")) }.toTypedArray()
        ),
        OptPane.string("customPattern", PluginBundle.message("inspection.keyNaming.option.pattern")),
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (TranslationFileScope.sourceOf(holder.file) == null) return PsiElementVisitor.EMPTY_VISITOR
        val rule = ruleOf(Convention.entries.firstOrNull { it.name == convention } ?: Convention.CAMEL_CASE, customPattern)
            ?: return PsiElementVisitor.EMPTY_VISITOR
        val config = Settings.getInstance(holder.project).config()
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                val base = PluralKey.stripSuffix(entry.name, config.pluralSeparator)
                val segments = if (config.keySeparator.isEmpty()) listOf(base) else base.split(config.keySeparator)
                if (segments.all { it.isEmpty() || rule.matches(it) }) return
                val plural = base != entry.name
                val renamable = !plural && !config.usesFlatKeys() && segments.size == 1
                val fixes = if (renamable) arrayOf<LocalQuickFix>(RenameKeyFix(base, rule.convert(base), entry.literal != null)) else emptyArray()
                holder.registerProblem(entry.keyElement, rule.problem(entry.name), *fixes)
            }
        }
    }

    /** The naming conventions offered, each with the name the options and the messages show; none for a custom pattern. */
    enum class Convention(val label: String?) {
        CAMEL_CASE("camelCase"),
        SNAKE_CASE("snake_case"),
        KEBAB_CASE("kebab-case"),
        CUSTOM(null),
    }

    /**
     * What a segment must match, the message reporting a key that does not, and how to convert
     * one; [convert] is null when it cannot.
     */
    internal class Rule(private val regex: Regex, val problem: (String) -> String, val convert: (String) -> String?) {
        fun matches(segment: String): Boolean = DIGITS.matches(segment) || regex.matches(segment)
    }

    internal companion object {

        private val DIGITS = Regex("[0-9]+")
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[^A-Za-z0-9]+")
        private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

        /** The rule of [convention], or null for a custom [pattern] that is blank or invalid. */
        fun ruleOf(convention: Convention, pattern: String): Rule? {
            val problem = { name: String -> PluginBundle.message("inspection.keyNaming.message", name, convention.label ?: "") }
            return when (convention) {
                Convention.CAMEL_CASE -> Rule(Regex("[a-z0-9][a-zA-Z0-9]*"), problem) { name ->
                    words(name)?.mapIndexed { index, word -> if (index == 0) word else word.replaceFirstChar(Char::uppercaseChar) }?.joinToString("")
                }
                Convention.SNAKE_CASE -> Rule(Regex("[a-z0-9]+(_[a-z0-9]+)*"), problem) { name -> words(name)?.joinToString("_") }
                Convention.KEBAB_CASE -> Rule(Regex("[a-z0-9]+(-[a-z0-9]+)*"), problem) { name -> words(name)?.joinToString("-") }
                Convention.CUSTOM -> pattern.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Regex(it) }.getOrNull() }
                    ?.let { regex -> Rule(regex, { name -> PluginBundle.message("inspection.keyNaming.message.pattern", name, pattern) }) { null } }
            }
        }

        /** `userName`, `user_name`, `User-Name`, `XMLParser` → their lowercase words; accents dropped; null when none is left. */
        fun words(name: String): List<String>? =
            Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "")
                .split(SEPARATORS)
                .flatMap { it.split(CAMEL_BOUNDARY) }
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
                .ifEmpty { null }
    }
}

/**
 * Opens the key rename ([RenameI18nKeyHandler.renameLevel]) on the reported key [name], its
 * [converted] spelling filled in when the convention gives one: the user confirms or edits the
 * name, and the key moves in every locale and call site.
 */
private class RenameKeyFix(private val name: String, private val converted: String?, private val leaf: Boolean) : LocalQuickFix {

    override fun getName(): String =
        if (converted != null) PluginBundle.message("inspection.keyNaming.fix.name", converted) else familyName
    override fun getFamilyName(): String = PluginBundle.message("inspection.keyNaming.fix.family")

    // The rename asks for the name and confirms in dialogs, then opens its own write command.
    override fun startInWriteAction(): Boolean = false

    /** The rename touches other files and asks first: no preview on a copy. */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    /**
     * Runs the rename right after the command the platform opened for the fix, left empty: the
     * rename opens a global command of its own, which nested in the platform's would keep its
     * confirmation policy — one Ctrl+Z would ask "Undo …?", as the unused-key deletion explains.
     */
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val property = TranslationPsi.propertyOf(descriptor.psiElement ?: return, strict = false) ?: return
        val level = KeyLevelRename.inTranslationFile(property) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (property.isValid) RenameI18nKeyHandler().renameLevel(project, level, converted ?: name, leaf)
        }, project.disposed)
    }
}
