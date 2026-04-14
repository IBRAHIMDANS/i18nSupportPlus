package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * i18n annotator for JSX/TSX — uses JsxLang which includes LinguiTransKeyExtractor.
 */
class JsxCompositeKeyAnnotator : CompositeKeyAnnotatorBase(JsxLang())
