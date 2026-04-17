package com.ibrahimdans.i18n.extensions.technology.sveltei18n

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class SvelteI18nTechnology : SimpleTechnology() {
    override fun translationFunctionNames(): List<String> = listOf("_", "\$_")
}
