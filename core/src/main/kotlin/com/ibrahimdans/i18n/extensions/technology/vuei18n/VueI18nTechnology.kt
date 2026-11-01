package com.ibrahimdans.i18n.extensions.technology.vuei18n

import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

class VueI18nTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "vue-i18n"

    override fun translationFunctionNames(): List<String> = FrameworkPresets.VUE_I18N.callNames
}
