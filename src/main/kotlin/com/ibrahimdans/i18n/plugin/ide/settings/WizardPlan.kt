package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService

/**
 * What the setup wizard is about to propose, and the evidence behind each value.
 *
 * The summary used to be a concatenated HTML string stating "Translations root: apps" in the
 * same tone as a fact the user had typed. Two things were missing from it: *why* a value was
 * picked, and *whether it was a guess at all* — [TranslationRootDetector] widens to the longest
 * common prefix when the files disagree, and said so only in its own KDoc.
 *
 * This object answers both, as data. Nothing here re-derives a setting: values come from
 * [WizardSettingsDeducer] and [TranslationRootDetector] unchanged, and what is added is the
 * origin. Origins are raw — a project-relative path, or a framework id — never sentences, for
 * the reason [ModuleTemplateResolver] returns issues rather than messages: localizing them is
 * the caller's job.
 *
 * UI-free like every other rule the wizard leans on: [SetupWizardDialog] cannot be instantiated
 * headlessly, so a rule left inside it is a rule no test ever runs.
 */
object WizardPlan {

    /** A setting the wizard can infer. The two flags carry no value of their own. */
    enum class Field { DEFAULT_NS, GETTEXT, FLAT_KEYS, PREFERRED_LOCALIZATION }

    /**
     * One inferred setting, with what it was read from.
     *
     * [value] is empty for [Field.GETTEXT] and [Field.FLAT_KEYS], which are switches: the field
     * itself is the whole statement. [origin] is a scanned file path, except for
     * [Field.FLAT_KEYS], which follows a ticked framework and carries its id.
     */
    data class Deduction(val field: Field, val value: String, val origin: String)

    /**
     * The translations root, and the folders it was derived from.
     *
     * [candidates] holds one entry per distinct folder the files point at. More than one means
     * [detected] is a compromise wider than any of them — a monorepo holding `apps/web/locales`
     * and `apps/api/locales` lands on `apps`, which contains far more than translations.
     */
    data class RootPlan(val detected: String?, val candidates: List<String>) {

        /** True when [detected] is wider than the folders it covers, and must be shown as such. */
        val widened: Boolean get() = detected != null && candidates.size > 1
    }

    data class Plan(val root: RootPlan, val deductions: List<Deduction>)

    /** What the wizard would propose for [relativeFilePaths] and the ticked [frameworks]. */
    fun of(relativeFilePaths: List<String>, frameworks: Set<String>): Plan =
        Plan(
            root = RootPlan(
                detected = TranslationRootDetector.detect(relativeFilePaths),
                candidates = TranslationRootDetector.candidates(relativeFilePaths)
            ),
            deductions = deductionsOf(relativeFilePaths, frameworks)
        )

    private fun deductionsOf(paths: List<String>, frameworks: Set<String>): List<Deduction> {
        val deduced = WizardSettingsDeducer.deduce(paths, frameworks)
        return buildList {
            deduced.defaultNs?.let {
                add(Deduction(Field.DEFAULT_NS, it, evidenceFor(paths, frameworks) { d -> d.defaultNs == it }))
            }
            deduced.gettext?.let {
                add(Deduction(Field.GETTEXT, "", evidenceFor(paths, frameworks) { d -> d.gettext == true }))
            }
            deduced.flatKeys?.let { add(Deduction(Field.FLAT_KEYS, "", REACT_INTL)) }
            deduced.preferredLocalization?.let {
                add(
                    Deduction(
                        Field.PREFERRED_LOCALIZATION,
                        it,
                        evidenceFor(paths, frameworks) { d -> d.preferredLocalization == it }
                    )
                )
            }
        }
    }

    /**
     * The first scanned file that, on its own, yields the same answer as the whole scan.
     *
     * Asking [WizardSettingsDeducer] one file at a time is what keeps this honest: a second
     * copy of "a `.po` means GetText, a file stem that is not a locale is a namespace" would
     * be a rule the tests exercise while the shipped one drifts — the trap #155 removed from
     * framework detection.
     */
    private fun evidenceFor(
        paths: List<String>,
        frameworks: Set<String>,
        matches: (WizardSettingsDeducer.Deduced) -> Boolean
    ): String = paths.firstOrNull { matches(WizardSettingsDeducer.deduce(listOf(it), frameworks)) } ?: ""

    private const val REACT_INTL = "react-intl"

    /**
     * One module per candidate root, for the monorepo the single root would flatten.
     *
     * Each module is given the template its own files actually follow, rather than the generic
     * `{lang}/{ns}.json` a hand-added module starts from: a proposal that resolves to nothing
     * would be one more value presented as configured while configuring nothing.
     */
    fun modulesFor(plan: RootPlan, relativeFilePaths: List<String>, frameworks: Set<String>): List<ModuleConfig> {
        val preset = frameworks.singleOrNull().orEmpty()
        return plan.candidates.map { root ->
            ModuleConfig(
                name = moduleNameOf(root),
                pathTemplate = pathTemplateOf(root, relativeFilePaths),
                rootDirectory = root,
                preset = preset
            )
        }
    }

    /**
     * The segment that names the module, which is the one *above* the translations folder:
     * `apps/web/locales` is the web app's catalogue, not a module called "locales". A root that
     * is nothing but a translations folder keeps its own name.
     */
    private fun moduleNameOf(root: String): String {
        val segments = root.split('/').filter { it.isNotEmpty() }
        return segments.lastOrNull { it !in TranslationFileScanner.FOLDER_NAMES }
            ?: segments.lastOrNull()
            ?: root
    }

    /**
     * The template the files under [root] follow, as the most frequent shape among them.
     *
     * Ties are broken alphabetically so the proposal never depends on the order the file system
     * returned, the same reason [WizardSettingsDeducer] sorts its own contenders.
     */
    private fun pathTemplateOf(root: String, relativeFilePaths: List<String>): String {
        val prefix = "$root/"
        val shapes = relativeFilePaths
            .map { it.normalizeSeparators() }
            .filter { it.startsWith(prefix) }
            .map { templateOf(it.removePrefix(prefix)) }
        if (shapes.isEmpty()) return ""
        return shapes.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
    }

    /** [remainder] with its locale folders and its file name turned back into placeholders. */
    private fun templateOf(remainder: String): String {
        val segments = remainder.split('/').filter { it.isNotEmpty() }
        val fileName = segments.last()
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")

        val folders = segments.dropLast(1).joinToString("/") {
            if (LocalizationSourceService.looksLikeLocale(it)) LANG else it
        }
        // A "one file per locale" layout names its files after locales, not namespaces —
        // `locales/fr.json` resolves through {lang}.json, never {ns}.json.
        val name = if (LocalizationSourceService.looksLikeLocale(stem)) LANG else NS
        val file = if (extension.isEmpty()) name else "$name.$extension"
        return if (folders.isEmpty()) file else "$folders/$file"
    }

    private const val LANG = "{lang}"
    private const val NS = "{ns}"

    /** The scanner returns platform-separated paths; every rule here reasons in `/`. */
    private fun String.normalizeSeparators(): String = replace('\\', '/')
}
