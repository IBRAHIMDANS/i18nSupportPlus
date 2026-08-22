package com.ibrahimdans.i18n.extensions.technology.i18njs

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

/**
 * i18n-js, the dominant i18n library of the React Native / Expo ecosystem.
 *
 * No technology declared it until now, and that such projects worked at all was an accident:
 * `t` happens to be published by [com.ibrahimdans.i18n.extensions.technology.i18next.I18NextTechnology],
 * so an i18n-js project depended on a framework it does not use — disabling or narrowing
 * i18next would have broken it silently.
 *
 * Source discovery is left to
 * [com.ibrahimdans.i18n.extensions.technology.tscatalog.TsCatalogTechnology], which reads the
 * plain locale-keyed TypeScript catalogue these projects declare, so nothing but the call
 * names belongs here.
 *
 * Two conventions differ from i18next and are worth knowing:
 *  - placeholders are written `%{count}`, not `{{count}}`. The plugin interprets no
 *    interpolation syntax at all, so both are displayed verbatim and nothing is needed here.
 *  - plurals are a nested object (`{ one: …, other: … }`) rather than the flat `key_one`
 *    suffixes the resolver expands. A key pointing at such an object is recognised by
 *    [com.ibrahimdans.i18n.plugin.tree.PluralGroup] instead of being reported as a
 *    "Reference to object".
 */
class I18nJsTechnology : SimpleTechnology() {

    /**
     * `i18n.t` must be listed explicitly: `JsLang.isDirectOrConfiguredCall` rejects a
     * qualified call whose full text is not a configured name, which is what guards against
     * `toast.t('…')`. Listing `t` again alongside i18next is harmless — the aggregated list
     * is only ever used through `any { }` and `in`, where a duplicate changes nothing.
     */
    override fun translationFunctionNames(): List<String> = listOf("t", "i18n.t")
}
