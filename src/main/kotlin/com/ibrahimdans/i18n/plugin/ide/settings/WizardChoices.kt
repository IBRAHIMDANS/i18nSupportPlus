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
 * therefore expressed by null, not by an empty string, exactly as [WizardSettingsDeducer.Deduced]
 * does for the same reason.
 *
 * Free of Swing so [applyTo] is testable: [Settings] has a no-arg constructor and needs no
 * project, while the dialog holding the form cannot be instantiated headlessly at all.
 */
data class WizardChoices(
    val translationsRoot: String? = null,
    val modules: List<ModuleConfig> = emptyList(),
    val defaultNs: String? = null,
    val gettext: Boolean? = null,
    val flatKeys: Boolean? = null,
    val preferredLocalization: String? = null,
    val keySeparator: String? = null,
    val nsSeparator: String? = null
) {

    /**
     * Writes the chosen values into [settings], and nothing else.
     *
     * Modules and the root are alternatives rather than additions: [modules] is how the user
     * answers a root that was widened to cover several catalogues, and storing the wide root
     * alongside them would put back the very value they declined.
     *
     * A module whose root directory is already configured is skipped: the wizard can be reopened
     * at any time, and running it twice must not leave two entries pointing at one folder.
     */
    fun applyTo(settings: Settings) {
        if (modules.isNotEmpty()) {
            val configured = settings.modules.map { it.rootDirectory.trim().trim('/') }.toSet()
            settings.modules.addAll(modules.filterNot { it.rootDirectory.trim().trim('/') in configured })
        } else {
            translationsRoot?.takeIf { it.isNotBlank() }?.let { settings.translationsRoot = it }
        }

        defaultNs?.takeIf { it.isNotBlank() }?.let { settings.defaultNs = it }
        gettext?.let { settings.gettext = it }
        flatKeys?.let { settings.flatKeys = it }
        preferredLocalization?.takeIf { it.isNotBlank() }?.let { settings.preferredLocalization = it }
        // Separators are the one pair the wizard never deduces: they are offered pre-filled with
        // what is already stored, so a blank one means the field was emptied, not left alone.
        keySeparator?.takeIf { it.isNotBlank() }?.let { settings.keySeparator = it }
        nsSeparator?.takeIf { it.isNotBlank() }?.let { settings.nsSeparator = it }
    }
}
