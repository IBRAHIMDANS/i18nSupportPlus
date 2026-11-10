package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * Colours the keys of a Vue template's markup that resolve. Their problems are reported by
 * [JsUnresolvedKeyInspection].
 */
class JsCompositeKeyAnnotator : CompositeKeyAnnotatorBase(JsLang(), reportsProblems = false)
