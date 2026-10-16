package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

/**
 * Paraglide JS (inlang): messages are compiled into functions, `m.hello()`, and no call names a key
 * as a string. The technology publishes no translation function, so a module whose preset is
 * `paraglide` stops claiming the generic `t('…')` calls: `m.hello` is no call a function name describes.
 */
class ParaglideTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "paraglide"

    override fun translationFunctionNames(): List<String> = emptyList()
}
