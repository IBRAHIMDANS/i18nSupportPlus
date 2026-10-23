package com.ibrahimdans.i18n

/**
 * An i18n framework: the calls it translates with, and what its configuration says about the
 * project's translations.
 *
 * [N] and [F] are those of the [TranslationSource]s it declares — a PSI element and a `Localization`
 * in the plugin, whose `Technology` fixes them. A technology declaring no source fixes both to
 * [Nothing] ([com.ibrahimdans.i18n.extensions.technology.SimpleTechnology]) and so is one of any.
 */
interface TranslationTechnology<out N, out F : TranslationFormat> {
    fun translationFunctionNames(): List<String>

    // N is only ever read from the sources returned: a technology of Nothing declares none.
    fun findSourcesByConfiguration(workspace: Workspace): List<TranslationSource<@UnsafeVariance N, F>>
    fun initialize(workspace: Workspace)
    fun cfgNamespaces(): List<String>

    /**
     * The framework id this technology implements, as [com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector]
     * names it (`i18next`, `vue-i18n`): what a module's preset selects. Null for a technology no preset names.
     */
    fun frameworkId(): String? = null

    /**
     * Namespaces a key may name as its first segment, written with the *key* separator: Transloco's
     * scopes (`admin.title` for the key `title` of the scope `admin`). Such a segment is read as the
     * namespace only when it is one of these; any other key is parsed as before.
     */
    fun keyScopes(workspace: Workspace): Set<String> = emptySet()

    /** The scope [file] holds, for a translation file of one of [keyScopes]; null otherwise. */
    fun keyScopeOf(workspace: Workspace, file: WorkspaceFile): String? = null

    /** The namespaces the framework's configuration falls back to (i18next's `fallbackNS`), in order. */
    fun fallbackNamespaces(workspace: Workspace): List<String> = emptyList()
}
