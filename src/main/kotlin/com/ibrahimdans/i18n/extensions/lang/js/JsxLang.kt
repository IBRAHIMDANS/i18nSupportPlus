package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.isOfLanguage
import com.ibrahimdans.i18n.extensions.lang.js.extractors.FormattedMessageExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.LinguiTransKeyExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.NgxTranslatePipeExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.XmlAttributeKeyExtractor
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.psi.PsiElement

class JsxLang : JsLang() {

    companion object {
        /**
         * Markup-based syntaxes on top of the call-based ones inherited from [JsLang]:
         * the key lives in a tag attribute (`<Trans id>`, `<FormattedMessage id>`, react-i18next's
         * `<Trans i18nKey>`) or in
         * markup text (`{{ 'key' | translate }}`), which no JS argument pattern matches.
         *
         * The ngx-translate pipe only ever reaches this point inside JSX/TSX, where the
         * XML PSI exists — the plugin registers no annotator for standalone Angular
         * templates, so an external `.html` is still out of reach.
         */
        private val MARKUP_EXTRACTORS: List<KeyExtractor> = listOf(
            LinguiTransKeyExtractor(),
            FormattedMessageExtractor(),
            NgxTranslatePipeExtractor(),
            XmlAttributeKeyExtractor(),
        )
    }

    override fun syntaxOwnedExtractors(): List<KeyExtractor> =
        MARKUP_EXTRACTORS + super.syntaxOwnedExtractors()

    override fun translationExtractor(): TranslationExtractor = JsxTranslationExtractor()

    /**
     * The JavaScript plugin's answer for the unresolved key inspection, given by this language
     * alone — the plugin registers [JsLang] as well, and one answer per element is enough. Each
     * element is read as its annotator reads it: an Angular template, markup and expressions, by
     * [AngularLang] (the pipes and directives); JavaScript and its dialects by this language; the
     * markup of a Vue template by [JsLang]. Languages are told apart on their id, so an IDE
     * without Vue or Angular loads nothing of theirs.
     */
    override fun inspectionLangOf(element: PsiElement): Lang? {
        return when {
            element.isOfLanguage(ANGULAR_EXPRESSIONS) || element.isOfLanguage(ANGULAR_MARKUP) -> ANGULAR
            element.language.isKindOf(JavascriptLanguage) -> this
            element.isOfLanguage(VUE) -> VUE_MARKUP
            else -> null
        }
    }
}

private const val ANGULAR_EXPRESSIONS = "Angular2"
private const val ANGULAR_MARKUP = "Angular2Html"
private const val VUE = "Vue"

/** The languages the inspection reads an Angular template and a Vue template's markup with: one each, made on first use. */
private val ANGULAR: Lang by lazy { AngularLang() }
private val VUE_MARKUP: Lang by lazy { JsLang() }
