package com.ibrahimdans.i18n.extensions.technology.sveltei18n

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class SvelteI18nTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "svelte-i18n"

    /** `$_` and its aliases `$t` and `$format`; `$json` reads the messages under a key. */
    override fun translationFunctionNames(): List<String> = listOf("_", "\$_", "\$t", "\$format", "\$json")
}
