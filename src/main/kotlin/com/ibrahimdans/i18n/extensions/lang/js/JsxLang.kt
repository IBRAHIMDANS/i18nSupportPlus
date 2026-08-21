package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.FormattedMessageExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.LinguiTransKeyExtractor
import com.ibrahimdans.i18n.extensions.lang.js.extractors.NgxTranslatePipeExtractor
import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor

class JsxLang : JsLang() {

    companion object {
        /**
         * Markup-based syntaxes on top of the call-based ones inherited from [JsLang]:
         * the key lives in a tag attribute (`<Trans id>`, `<FormattedMessage id>`) or in
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
        )
    }

    override fun syntaxOwnedExtractors(): List<KeyExtractor> =
        MARKUP_EXTRACTORS + super.syntaxOwnedExtractors()

    override fun translationExtractor(): TranslationExtractor = JsxTranslationExtractor()
}
