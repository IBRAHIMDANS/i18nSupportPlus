package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * Colours the keys of JavaScript and its dialects that resolve — uses JsxLang, which includes
 * LinguiTransKeyExtractor. Their problems are reported by [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspection].
 */
class JsxCompositeKeyAnnotator : CompositeKeyAnnotatorBase(JsxLang())
