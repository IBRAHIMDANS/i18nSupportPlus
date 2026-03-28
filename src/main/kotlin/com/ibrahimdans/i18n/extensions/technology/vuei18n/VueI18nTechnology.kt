package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class VueI18nTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = listOf("\$t", "\$tc", "\$te")
}
