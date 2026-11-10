package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * i18n annotator for Angular templates.
 *
 * Each annotator carries its [com.ibrahimdans.i18n.Lang] in its constructor, so registering
 * [JsCompositeKeyAnnotator] against the `Angular2` dialect would bring `JsLang` along and miss
 * the `| translate` pipe entirely. It colours the keys that resolve; their problems are reported
 * by [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspection], which reads the
 * template with [AngularLang] too.
 */
class AngularCompositeKeyAnnotator : CompositeKeyAnnotatorBase(AngularLang())
