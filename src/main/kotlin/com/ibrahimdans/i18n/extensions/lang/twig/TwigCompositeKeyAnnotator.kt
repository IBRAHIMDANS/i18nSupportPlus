package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * Colours the keys of a Twig template that resolve. Their problems are reported by
 * [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspection].
 */
class TwigCompositeKeyAnnotator : CompositeKeyAnnotatorBase(TwigLang())
