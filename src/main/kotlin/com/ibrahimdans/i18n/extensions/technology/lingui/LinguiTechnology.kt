package com.ibrahimdans.i18n.extensions.technology.lingui

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class LinguiTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = listOf("msg", "i18n._")
}
