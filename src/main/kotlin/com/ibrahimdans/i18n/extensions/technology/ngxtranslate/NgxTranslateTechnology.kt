package com.ibrahimdans.i18n.extensions.technology.ngxtranslate

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class NgxTranslateTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = listOf("instant", "get", "stream")
}
