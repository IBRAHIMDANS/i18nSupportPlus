package com.ibrahimdans.i18n.plugin.ide.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.ibrahimdans.i18n.plugin.utils.LocaleNames

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

    /**
     * A setting the wizard can infer. The two flags carry no value of their own.
     * [REFERENCE_LOCALE] is the project's reference locale (`Config.foldingPreferredLanguage`).
     */
    enum class Field { DEFAULT_NS, GETTEXT, FLAT_KEYS, PREFERRED_LOCALIZATION, REFERENCE_LOCALE }

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
     *
     * [origin] is the configuration file the root was read from ([I18nextCliConfig], [VueI18nConfig]),
     * null when it was derived from the files.
     */
    data class RootPlan(val detected: String?, val candidates: List<String>, val origin: String? = null) {

        /** True when [detected] is wider than the folders it covers, and must be shown as such. */
        val widened: Boolean get() = detected != null && candidates.size > 1
    }

    /**
     * The separators the i18next-cli configuration [origin] states, each null when it states none.
     * Not a [Deduction]: the summary always shows both separators, pre-filled.
     */
    data class Separators(val key: String?, val ns: String?, val origin: String)

    /**
     * A module proposed for its template alone: the layout of the catalogues, when the reading by
     * default — a locale folder holding namespace files, `{lang}/{ns}.json` — would miss it. The
     * case is a folder per namespace above files named after their locale (`locales/common/en.json`):
     * without a template, `en.json` is read as the locale `en` in no namespace, and `t('common:save')`
     * finds no file.
     *
     * The module has no root directory: it adds a template, it does not cut the project into
     * applications, so the code keeps resolving against every file as before. Its [ModuleConfig.pathTemplate]
     * is project-relative, the translations root included.
     *
     * [origin] is the configuration file the template was read from, null when it was deduced from
     * the files.
     */
    data class TemplatePlan(val module: ModuleConfig, val origin: String?)

    data class Plan(
        val root: RootPlan,
        val deductions: List<Deduction>,
        val separators: Separators? = null,
        val template: TemplatePlan? = null,
    )

    /**
     * What the wizard would propose for [relativeFilePaths] and the ticked [frameworks].
     *
     * An i18next-cli configuration [cliConfig], or a Nuxt / vue-i18n one [vueConfig], wins over the
     * scan for what it states: it is what the project wrote down, where the scan only infers from
     * the files' names. The i18next one comes first when both state a setting.
     */
    fun of(
        relativeFilePaths: List<String>,
        frameworks: Set<String>,
        cliConfig: I18nextCliConfig? = null,
        vueConfig: VueI18nConfig? = null,
    ): Plan {
        val configRoot = cliConfig?.translationsRoot?.let { it to cliConfig.file }
            ?: vueConfig?.translationsRoot?.let { it to vueConfig.file }
        val namespaceFolders = if (configRoot == null) namespaceFoldersOf(relativeFilePaths) else null
        val root = when {
            configRoot != null -> RootPlan(configRoot.first, listOf(configRoot.first), configRoot.second)
            // One folder per namespace is one catalogue, not one application per folder: the
            // detector, which strips locale levels only, would see `locales/common` and
            // `locales/auth` as two roots and offer to split them into modules.
            namespaceFolders != null -> RootPlan(namespaceFolders.first, listOf(namespaceFolders.first))
            else -> RootPlan(
                detected = TranslationRootDetector.detect(relativeFilePaths),
                candidates = TranslationRootDetector.candidates(relativeFilePaths)
            )
        }
        val template = when {
            namespaceFolders != null -> TemplatePlan(templateModule(namespaceFolders.first, namespaceFolders.second), null)
            else -> statedTemplate(cliConfig)
        }
        val stated = listOfNotNull(
            cliConfig?.defaultNs?.let { Deduction(Field.DEFAULT_NS, it, cliConfig.file) },
            cliConfig?.referenceLocale?.let { Deduction(Field.REFERENCE_LOCALE, it, cliConfig.file) },
            vueConfig?.referenceLocale?.let { Deduction(Field.REFERENCE_LOCALE, it, vueConfig.file) },
            vueConfig?.let { config ->
                WizardSettingsDeducer.deduce(config.localeFiles, emptySet()).preferredLocalization
                    ?.let { Deduction(Field.PREFERRED_LOCALIZATION, it, config.file) }
            },
        ).distinctBy { it.field }
        val deductions = stated + deductionsOf(relativeFilePaths, frameworks)
            .filterNot { scanned -> stated.any { it.field == scanned.field } }
        val separators = cliConfig
            ?.takeIf { it.keySeparator != null || it.nsSeparator != null }
            ?.let { Separators(it.keySeparator, it.nsSeparator, it.file) }
        return Plan(root, deductions, separators, template)
    }

    /**
     * How the summary offers a deduction.
     *
     * [HELD] is a setting `i18n-check.json` sets: *Apply* keeps the file's value whatever the form
     * holds (`Settings.setConfig`), so a ticked box there was a value dropped without a word.
     */
    enum class Offer { TICKED, UNTICKED, HELD }

    /** A field the summary pre-fills, and whether `i18n-check.json` holds it. */
    data class Prefill(val value: String, val held: Boolean)

    /** What the summary shows for [plan]; see [formOf]. */
    data class Form(
        val offers: Map<Field, Offer>,
        val root: Prefill,
        val keySeparator: Prefill,
        val nsSeparator: Prefill,
    )

    /** The `i18n-check.json` name of the setting [field] writes, as [ProjectConfigFile] reads it. */
    fun settingName(field: Field): String = when (field) {
        Field.DEFAULT_NS -> "defaultNs"
        Field.GETTEXT -> "gettext"
        Field.FLAT_KEYS -> "flatKeys"
        Field.PREFERRED_LOCALIZATION -> "preferredLocalization"
        Field.REFERENCE_LOCALE -> "foldingPreferredLanguage"
    }

    /**
     * What the summary offers for [plan], given the settings in effect [current] — the stored ones
     * with `i18n-check.json` laid over them — and the names the file [held].
     *
     * A deduction is ticked only while its setting still holds its default: the wizard can be
     * reopened at any time and never overwrites a decision already made. A setting the file holds
     * is neither ticked nor editable, and a field it holds shows the file's value. [autoPickedLocalization]
     * is the format the plugin picks when none is chosen; see [WizardSettingsDeducer.isUntouchedPreferredLocalization].
     */
    fun formOf(plan: Plan, current: Config, held: Set<String>, autoPickedLocalization: String?): Form {
        val defaults = Config()
        val offers = plan.deductions.associate { deduction ->
            val untouched = when (deduction.field) {
                Field.DEFAULT_NS -> current.defaultNs == defaults.defaultNs
                Field.GETTEXT -> current.gettext == defaults.gettext
                Field.FLAT_KEYS -> current.flatKeys == defaults.flatKeys
                Field.PREFERRED_LOCALIZATION ->
                    WizardSettingsDeducer.isUntouchedPreferredLocalization(current.preferredLocalization, autoPickedLocalization)
                Field.REFERENCE_LOCALE -> current.foldingPreferredLanguage == defaults.foldingPreferredLanguage
            }
            deduction.field to when {
                settingName(deduction.field) in held -> Offer.HELD
                untouched -> Offer.TICKED
                else -> Offer.UNTICKED
            }
        }
        // The configuration's separators are offered only over a value nobody chose, as the
        // deductions are: reopening the wizard never undoes a separator set by hand.
        fun separator(name: String, value: String, default: String, stated: String?) = when (name) {
            in held -> Prefill(value, held = true)
            else -> Prefill(stated?.takeIf { value == default } ?: value, held = false)
        }
        return Form(
            offers = offers,
            root = if (TRANSLATIONS_ROOT in held) Prefill(current.translationsRoot, held = true)
            else Prefill(plan.root.detected.orEmpty(), held = false),
            keySeparator = separator("keySeparator", current.keySeparator, defaults.keySeparator, plan.separators?.key),
            nsSeparator = separator("nsSeparator", current.nsSeparator, defaults.nsSeparator, plan.separators?.ns),
        )
    }

    private const val TRANSLATIONS_ROOT = "translationsRoot"

    /**
     * The template an i18next-type configuration states, when it is one the reading by default
     * would miss: a namespace placeholder anywhere but in the file name below a locale folder.
     * `{lang}/{ns}.json` and `{lang}.json` change nothing, and are left out.
     */
    private fun statedTemplate(config: I18nextCliConfig?): TemplatePlan? {
        val root = config?.translationsRoot ?: return null
        val template = config.pathTemplate ?: return null
        if (NS !in template || template.substringBeforeLast('.') == "$LANG/$NS") return null
        return TemplatePlan(templateModule(root, template), config.file)
    }

    private fun templateModule(root: String, template: String) =
        ModuleConfig(name = moduleNameOf(root), pathTemplate = "$root/$template")

    /**
     * The translations root and template of a folder-per-namespace layout, or null when the files
     * do not all follow it: every file `<root>/<namespace>/<locale>.<ext>`, one root that is a
     * translations folder (`locales`, `i18n`…), at least two namespace folders and one extension.
     *
     * The translations folder is what tells a namespace from an application: `packages/web/en.json`
     * and `packages/api/en.json` have the same shape, and are two applications.
     */
    private fun namespaceFoldersOf(paths: List<String>): Pair<String, String>? {
        if (paths.isEmpty()) return null
        val files = paths.map { path -> path.normalizeSeparators().split('/').filter { it.isNotEmpty() } }
        if (files.any { it.size < 3 }) return null
        val stems = files.map { it.last().substringBeforeLast('.', it.last()) }
        val namespaces = files.map { it[it.size - 2] }
        if (stems.any { !LocaleNames.looksLikeLocale(it) } || namespaces.any { LocaleNames.looksLikeLocale(it) }) return null
        val root = files.map { it.dropLast(2).joinToString("/") }.distinct().singleOrNull() ?: return null
        if (root.substringAfterLast('/') !in TranslationFileScanner.FOLDER_NAMES) return null
        if (namespaces.distinct().size < 2) return null
        val extension = files.map { it.last().substringAfterLast('.', "") }.distinct().singleOrNull() ?: return null
        val file = if (extension.isEmpty()) LANG else "$LANG.$extension"
        return root to "$NS/$file"
    }

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
     * A module's root directory is its application's folder, not its translations folder: a code
     * file belongs to the module whose root its path starts with, so a root of `apps/web/locales`
     * left `apps/web/src/App.tsx` in no module, resolved against every application's files. The
     * application is the nearest folder above the catalogues holding one of [manifests]
     * (project-relative folder -> `package.json` / `composer.json` text, the project root left
     * out); without one, the catalogues' root minus its translations and asset folders
     * (`apps/web/public/locales` -> `apps/web`). Two candidates of the same application keep their
     * own roots: one module cannot hold two templates.
     *
     * Each module is given the template its own files actually follow, relative to its root,
     * rather than the generic `{lang}/{ns}.json` a hand-added module starts from: a proposal that
     * resolves to nothing would be one more value presented as configured while configuring
     * nothing. Its preset is the one framework its own manifest declares, else the project's one.
     *
     * A module that is a [workspace] package other packages depend on is given those packages as
     * [ModuleConfig.codeRoots]: a shared translations package holds no code calling its keys, so
     * without them every key reads as unused. See [codeRootsOf].
     */
    fun modulesFor(
        plan: RootPlan,
        relativeFilePaths: List<String>,
        frameworks: Set<String>,
        manifests: Map<String, String> = emptyMap(),
        workspace: Map<String, String> = emptyMap(),
    ): List<ModuleConfig> {
        val appRoots = plan.candidates.associateWith { appRootOf(it, manifests) }
        val shared = appRoots.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return plan.candidates.map { candidate ->
            val root = appRoots.getValue(candidate).takeUnless { it in shared } ?: candidate
            val preset = manifests[root]?.let { FrameworkDetector.detect(it).singleOrNull() }
                ?: frameworks.singleOrNull().orEmpty()
            ModuleConfig(
                name = moduleNameOf(root),
                pathTemplate = pathTemplateOf(root, relativeFilePaths),
                rootDirectory = root,
                preset = preset,
                codeRoots = codeRootsOf(root, workspace)
            )
        }
    }

    /**
     * The folders whose code uses the keys of the [workspace] package at [root] (project-relative
     * folder -> `package.json` text): the packages declaring a dependency on its `name`, sorted.
     * The package itself is kept first when its own manifest declares an i18n library, i.e. when
     * it has code of its own calling its keys. Empty when no package depends on it: a package name
     * alone proves nothing.
     */
    fun codeRootsOf(root: String, workspace: Map<String, String>): String {
        val manifest = workspace[root] ?: return ""
        val name = packageNameOf(manifest) ?: return ""
        val dependents = workspace.filterKeys { it != root }
            .filterValues { name in dependenciesOf(it) }
            .keys.sorted()
        if (dependents.isEmpty()) return ""
        val own = listOf(root).filter { FrameworkDetector.detect(manifest).isNotEmpty() }
        return (own + dependents).joinToString(", ")
    }

    /** The `name` of a `package.json`, null when it has none or is not JSON. */
    fun packageNameOf(packageJson: String): String? =
        parse(packageJson)?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun dependenciesOf(packageJson: String): Set<String> {
        val json = parse(packageJson) ?: return emptySet()
        return DEPENDENCY_FIELDS.flatMapTo(mutableSetOf()) { field ->
            json.get(field)?.takeIf { it.isJsonObject }?.asJsonObject?.keySet().orEmpty()
        }
    }

    private fun parse(packageJson: String): JsonObject? =
        try {
            JsonParser.parseString(packageJson).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonParseException) {
            null
        }

    private val DEPENDENCY_FIELDS = listOf("dependencies", "devDependencies", "peerDependencies")

    /** The folder of the application whose catalogues sit in [catalogueRoot]; see [modulesFor]. */
    private fun appRootOf(catalogueRoot: String, manifests: Map<String, String>): String {
        val segments = catalogueRoot.split('/').filter { it.isNotEmpty() }
        (segments.size downTo 1).map { segments.take(it).joinToString("/") }
            .firstOrNull { it in manifests }
            ?.let { return it }
        var kept = segments
        while (kept.size > 1 && kept.last() in NOT_AN_APPLICATION) kept = kept.dropLast(1)
        return kept.joinToString("/")
    }

    /**
     * The folders a catalogue sits in that do not name an application: the translations folders,
     * and the asset and source folders holding them (`public/locales`, `src/i18n`, `resources/lang`).
     */
    private val NOT_AN_APPLICATION = TranslationFileScanner.FOLDER_NAMES + setOf("public", "src", "assets", "static", "resources")

    /**
     * The segment that names the module: the application's folder, the root's last segment that
     * is no translations or asset folder (`apps/web/public/locales` is the web app's catalogue,
     * not a module called "locales" or "public"). A root that is nothing but such folders keeps
     * its own name.
     */
    private fun moduleNameOf(root: String): String {
        val segments = root.split('/').filter { it.isNotEmpty() }
        return segments.lastOrNull { it !in NOT_AN_APPLICATION }
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
            if (LocaleNames.looksLikeLocale(it)) LANG else it
        }
        // A "one file per locale" layout names its files after locales, not namespaces —
        // `locales/fr.json` resolves through {lang}.json, never {ns}.json.
        val name = if (LocaleNames.looksLikeLocale(stem)) LANG else NS
        val file = if (extension.isEmpty()) name else "$name.$extension"
        return if (folders.isEmpty()) file else "$folders/$file"
    }

    private const val LANG = "{lang}"
    private const val NS = "{ns}"

    /** The scanner returns platform-separated paths; every rule here reasons in `/`. */
    private fun String.normalizeSeparators(): String = replace('\\', '/')
}
