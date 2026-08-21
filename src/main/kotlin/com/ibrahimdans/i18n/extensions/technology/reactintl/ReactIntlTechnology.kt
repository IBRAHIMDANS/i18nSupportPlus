package com.ibrahimdans.i18n.extensions.technology.reactintl

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

/**
 * react-intl (FormatJS).
 *
 * `t` used to be listed here, but react-intl has no such function: it was only ever
 * duplicating the name already published by I18NextTechnology, widening the false
 * positive surface for every project since all technologies are active at once.
 *
 * Key extraction itself is driven by ReactIntlExtractor (`formatMessage({ id })`) and
 * FormattedMessageExtractor (`<FormattedMessage id="…" />`), which match the call shape
 * rather than a function name.
 */
class ReactIntlTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = listOf("formatMessage")
}
