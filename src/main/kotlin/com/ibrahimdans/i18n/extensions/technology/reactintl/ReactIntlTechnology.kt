package com.ibrahimdans.i18n.extensions.technology.reactintl

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class ReactIntlTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = listOf("formatMessage", "t")
}
