package com.ibrahimdans.i18n.extensions.lang.php

import com.ibrahimdans.i18n.plugin.ide.inlay.I18nInlayHintsProvider

/** The value after each key of a Blade view: its `@lang('…')` and `{{ __('…') }}` are injected PHP. */
class BladeInlayHintsProvider : I18nInlayHintsProvider() {
    override fun readsInjectedFragments(): Boolean = true
}
