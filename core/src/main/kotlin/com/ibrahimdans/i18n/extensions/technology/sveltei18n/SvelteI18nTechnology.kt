package com.ibrahimdans.i18n.extensions.technology.sveltei18n

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class SvelteI18nTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "svelte-i18n"

    override fun translationFunctionNames(): List<String> = listOf("_", "\$_")
}
