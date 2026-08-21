package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Maps `package.json` dependencies to the i18n frameworks the setup wizard knows about.
 *
 * Deliberately free of any UI dependency: the wizard itself cannot be instantiated in a
 * headless test, so keeping the detection here is what makes it directly testable.
 */
object FrameworkDetector {

    /** Framework id to the dependency names that give it away. */
    val FRAMEWORK_KEYS: Map<String, List<String>> = mapOf(
        "i18next" to listOf("i18next", "react-i18next"),
        "vue-i18n" to listOf("vue-i18n"),
        "lingui" to listOf("@lingui/core", "@lingui/react", "@lingui/macro", "@lingui/react/macro"),
        "react-intl" to listOf("react-intl", "@formatjs/intl")
    )

    /** Framework id to the label shown on the wizard's checkbox. */
    val LABELS: Map<String, String> = mapOf(
        "i18next" to "i18next / react-i18next",
        "vue-i18n" to "vue-i18n",
        "lingui" to "lingui",
        "react-intl" to "react-intl (FormatJS)"
    )

    /**
     * Returns the frameworks whose dependencies appear in [packageJsonContent].
     *
     * Matching is textual on the *quoted* dependency name, so a longer package sharing a
     * prefix — `react-intl-universal` against `react-intl` — never counts as a match.
     */
    fun detect(packageJsonContent: String): Set<String> =
        FRAMEWORK_KEYS
            .filterValues { deps -> deps.any { packageJsonContent.contains("\"$it\"") } }
            .keys
}
