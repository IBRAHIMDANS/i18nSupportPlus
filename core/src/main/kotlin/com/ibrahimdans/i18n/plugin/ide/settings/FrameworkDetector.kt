package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Maps the dependencies of a manifest — `package.json`, `composer.json` for Laravel and Symfony, a Maven or Gradle
 * build for Spring — to the i18n frameworks the setup wizard knows about, and an `angular.json` listing its
 * translations to Angular's own i18n.
 *
 * Deliberately free of any UI dependency: the wizard itself cannot be instantiated in a
 * headless test, so keeping the detection here is what makes it directly testable.
 */
object FrameworkDetector {

    /** Angular's own i18n: `i18n="@@id"` markers, `$localize`, the XLIFF files `angular.json` lists. */
    const val ANGULAR_I18N = "angular-i18n"

    /**
     * A project of an `angular.json` listing its translations: `"i18n": { "sourceLocale": …, "locales": { … } }`.
     * `sourceLocale` alone only sets the locale data of an app another library translates; it may be an
     * object (`{ "code": "en", "baseHref": "" }`), hence one level of braces allowed before `locales`.
     */
    private val ANGULAR_I18N_LOCALES = Regex(""""i18n"\s*:\s*\{(?:[^{}]|\{[^{}]*\})*"locales"\s*:""")

    /**
     * Spring and its resource bundles (`messages_fr.properties`), read by `MessageSource.getMessage`. IDEA resolves
     * those calls itself; the preset only names the framework and documents no layout, a bundle being placed by its name.
     */
    const val SPRING = "spring"

    /**
     * A Maven or Gradle build naming a Spring artifact: `<groupId>org.springframework.boot</groupId>`,
     * `id("org.springframework.boot")`, `implementation 'org.springframework:spring-context:6.1.0'`. Every Spring
     * application has `spring-context`, the `MessageSource` with it. No dependency is quoted alone in a `pom.xml`.
     */
    private val SPRING_BUILD = Regex("""\borg\.springframework\b""")

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
     * Symfony is given away by its translation component, which `composer require translation` adds:
     * a Symfony application without it translates nothing. Laravel's own `composer.json` never names it,
     * the component coming in through `laravel/framework`.
     *
     * Angular's own i18n needs `@angular/localize`, which `ng extract-i18n` and a localized build require;
     * an `angular.json` listing translations gives it away as well ([detect]).
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
        "laravel" to listOf("laravel/framework"),
        "symfony" to listOf("symfony/translation"),
        ANGULAR_I18N to listOf("@angular/localize"),
        SPRING to listOf("org.springframework.boot", "org.springframework")
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
        "laravel" to "Laravel (PHP)",
        "symfony" to "Symfony (PHP, Twig)",
        ANGULAR_I18N to "Angular i18n (native, XLIFF)",
        SPRING to "Spring (Java resource bundles)"
    )

    /**
     * Returns the frameworks whose dependencies appear in [packageJsonContent], the text of a
     * `package.json` or of a `composer.json` — and Angular's own i18n for an `angular.json` listing
     * translations, and Spring for a `pom.xml` or a `build.gradle(.kts)` naming a Spring artifact.
     *
     * Matching is textual on the *quoted* dependency name, so a longer package sharing a
     * prefix — `react-intl-universal` against `react-intl` — never counts as a match.
     */
    fun detect(packageJsonContent: String): Set<String> {
        val byDependency = FRAMEWORK_KEYS
            .filterValues { deps -> deps.any { packageJsonContent.contains("\"$it\"") } }
            .keys
        val angular = if (ANGULAR_I18N_LOCALES.containsMatchIn(packageJsonContent)) setOf(ANGULAR_I18N) else emptySet()
        val spring = if (SPRING_BUILD.containsMatchIn(packageJsonContent)) setOf(SPRING) else emptySet()
        return byDependency + angular + spring
    }
}
