package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.TranslationExtractor

class JsxLang : JsLang() {

    override fun translationExtractor(): TranslationExtractor = JsxTranslationExtractor()
}
