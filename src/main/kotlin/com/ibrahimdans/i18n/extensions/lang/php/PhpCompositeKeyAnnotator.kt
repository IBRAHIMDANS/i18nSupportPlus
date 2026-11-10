package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.ide.annotator.CompositeKeyAnnotatorBase

/**
 * Colours the keys of PHP, and of the PHP a Blade view injects, that resolve. Their problems are
 * reported by [com.ibrahimdans.i18n.plugin.ide.inspection.UnresolvedKeyInspection].
 */
class PhpCompositeKeyAnnotator: CompositeKeyAnnotatorBase(PhpLang())