package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling
import com.ibrahimdans.i18n.plugin.tree.PluralCategories
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.codeInspection.options.OptPane.checkbox
import com.intellij.codeInspection.options.OptPane.pane
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Flags, in a locale file, an i18next plural group lacking a category its language needs:
 * `ru/common.json` holding `item_one` and `item_other` only, where Russian also needs `item_few`
 * and `item_many` — the count `3` then falls on a form that does not exist.
 *
 * *Sync Keys*, the Stats tab and *Translation key missing from a locale* compare plural groups as
 * a whole on purpose, so that `ja` holding `item_other` alone is complete. This is the check they
 * leave out: whether the forms a group has are the ones its own language uses, read from
 * [PluralCategories]. Only categories **missing** are reported, never one too many.
 *
 * A group is what [PluralKey.groupForms] calls one, among the keys of a single object: two forms
 * or more of a base, or `_other` alone — `step_one` with no sibling is a key named so. The problem
 * sits on the group's first form, the base having no element of its own. The locale is the
 * file's, the reference file included: an English file lacking `item_one` is reported too.
 *
 * The large-number `many` of French, Spanish, Italian, Portuguese and Catalan is only asked for
 * when [largeNumberForms] is set: real, but almost never translated.
 *
 * i18next's flat `key_one` convention only — vue-i18n's `a | b` and ICU's `{count, plural, …}`
 * hold every form in one value and are not read here.
 */
class MissingPluralFormsInspection : LocalInspectionTool() {

    /** Also ask French, Spanish, Italian, Portuguese and Catalan for their large-number `many`. */
    @JvmField
    var largeNumberForms: Boolean = false

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nMissingPluralForms"

    override fun getOptionsPane(): OptPane = pane(
        checkbox("largeNumberForms", PluginBundle.message("inspection.plural.forms.option.large"))
    )

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        val source = TranslationFileScope.sourceOf(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val locale = source.localeLabel()
        val needed = PluralCategories.of(locale, largeNumberForms)

        return object : PsiElementVisitor() {
            // YAML types stay inside this visitor: the platform reflects on the inspection
            // class's own members to save the profile (see InspectionYamlIsolationTest).
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is JsonProperty -> {
                        val siblings = (element.parent as? JsonObject)?.propertyList ?: return
                        check(element.name, element.nameElement, siblings.map { it.name }, needed, locale, holder)
                    }
                    is YAMLKeyValue -> {
                        val siblings = (element.parent as? YAMLMapping)?.keyValues ?: return
                        check(element.keyText, element.key ?: return, siblings.map { it.keyText }, needed, locale, holder)
                    }
                }
            }
        }
    }

    /**
     * Reports the plural group whose first form is [name], when its forms among [siblings] lack
     * some of the [needed] categories. Any other key, and the group's other forms, pass silently.
     */
    private fun check(
        name: String,
        anchor: PsiElement,
        siblings: List<String>,
        needed: Set<String>,
        locale: String,
        holder: ProblemsHolder
    ) {
        val (base, forms) = PluralKey.groupForms(siblings).entries
            .firstOrNull { (base, forms) -> forms.first() == name && forms != listOf(base) }
            ?.toPair() ?: return
        val present = forms.map { it.removePrefix(base + SUFFIX_SEPARATOR) }.toSet()
        val missing = PluralCategories.ALL.filter { it in needed && it !in present }
        if (missing.isEmpty()) return
        holder.registerProblem(
            anchor,
            PluginBundle.message("inspection.plural.forms.message", base, missing.joinToString(", "), locale),
            AddPluralFormsFix(missing.map { base + SUFFIX_SEPARATOR + it })
        )
    }

    private companion object {
        /** Between a base and its category: `item_one`. */
        const val SUFFIX_SEPARATOR = "_"
    }
}

/**
 * Adds the missing forms of a plural group with **empty** values, next to the existing ones.
 *
 * Empty rather than a copy of `_other`, as the *Add missing key* quick fix and *Sync Keys* do: a
 * copied form looks translated while its grammar is wrong (`few` in Russian), and would never be
 * reported again, where an empty value is what [EmptyTranslationValueInspection] points at until
 * someone translates it. Written through [DialogViewModel.saveTranslation], as *Sync Keys* writes.
 */
private class AddPluralFormsFix(private val forms: List<String>) : LocalQuickFix {

    override fun getName(): String = PluginBundle.message("inspection.plural.forms.fix.name", forms.joinToString(", "))
    override fun getFamilyName(): String = PluginBundle.message("inspection.plural.forms.fix.family")

    /** No preview: the fix writes through the localization source, i.e. the physical file. */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val anchor = descriptor.psiElement ?: return
        val source = TranslationFileScope.sourceOf(anchor.containingFile ?: return) ?: return
        val config = Settings.getInstance(project).config()
        val parentPath = TranslationFileKeys.pathOf(anchor).dropLast(1)
        val synchronizer = KeysSynchronizer()
        for (form in forms) {
            val key = (parentPath + form).fold("") { spelled, segment -> KeySpelling.child(config, spelled, segment) }
            DialogViewModel(project).saveTranslation(source, synchronizer.buildFullKey(key, config), "")
        }
    }
}
