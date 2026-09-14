package com.ibrahimdans.i18n.extensions.technology.ngxtranslate

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

/**
 * ngx-translate.
 *
 * Publishes no bare name: its calls are always made on the service (`translate.instant('key')`),
 * which `NgxTranslateExtractor` recognises from the call itself, and `| translate` has extractors
 * of its own. `instant`, `get` and `stream` used to be published, and since every technology's
 * names apply to every project, any unqualified `get('…')` — a form library, an HTTP helper, PHP —
 * was annotated as an unresolved key.
 */
class NgxTranslateTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = emptyList()
}
