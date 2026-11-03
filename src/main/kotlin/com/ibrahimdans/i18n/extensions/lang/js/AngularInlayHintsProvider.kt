package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.Lang
import com.ibrahimdans.i18n.plugin.ide.inlay.I18nInlayHintsProvider

/**
 * The value after each key of an Angular template, `{{ 'key' | translate }}` and the attribute
 * directives included.
 *
 * Declared on the template's language (`angularConfig.xml`): a `.html` template is an
 * `Angular17Html` file, no dialect of JavaScript, so the provider declared on JavaScript never
 * ran on it. Its expressions sit in the file's own tree, not in an injected fragment.
 */
open class AngularInlayHintsProvider : I18nInlayHintsProvider() {
    private val angular: List<Lang> = listOf(AngularLang())

    override fun langs(): List<Lang> = angular
}

/**
 * The value after each key of a component's inline `template: '…'`, declared on TypeScript.
 *
 * The template is a fragment injected into the literal: the pass visits the component's own
 * elements, and the provider declared on JavaScript reads only those, so the template had no hint.
 * This one reads the injected fragments only, so the component's own keys keep their single hint.
 */
class AngularInlineTemplateInlayHintsProvider : AngularInlayHintsProvider() {
    override fun readsInjectedFragments(): Boolean = true
}
