package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/** Colours the keys of a Twig template by their state: resolved, missing, partly translated. */
class TwigCompositeKeyAnnotator : CompositeKeyAnnotatorBase(TwigLang())
