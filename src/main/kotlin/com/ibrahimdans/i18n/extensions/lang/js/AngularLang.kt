package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.extensions.lang.js.extractors.AngularTranslatePipeExtractor
import com.ibrahimdans.i18n.plugin.parser.KeyExtractor

/**
 * Angular templates: the call-based syntaxes of [JsLang] plus the `| translate` pipe as it
 * actually appears once a template is parsed.
 *
 * A `.html` is only parsed as Angular inside an Angular project — a component referencing it
 * through `templateUrl`, with `@angular/core` resolvable. Outside one it stays plain HTML and
 * nothing here matches.
 */
class AngularLang : JsLang() {

    override fun syntaxOwnedExtractors(): List<KeyExtractor> =
        listOf(AngularTranslatePipeExtractor()) + super.syntaxOwnedExtractors()
}
