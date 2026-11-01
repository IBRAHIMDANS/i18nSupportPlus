package com.ibrahimdans.i18n.plugin.rules

/**
 * The frameworks the plugin and `i18n-check` read, one [FrameworkRules] each: the single place a
 * framework's function names, hooks, components and pipes are written. A technology's
 * `translationFunctionNames()` and the extractors of both pipelines read them from here.
 */
object FrameworkPresets {

    // Before the presets reading them: an object's properties are initialized in order.
    private const val LINGUI_PACKAGES = "@lingui/"
    private const val SVELTE_I18N_PACKAGE = "svelte-i18n"
    private val SVELTE_IMPORT_GATED = setOf("_", "\$t")
    private val TRANSLOCO_PACKAGES = listOf("@jsverse/transloco", "@ngneat/transloco")

    /** i18next and react-i18next: `t('ns:key', { ns, context })`, `useTranslation(ns, { keyPrefix })`, `<Trans i18nKey>`. */
    val I18NEXT = FrameworkRules(
        id = "i18next",
        // Qualified names in full: a call qualified by anything else than `this` is only read when its
        // whole text is one of them, which keeps `toast.t('…')` out. `props.t` and `this.props.t` are
        // what the `withTranslation` HOC injects, `i18next.t` the instance API outside React.
        calls = listOf("t", "i18n.t", "i18next.t", "props.t", "this.props.t").map { CallRule(it, namespaceOption = "ns", contextOption = "context") },
        hooks = listOf(HookRule("useTranslation", HookGives.NAMESPACES, keyPrefixOption = "keyPrefix")),
        components = listOf(ComponentRule(tag = null, keyAttribute = "i18nKey", contextAttribute = "context")),
    )

    /** next-intl: `const t = useTranslations('Home')` or `await getTranslations({ namespace })`, then `t('title')`, `t.rich`, `t.markup`, `t.raw`, `t.has`. */
    val NEXT_INTL = FrameworkRules(
        id = "next-intl",
        hooks = listOf("useTranslations", "getTranslations").map {
            HookRule(it, HookGives.KEY_PREFIX, namespaceProperty = "namespace", methods = setOf("rich", "markup", "raw", "has"))
        },
    )

    /** react-intl (FormatJS): the `id` of a message descriptor, inline or catalogued, and `<FormattedMessage id>`. */
    val REACT_INTL = FrameworkRules(
        id = "react-intl",
        calls = listOf(
            // Matched on the suffix so every calling style is read: the bare `formatMessage(…)` destructured
            // from `useIntl()`, `intl.formatMessage(…)`, `this.props.intl.formatMessage(…)` from injectIntl.
            CallRule("formatMessage", CalleeMatch.SUFFIX, KeyLocation.Descriptor("id")),
            CallRule("defineMessages", key = KeyLocation.Catalogue("id")),
            CallRule("defineMessage", key = KeyLocation.Catalogue("id")),
        ),
        components = listOf(ComponentRule(tag = "FormattedMessage", keyAttribute = "id")),
    )

    /**
     * Lingui: `msg('key')`, `msg({ id: 'key' })` and `i18n._('key')`. `msg` is a common name, read only
     * where the file imports a Lingui package. The source-text `<Trans>Hello</Trans>` is no rule: its
     * msgid is built by the macro's own algorithm, code on each side.
     */
    val LINGUI = FrameworkRules(
        id = "lingui",
        calls = listOf(
            CallRule("msg", importedFrom = listOf(LINGUI_PACKAGES)),
            CallRule("msg", key = KeyLocation.Descriptor("id"), importedFrom = listOf(LINGUI_PACKAGES)),
            CallRule("i18n._"),
        ),
    )

    /**
     * svelte-i18n: `$_('key')`, its aliases `$t` and `$format`, `$_({ id: 'key' })`, and `$json('nav')`,
     * which returns the messages under a key. `_` is lodash's and `$t` vue-i18n's: svelte-i18n's only
     * once the file imports it.
     */
    val SVELTE_I18N = FrameworkRules(
        id = "svelte-i18n",
        calls = listOf("_", "\$_", "\$t", "\$format", "\$json").flatMap { name ->
            val gate = if (name in SVELTE_IMPORT_GATED) listOf(SVELTE_I18N_PACKAGE) else emptyList()
            listOf(CallRule(name, importedFrom = gate), CallRule(name, key = KeyLocation.Descriptor("id"), importedFrom = gate))
        },
    )

    /** vue-i18n: the global `$t`, `$tc`, `$te`, the `t` / `te` of `useI18n()`, `<i18n-t keypath>` and `v-t`. */
    val VUE_I18N = FrameworkRules(
        id = "vue-i18n",
        calls = listOf("\$t", "\$tc", "\$te").map { CallRule(it) },
        hooks = listOf(HookRule("useI18n", HookGives.NAMESPACES)),
        components = listOf(ComponentRule(tag = "i18n-t", keyAttribute = "keypath")),
    )

    /** Transloco: the `transloco` pipe, the standalone `translate()` and `translateSignal()` once imported; the service and `read` prefix stay code. */
    val TRANSLOCO = FrameworkRules(
        id = "transloco",
        calls = listOf("translate", "translateSignal").map { CallRule(it, importedFrom = TRANSLOCO_PACKAGES) },
        pipes = listOf(PipeRule("transloco")),
    )

    /** ngx-translate: the `translate` pipe; `translate.instant`, `.get` and `.stream` on the service stay code. */
    val NGX_TRANSLATE = FrameworkRules(
        id = "ngx-translate",
        pipes = listOf(PipeRule("translate")),
    )

    val ALL: List<FrameworkRules> = listOf(I18NEXT, NEXT_INTL, REACT_INTL, LINGUI, SVELTE_I18N, VUE_I18N, TRANSLOCO, NGX_TRANSLATE)

    /** The rules of the framework a preset names, null for an unknown preset. */
    fun byId(id: String?): FrameworkRules? = ALL.firstOrNull { it.id == id }

    /**
     * The bare names read only in a file importing a framework, with the packages that do: `msg` →
     * `@lingui/`. A name another framework reads unconditionally is not one — svelte-i18n's `$t` is
     * gated, vue-i18n's is not, so `$t` is read everywhere and the gate is svelte-i18n's own to apply.
     */
    val IMPORT_GATED_NAMES: Map<String, List<String>>
        get() {
            val ungated = ALL.flatMap { rules -> rules.calls.filter { it.importedFrom.isEmpty() }.map { it.callee } }.toSet()
            return ALL.flatMap { it.importGatedNames.entries }.filter { it.key !in ungated }
                .groupBy({ it.key }, { it.value }).mapValues { it.value.flatten().distinct() }
        }
}
