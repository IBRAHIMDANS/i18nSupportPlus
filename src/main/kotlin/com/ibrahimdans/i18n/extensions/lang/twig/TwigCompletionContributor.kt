package com.ibrahimdans.i18n.extensions.lang.twig

import com.ibrahimdans.i18n.plugin.ide.completion.CompositeKeyCompletionContributor

/** Completes a key being typed in `'…'|trans`, among the keys of the domain the template names. */
class TwigCompletionContributor : CompositeKeyCompletionContributor(TwigLang())
