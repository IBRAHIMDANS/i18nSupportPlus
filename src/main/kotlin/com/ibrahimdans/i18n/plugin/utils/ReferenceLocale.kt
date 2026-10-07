package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig

/**
 * The locale a module translates from, the one other locales are compared against.
 *
 * It used to be computed five ways: the inspections took the module's locale then `en`, the
 * extraction the module's then the preview locale, *Generate i18next Types* matched `en` to
 * `en-US` where the inspections compared labels with `==`, and the Stats tab ignored the module.
 * On `en-US` files with no locale declared, *Translation key missing from a locale* and the key
 * reuse at extraction stayed silent while the other two worked. Every caller now goes through
 * [of], which applies the order [ModuleConfig.referenceLocale] documents, then [LocaleMatching].
 */
internal object ReferenceLocale {

    private const val DEFAULT = "en"

    /**
     * The locale wanted for [module], as the settings spell it, before it is matched against any
     * file: the module's own, then the preview locale, then the folding language, then `en`.
     */
    fun wanted(module: ModuleConfig?, config: Config): String =
        module?.referenceLocale?.takeIf { it.isNotBlank() }
            ?: config.previewLocale.takeIf { it.isNotBlank() }
            ?: config.foldingPreferredLanguage.takeIf { it.isNotBlank() }
            ?: DEFAULT

    /** The label among [labels] that [wanted] designates — `en` finds `en-US` — or null when none does. */
    fun of(module: ModuleConfig?, config: Config, labels: Collection<String>): String? =
        LocaleMatching.pick(wanted(module, config), labels)

    /**
     * The label among [labels] that [module] itself declares, or null when it declares none or
     * none matches — for a caller with a better fallback than the project-wide settings.
     */
    fun declared(module: ModuleConfig?, labels: Collection<String>): String? =
        module?.referenceLocale?.takeIf { it.isNotBlank() }?.let { LocaleMatching.pick(it, labels) }
}
