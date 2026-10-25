package com.ibrahimdans.i18n.plugin.ide.inlay

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.tree.PluralKey
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.ellipsis
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.renderIcu
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Shows, after each value of a translation file, the value the reference locale holds for the
 * same key: `"save": "Enregistrer"` in `fr/common.json` reads `en: Save`.
 *
 * The reference is the one the inspections compare against — [TranslationFileScope.referenceLocaleFor],
 * i.e. [com.ibrahimdans.i18n.plugin.utils.ReferenceLocale] for the module holding the file — and its
 * file is the same namespace in that locale ([TranslationFileKeys.referenceFileOf]). The reference
 * file itself gets no hint: it would only repeat its own values.
 *
 * A key the reference lacks reads `missing in en`. A plural form the reference language does not
 * have (`item_many` in `fr`, where `en` has `item_one` / `item_other`; `steps.few` beside
 * `steps.other`) shows the reference's `other` form instead — the source [ReviewSources] gives it —:
 * the group exists there, only not that category.
 *
 * Values go through the folding's display pipeline — ICU rendered, cut at `foldingMaxLength`.
 * Toggled under *Editor > Inlay Hints* as *i18n reference values*.
 */
class ReferenceValueInlayHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        // Everything that does not depend on the element is read once per file and pass.
        val source = TranslationFileScope.sourceOf(file) ?: return null
        val referenceLocale = TranslationFileScope.referenceLocaleFor(file, source)
        if (source.localeLabel() == referenceLocale) return null
        val referenceFile = TranslationFileKeys.referenceFileOf(file) ?: return null

        val config = Settings.getInstance(file.project).config()
        val ownKeys = TranslationFileKeys.translationLeaves(file).keys
        val reference = TranslationFileKeys.flattenTranslations(referenceFile)
        val referenceGroups = PluralKey.groupForms(reference.keys)
        val missing = PluginBundle.message("inlay.reference.missing", referenceLocale)

        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                val entry = TranslationPsi.entryOf(element) ?: return
                val literal = entry.literal ?: return
                val path = TranslationFileKeys.pathOf(element)
                // A leaf the key scan skips (a later YAML document) resolves nowhere: no hint.
                if (path !in ownKeys) return
                val key = path.joinToString(".")

                val text = referenceValue(key, reference, referenceGroups, config.pluralSeparator)
                    ?.renderIcu()
                    ?.ellipsis(config.foldingMaxLength)
                    ?.let { "$referenceLocale: $it" }
                    ?: missing
                sink.addPresentation(InlineInlayPosition(literal.textRange.endOffset, true), null, null, HintFormat.default) {
                    text(text)
                }
            }
        }
    }

    internal companion object {

        /**
         * The reference value of [key], or its source ([ReviewSources]: the plural group's `other`
         * form) when the reference language lacks this category — any form of a CLDR group when
         * it has no `other`. Null when the reference holds neither.
         */
        fun referenceValue(
            key: String,
            reference: Map<String, String>,
            referenceGroups: Map<String, List<String>>,
            pluralSeparator: String,
        ): String? {
            reference[key]?.let { return it }
            ReviewSources.sourceTextOf(key, pluralSeparator, referenceText = reference::get)?.let { return it }
            val base = PluralKey.stripSuffix(key, pluralSeparator)
            if (base == key) return null
            return referenceGroups[base]?.firstOrNull()?.let(reference::get)
        }
    }
}
