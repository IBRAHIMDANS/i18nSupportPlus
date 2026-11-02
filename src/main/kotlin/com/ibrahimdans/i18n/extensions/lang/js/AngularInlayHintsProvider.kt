package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.inlay.I18nInlayHintsProvider

/**
 * The value after each key of an Angular template, `{{ 'key' | translate }}` included.
 *
 * Declared on the template's language (`angularConfig.xml`): a `.html` template is an
 * `Angular17Html` file, no dialect of JavaScript, so the provider declared on JavaScript never
 * ran on it. Its expressions sit in the file's own tree, not in an injected fragment.
 */
class AngularInlayHintsProvider : I18nInlayHintsProvider() {
    private val angular: List<Lang> = listOf(AngularLang())

    override fun langs(): List<Lang> = angular
}
