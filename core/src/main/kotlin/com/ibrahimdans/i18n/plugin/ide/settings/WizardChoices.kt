package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * What the user settled on in the wizard's summary, and what *Apply* writes from it.
 *
 * The wizard used to re-derive everything at *Apply* time, straight from the scan: whatever the
 * summary had displayed, the deduction ran again and won. That is workable while the summary is
 * a read-only paragraph, and wrong as soon as it becomes a form — a root corrected on screen has
 * to be the root that is stored.
 *
 * A null field means "leave this setting alone", never "write the default back". Emptiness is
 * therefore expressed by null, not by an empty string, exactly as `WizardSettingsDeducer.Deduced`
 * does for the same reason.
 *
 * Free of Swing so [applyTo] is testable on a [ConfigStore] held in memory, while the dialog
 * holding the form cannot be instantiated headlessly at all.
 */
data class WizardChoices(
    val translationsRoot: String? = null,
    val modules: List<ModuleConfig> = emptyList(),
    val defaultNs: String? = null,
    val gettext: Boolean? = null,
    val flatKeys: Boolean? = null,
    val preferredLocalization: String? = null,
    /** The project's reference locale, stored as `foldingPreferredLanguage`: see `ReferenceLocale.wanted`. */
    val referenceLocale: String? = null,
    val keySeparator: String? = null,
    val nsSeparator: String? = null,
    /** The module carrying the catalogues' template ([WizardPlan.TemplatePlan]), added beside the root. */
    val templateModule: ModuleConfig? = null
) {

    /**
     * Writes the chosen values into [store], and nothing else.
     *
     * Modules and the root are alternatives rather than additions: [modules] is how the user
     * answers a root that was widened to cover several catalogues, and storing the wide root
     * alongside them would put back the very value they declined.
     *
     * A module whose root directory is already configured is skipped: the wizard can be reopened
     * at any time, and running it twice must not leave two entries pointing at one folder.
     *
     * [templateModule] is no alternative to the root: it has no root directory and only tells how
     * the files under the root are laid out. It is skipped when a module already resolves the same
     * template.
     */
    fun applyTo(store: ConfigStore) {
        val config = store.config()
        val configured = config.modules.map { it.rootDirectory.trim().trim('/') }.toSet()
        val templates = config.modules.map { ModuleTemplateResolver.combine(it) }.toSet()
        val added = modules.filterNot { it.rootDirectory.trim().trim('/') in configured } +
            listOfNotNull(templateModule?.takeUnless { ModuleTemplateResolver.combine(it) in templates })
        store.setConfig(
            config.copy(
                modules = config.modules + added,
                translationsRoot = translationsRoot.takeIf { modules.isEmpty() }.nonBlank() ?: config.translationsRoot,
                defaultNs = defaultNs.nonBlank() ?: config.defaultNs,
                gettext = gettext ?: config.gettext,
                flatKeys = flatKeys ?: config.flatKeys,
                preferredLocalization = preferredLocalization.nonBlank() ?: config.preferredLocalization,
                foldingPreferredLanguage = referenceLocale.nonBlank() ?: config.foldingPreferredLanguage,
                // Separators are the one pair the wizard never deduces: they are offered pre-filled with
                // what is already stored, so a blank one means the field was emptied, not left alone.
                keySeparator = keySeparator.nonBlank() ?: config.keySeparator,
                nsSeparator = nsSeparator.nonBlank() ?: config.nsSeparator
            )
        )
    }

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
}
