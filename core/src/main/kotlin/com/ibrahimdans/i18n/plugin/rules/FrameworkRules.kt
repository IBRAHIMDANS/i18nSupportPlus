package com.ibrahimdans.i18n.plugin.rules

/**
 * How one i18n framework writes its keys in the code, as data: the one description both pipelines
 * read — the plugin on the PSI, `i18n-check` on tree-sitter — so a framework is written once.
 *
 * A [CallRule] names a function taking a key and where the key sits in the call; a [HookRule] names
 * what binds the `t` of a component and what its argument means; a [ComponentRule] a JSX tag whose
 * attribute holds a key; a [PipeRule] a template pipe. [FrameworkPresets] holds the frameworks.
 *
 * What a rule cannot say stays code, on each side: a message built from source text (Lingui's
 * `<Trans>Hello</Trans>`), a key that is a member path (Paraglide's `m.hello()`, typesafe-i18n's
 * `LL.home.title()`), a prefix taken from an enclosing template block (Transloco's `read`), a
 * method only read on a service variable (ngx-translate's `translate.instant`), and the markup of
 * Vue, Svelte, Angular and Blade templates.
 *
 * @param id the framework as a module's preset names it (`i18next`, `react-intl`), the `frameworkId` of its technology
 */
data class FrameworkRules(
    val id: String,
    val calls: List<CallRule> = emptyList(),
    val hooks: List<HookRule> = emptyList(),
    val components: List<ComponentRule> = emptyList(),
    val pipes: List<PipeRule> = emptyList(),
) {

    /** The functions the framework translates with, as a technology publishes them: `t`, `i18n.t`, `formatMessage`. */
    val callNames: List<String> get() = calls.map { it.callee }.distinct()

    /** The bare names read only in a file importing the framework: `msg` → `@lingui/`, `_` → `svelte-i18n`. */
    val importGatedNames: Map<String, List<String>>
        get() = calls.filter { it.importedFrom.isNotEmpty() }.groupBy({ it.callee }, { it.importedFrom }).mapValues { it.value.flatten().distinct() }
}

/** Where a call holds its key. */
sealed interface KeyLocation {

    /** The call's argument at [index], a string literal: `t('key')`. */
    data class Argument(val index: Int = 0) : KeyLocation

    /** The [property] of the object literal passed at [index]: react-intl's `formatMessage({ id: 'key' })`. */
    data class Descriptor(val property: String = "id", val index: Int = 0) : KeyLocation

    /**
     * The [property] of the object passed first, or of each object it holds: react-intl's
     * `defineMessage({ id })` and `defineMessages({ greeting: { id } })`.
     */
    data class Catalogue(val property: String = "id") : KeyLocation
}

/** How a rule's [CallRule.callee] is compared with the called function as written. */
enum class CalleeMatch {

    /** The whole callee: `i18n.t` reads `i18n.t('k')`, not `toast.t('k')`. */
    EXACT,

    /** The callee or its last member: `formatMessage` reads `intl.formatMessage(…)` and `this.props.intl.formatMessage(…)` too. */
    SUFFIX,
}

/**
 * A function taking a key.
 *
 * @param callee the function as written, `t`, `i18n._`, `formatMessage`
 * @param match how [callee] is compared with the call
 * @param key where the call holds its key
 * @param namespaceOption the property of the options object, passed after the key, naming its namespace(s): i18next's `ns`
 * @param contextOption the property of the options object naming the key's context: i18next's `context`
 * @param importedFrom package prefixes one of which the file must import for the call to be read: lodash's `_` is not svelte-i18n's
 */
data class CallRule(
    val callee: String,
    val match: CalleeMatch = CalleeMatch.EXACT,
    val key: KeyLocation = KeyLocation.Argument(),
    val namespaceOption: String? = null,
    val contextOption: String? = null,
    val importedFrom: List<String> = emptyList(),
)

/** What a hook's first argument gives the keys of the `t` it binds. */
enum class HookGives {

    /** Namespaces, a string or an array: react-i18next's `useTranslation(['a', 'b'])`. */
    NAMESPACES,

    /** A key prefix, every key read under it: next-intl's `useTranslations('Home')`. */
    KEY_PREFIX,
}

/**
 * A hook binding a translation function: `const { t } = useTranslation('ns', { keyPrefix })`,
 * `const t = useTranslations('Home')`.
 *
 * @param name the hook as written
 * @param gives what its first argument means for the keys
 * @param keyPrefixOption the property of its options object, passed second, prefixing every key: react-i18next's `keyPrefix`
 * @param namespaceProperty the property of an object passed first holding the argument's value: next-intl's `getTranslations({ namespace })`
 * @param methods the methods of the bound `t` that take a key too: next-intl's `t.rich`, `t.markup`, `t.raw`, `t.has`
 */
data class HookRule(
    val name: String,
    val gives: HookGives,
    val keyPrefixOption: String? = null,
    val namespaceProperty: String? = null,
    val methods: Set<String> = emptySet(),
)

/**
 * A JSX component whose attribute holds a key: `<FormattedMessage id="key" />`, `<Trans i18nKey="key">`.
 *
 * @param tag the component's name, null for the attribute on any tag
 * @param keyAttribute the attribute holding the key
 * @param contextAttribute the attribute naming the key's context, when the framework has one
 */
data class ComponentRule(
    val tag: String?,
    val keyAttribute: String,
    val contextAttribute: String? = null,
)

/** A template pipe taking the key as its input: `{{ 'key' | translate }}`. */
data class PipeRule(val name: String)
