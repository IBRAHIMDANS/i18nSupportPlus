package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * i18n annotator for Angular templates.
 *
 * Each annotator carries its [com.ibrahimdans.i18n.Lang] in its constructor, so registering
 * [JsCompositeKeyAnnotator] against the `Angular2` dialect would bring `JsLang` along and miss
 * the `| translate` pipe entirely.
 */
class AngularCompositeKeyAnnotator : CompositeKeyAnnotatorBase(AngularLang())
