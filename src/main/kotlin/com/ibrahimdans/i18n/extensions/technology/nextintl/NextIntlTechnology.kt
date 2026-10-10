package com.ibrahimdans.i18n.extensions.technology.nextintl

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

/**
 * next-intl: `const t = useTranslations('Home')`, then `t('title')` for the key `Home.title`.
 *
 * The keys themselves are read by ReactUseTranslationHookExtractor, which every preset allows.
 * What this technology adds is a framework a module can be set to: without it, a next-intl
 * project could only be given another framework's preset — react-intl's dropped `t` and turned
 * on flat keys, i18next's read `{name}` as plain text instead of an ICU argument.
 */
class NextIntlTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "next-intl"

    override fun translationFunctionNames(): List<String> = listOf("t")
}
