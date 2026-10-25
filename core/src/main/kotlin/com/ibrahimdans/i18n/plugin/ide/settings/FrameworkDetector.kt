package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Maps the dependencies of a manifest — `package.json`, or `composer.json` for Laravel — to the i18n
 * frameworks the setup wizard knows about.
 *
 * Deliberately free of any UI dependency: the wizard itself cannot be instantiated in a
 * headless test, so keeping the detection here is what makes it directly testable.
 */
object FrameworkDetector {

    /**
     * Framework id to the dependency names that give it away.
     *
     * A Nuxt project depends on `@nuxtjs/i18n`, which bundles `vue-i18n` without the project
     * declaring it.
     *
     * Paraglide is given away by its compiler or one of its framework adapters, never by
     * `@inlang/sdk`, which other inlang tools depend on too.
     *
     * Laravel is a Composer package: `laravel/framework` is matched in `composer.json`. Composer names
     * are `vendor/package`, which no npm dependency spells unscoped, so one table serves both manifests.
     */
    val FRAMEWORK_KEYS: Map<String, List<String>> = mapOf(
        "i18next" to listOf("i18next", "react-i18next"),
        "vue-i18n" to listOf("vue-i18n", "@nuxtjs/i18n"),
        "lingui" to listOf("@lingui/core", "@lingui/react", "@lingui/macro", "@lingui/react/macro"),
        "react-intl" to listOf("react-intl", "@formatjs/intl"),
        "ngx-translate" to listOf("@ngx-translate/core"),
        "transloco" to listOf("@jsverse/transloco", "@ngneat/transloco"),
        "svelte-i18n" to listOf("svelte-i18n"),
        "i18n-js" to listOf("i18n-js"),
        "next-intl" to listOf("next-intl"),
        "paraglide" to listOf("@inlang/paraglide-js", "@inlang/paraglide-sveltekit", "@inlang/paraglide-next", "@inlang/paraglide-astro"),
        "laravel" to listOf("laravel/framework")
    )

    /** Framework id to the label shown on the wizard's checkbox. */
    val LABELS: Map<String, String> = mapOf(
        "i18next" to "i18next / react-i18next",
        "vue-i18n" to "vue-i18n",
        "lingui" to "Lingui",
        "react-intl" to "react-intl (FormatJS)",
        "ngx-translate" to "ngx-translate (Angular)",
        "transloco" to "Transloco (Angular)",
        "svelte-i18n" to "svelte-i18n",
        "i18n-js" to "i18n-js (React Native / Expo)",
        "next-intl" to "next-intl",
        "paraglide" to "Paraglide JS (inlang)",
        "laravel" to "Laravel (PHP)"
    )

    /**
     * Returns the frameworks whose dependencies appear in [packageJsonContent], the text of a
     * `package.json` or of a `composer.json`.
     *
     * Matching is textual on the *quoted* dependency name, so a longer package sharing a
     * prefix — `react-intl-universal` against `react-intl` — never counts as a match.
     */
    fun detect(packageJsonContent: String): Set<String> =
        FRAMEWORK_KEYS
            .filterValues { deps -> deps.any { packageJsonContent.contains("\"$it\"") } }
            .keys
}
