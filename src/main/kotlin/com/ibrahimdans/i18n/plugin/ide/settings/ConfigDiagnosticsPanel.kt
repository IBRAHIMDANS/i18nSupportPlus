package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.extensions.technology.i18next.I18NextTechnology
import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import java.awt.Component
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JPanel

/** The state of a configured translation directory, as far as a diagnostic is concerned. */
enum class DirectoryState { MISSING, WITHOUT_TRANSLATIONS, OK }

/**
 * Reads the state of one configured directory.
 *
 * Exists so that [ConfigDiagnostics] never touches a file system: the checks are the part
 * worth testing, and a virtual file system needs a running IDE to answer at all.
 */
fun interface DirectoryProbe {
    fun inspect(path: String): DirectoryState
}

/** The kind of problem a [ConfigDiagnostic] reports. */
enum class ConfigIssue {
    ROOT_NOT_CONFIGURED,
    ROOT_MISSING,
    ROOT_WITHOUT_TRANSLATIONS,
    DEFAULT_NS_EMPTY,
    KEY_SEPARATOR_EMPTY,

    /**
     * A stored separator [SeparatorRules] refuses — several characters, a refused one, the namespace
     * and key separators alike: `i18n-check.json` and settings saved before the rule can hold one.
     */
    SEPARATOR_INVALID,

    /** No locale has a file for the default namespace: every key without a prefix is unresolved. */
    DEFAULT_NS_NO_FILE,

    /** Not a problem: the blank default namespace is read from the project's i18next initialisation. */
    DEFAULT_NS_FROM_CODE
}

/**
 * One problem found in a [Config].
 *
 * [module] names the module the problem belongs to, or is `null` when the problem comes from
 * the project-wide settings. A path on its own does not tell the reader which of several
 * module rows to go and fix, which is the whole point of naming it.
 */
data class ConfigDiagnostic(
    val issue: ConfigIssue,
    val module: String? = null,
    val path: String = "",
    val suggestion: String? = null,
    val origin: String? = null,
    /** The problem a [ConfigIssue.SEPARATOR_INVALID] reports, null for every other issue. */
    val separator: SeparatorRules.Problem? = null
)

/**
 * What the translation files and the project's i18next initialisation say of the namespaces, for the
 * default namespace checks: [byLocale], the namespaces each locale has a file for; [holdsDefaultByName],
 * whether a file named after its locale (`en.json`) holds the default namespace whatever it is called;
 * [code], what the i18next initialisation states; [readFromCode], whether the default namespace in use
 * was read from it, the setting being blank. Empty when unknown: nothing is then reported.
 */
data class NamespaceFacts(
    val byLocale: Map<String, Set<String>> = emptyMap(),
    val holdsDefaultByName: Boolean = false,
    val code: CodeDefaults? = null,
    val readFromCode: Boolean = false
)

/**
 * The configuration checks, with no user interface attached.
 *
 * Kept apart from [ConfigDiagnosticsPanel] so the rules can be exercised headlessly against a
 * stub [DirectoryProbe]; the panel only turns the result into banners.
 */
object ConfigDiagnostics {

    /** Every problem [config] currently has, in the order they should be shown. */
    fun inspect(config: Config, probe: DirectoryProbe, facts: NamespaceFacts = NamespaceFacts()): List<ConfigDiagnostic> = buildList {
        val modules = config.modules.filter { Config.normalizeRoot(it.rootDirectory).isNotEmpty() }

        // Read as the scan reads it: `./` names no root, `./locales` and `/locales` name `locales`.
        if (config.normalizedTranslationsRoot().isEmpty()) {
            // Modules carrying their own roots make the project-wide one redundant, so its
            // absence is only worth reporting when nothing else points at the translations.
            if (modules.isEmpty()) add(ConfigDiagnostic(ConfigIssue.ROOT_NOT_CONFIGURED))
        } else {
            addRootIssue(module = null, path = config.translationsRoot, probe = probe)
        }

        modules.forEach { addRootIssue(it.name.ifBlank { it.rootDirectory }, it.rootDirectory, probe) }

        val code = facts.code
        if (facts.readFromCode && code != null) {
            add(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_FROM_CODE, path = config.defaultNamespaces().joinToString(", "), origin = code.origin))
        } else if (config.defaultNs.isBlank()) {
            add(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_EMPTY))
        }
        defaultNamespaceWithoutFile(config, facts)?.let(::add)
        // The empty key separator keeps its own issue: it is a choice some flat-key projects make,
        // and its banner says what the plugin does with it rather than refusing it.
        SeparatorRules.check(config.nsSeparator, config.keySeparator, config.pluralSeparator).forEach { problem ->
            if (problem.separator == SeparatorRules.Separator.KEY && problem.fault == SeparatorRules.Fault.EMPTY) {
                add(ConfigDiagnostic(ConfigIssue.KEY_SEPARATOR_EMPTY))
            } else {
                add(ConfigDiagnostic(ConfigIssue.SEPARATOR_INVALID, path = problem.value, separator = problem))
            }
        }
    }

    /**
     * [ConfigIssue.DEFAULT_NS_NO_FILE] when no locale has a file for any default namespace, with the
     * namespace to use instead: the one the i18next initialisation names, else the only one every
     * locale has a file for. Null when the files are unknown, or a locale-named file holds the
     * default namespace whatever its name.
     */
    fun defaultNamespaceWithoutFile(config: Config, facts: NamespaceFacts): ConfigDiagnostic? {
        if (facts.byLocale.isEmpty() || facts.holdsDefaultByName) return null
        val present = facts.byLocale.values.flatten().toSet()
        val defaults = config.defaultNamespaces()
        if (defaults.any { it in present }) return null
        val fromCode = facts.code?.defaultNs.orEmpty().filter { it in present }
        val inEveryLocale = facts.byLocale.values.reduce { all, namespaces -> all intersect namespaces }
        val suggestion = fromCode.joinToString(",").ifEmpty { null } ?: inEveryLocale.singleOrNull()
        return ConfigDiagnostic(
            ConfigIssue.DEFAULT_NS_NO_FILE,
            path = defaults.joinToString(", "),
            suggestion = suggestion,
            origin = facts.code?.origin?.takeIf { fromCode.isNotEmpty() }
        )
    }

    private fun MutableList<ConfigDiagnostic>.addRootIssue(module: String?, path: String, probe: DirectoryProbe) {
        // The banner shows the root as typed; the probe gets it as the plugin reads it.
        when (probe.inspect(Config.normalizeRoot(path))) {
            DirectoryState.MISSING -> add(ConfigDiagnostic(ConfigIssue.ROOT_MISSING, module, path))
            DirectoryState.WITHOUT_TRANSLATIONS -> add(ConfigDiagnostic(ConfigIssue.ROOT_WITHOUT_TRANSLATIONS, module, path))
            DirectoryState.OK -> Unit
        }
    }
}

/** The localized sentence shown for this diagnostic. */
internal fun ConfigDiagnostic.text(): String = when (issue) {
    ConfigIssue.ROOT_NOT_CONFIGURED -> PluginBundle.message("settings.diagnostics.root.notConfigured")

    ConfigIssue.ROOT_MISSING ->
        if (module == null) PluginBundle.message("settings.diagnostics.root.missing", path)
        else PluginBundle.message("settings.diagnostics.module.root.missing", module, path)

    ConfigIssue.ROOT_WITHOUT_TRANSLATIONS ->
        if (module == null) PluginBundle.message("settings.diagnostics.root.empty", path)
        else PluginBundle.message("settings.diagnostics.module.root.empty", module, path)

    ConfigIssue.DEFAULT_NS_EMPTY -> PluginBundle.message("settings.diagnostics.defaultNs.empty")
    ConfigIssue.KEY_SEPARATOR_EMPTY -> PluginBundle.message("settings.diagnostics.keySeparator.empty")
    ConfigIssue.SEPARATOR_INVALID -> separator?.text() ?: PluginBundle.message("settings.separator.refused.comment")
    ConfigIssue.DEFAULT_NS_NO_FILE -> PluginBundle.message("settings.diagnostics.defaultNs.noFile", path)
    ConfigIssue.DEFAULT_NS_FROM_CODE -> PluginBundle.message("settings.diagnostics.defaultNs.fromCode", path, origin.orEmpty())
}

/** The setting's label, as the settings page shows it: the subject of every separator message. */
internal fun SeparatorRules.Separator.label(): String = when (this) {
    SeparatorRules.Separator.NAMESPACE -> PluginBundle.message("settings.namespace.separator")
    SeparatorRules.Separator.KEY -> PluginBundle.message("settings.key.separator")
    SeparatorRules.Separator.PLURAL -> PluginBundle.message("settings.plural.separator")
}

/**
 * The localized sentence for this problem, the same under a settings field and in a banner: the
 * user who pasted `::` and the one who inherited it from `i18n-check.json` read the same words.
 */
internal fun SeparatorRules.Problem.text(): String = when (fault) {
    SeparatorRules.Fault.EMPTY -> PluginBundle.message("settings.separator.empty", separator.label())
    SeparatorRules.Fault.SEVERAL_CHARACTERS -> PluginBundle.message("settings.separator.severalCharacters", separator.label(), value)
    SeparatorRules.Fault.REFUSED_CHARACTER -> PluginBundle.message("settings.separator.refusedCharacter", separator.label(), value)
    SeparatorRules.Fault.SAME_AS_ANOTHER ->
        PluginBundle.message("settings.separator.sameAsAnother", separator.label(), (other ?: separator).label(), value)
}

/** The label of the action applying [ConfigDiagnostic.suggestion]: `Use 'common' (from src/i18n.ts)`. */
internal fun ConfigDiagnostic.useSuggestionLabel(): String? = suggestion?.let {
    if (origin == null) PluginBundle.message("settings.diagnostics.action.useNamespace", it)
    else PluginBundle.message("settings.diagnostics.action.useNamespace.from", it, origin)
}

/**
 * What [project]'s translation files and i18next initialisation say of the namespaces, under [config]:
 * empty while the indexes are built, as nothing is known then.
 */
fun namespaceFacts(project: Project, config: Config): NamespaceFacts {
    if (project.isDisposed || DumbService.isDumb(project)) return NamespaceFacts()
    return try {
        ReadAction.compute<NamespaceFacts, RuntimeException> {
            val sources = project.service<LocalizationSourceService>().findAllSources(project)
            val default = config.defaultNamespaces().first()
            val byLocale = sources.filter { it.namespace != null || !it.isLocaleNamedFile() }
                .groupBy({ it.localeLabel() }, { TranslationDataLoader.extractNamespace(it, default) })
                .mapValues { (_, namespaces) -> namespaces.toSet() }
            val code = I18NextTechnology.codeDefaultsOf(project, config)
            val settings = Settings.getInstance(project)
            val applied = settings.codeDefaults()?.takeIf { settings.defaultNs.isBlank() && it.defaultNs.isNotEmpty() }
            NamespaceFacts(
                byLocale = byLocale,
                holdsDefaultByName = sources.any { it.namespace == null && it.isLocaleNamedFile() },
                code = applied ?: code,
                readFromCode = applied != null
            )
        }
    } catch (_: IndexNotReadyException) {
        NamespaceFacts()
    }
}

/**
 * Sets the default namespace to [namespace] — the way out [ConfigIssue.DEFAULT_NS_NO_FILE] offers
 * outside the settings page — and refreshes the open editors, whose keys it changes.
 */
fun useDefaultNamespace(project: Project, namespace: String) {
    val settings = Settings.getInstance(project)
    settings.setConfig(settings.config().copy(defaultNs = namespace))
    EditorRefresh.afterSettingsChange(project)
}

/**
 * The diagnostics shown at the top of the settings panel, one [InlineBanner] per problem.
 *
 * [InlineBanner] is the platform's own warning strip: it follows the IDE colour scheme in both
 * themes and carries link actions, which the hand-painted yellow panel it replaces could not.
 *
 * Every banner offers a way out of the problem it reports. The handlers are injected rather
 * than performed here, because undoing a problem means editing the very fields
 * [SettingsPanel] owns; a banner writing into the settings behind its back would leave the
 * open dialog showing stale values. A handler left unset simply drops its action from the
 * banner, so an un-wired caller shows a message with no dead link rather than a link that
 * does nothing.
 *
 * Call [refresh] whenever the config may have changed (e.g. on [SettingsPanel.reset]).
 */
class ConfigDiagnosticsPanel(
    private val project: Project,
    private val onChooseDirectory: ((ConfigDiagnostic) -> Unit)? = null,
    private val onRemoveModule: ((String) -> Unit)? = null,
    private val onRunWizard: (() -> Unit)? = { SetupWizardDialog(project).show() },
    private val onUseDefaultNamespace: ((String) -> Unit)? = null,
    private val facts: (Config) -> NamespaceFacts = { namespaceFacts(project, it) }
) : JPanel() {

    private val probe = DirectoryProbe { path -> inspect(path) }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }

    /**
     * Runs all diagnostic checks against [config] and rebuilds the banners.
     */
    fun refresh(config: Config) {
        removeAll()

        val diagnostics = ConfigDiagnostics.inspect(config, probe, facts(config))
        diagnostics.forEach { add(bannerFor(it)) }
        isVisible = diagnostics.isNotEmpty()

        revalidate()
        repaint()
    }

    // --- rendering ---

    private fun bannerFor(diagnostic: ConfigDiagnostic): InlineBanner {
        val status = if (diagnostic.issue == ConfigIssue.DEFAULT_NS_FROM_CODE) EditorNotificationPanel.Status.Info
        else EditorNotificationPanel.Status.Warning
        val banner = InlineBanner(diagnostic.text(), status)
        banner.showCloseButton(false)
        banner.alignmentX = Component.LEFT_ALIGNMENT

        when (diagnostic.issue) {
            ConfigIssue.ROOT_NOT_CONFIGURED -> onRunWizard?.let { runWizard ->
                banner.addAction(PluginBundle.message("settings.diagnostics.action.runWizard"), Runnable { runWizard() })
            }

            ConfigIssue.ROOT_MISSING, ConfigIssue.ROOT_WITHOUT_TRANSLATIONS -> {
                onChooseDirectory?.let { chooseDirectory ->
                    banner.addAction(
                        PluginBundle.message("settings.diagnostics.action.chooseDirectory"),
                        Runnable { chooseDirectory(diagnostic) }
                    )
                }
                val module = diagnostic.module
                if (module != null) onRemoveModule?.let { removeModule ->
                    banner.addAction(
                        PluginBundle.message("settings.diagnostics.action.removeModule"),
                        Runnable { removeModule(module) }
                    )
                }
            }

            ConfigIssue.DEFAULT_NS_NO_FILE -> {
                val suggestion = diagnostic.suggestion
                val label = diagnostic.useSuggestionLabel()
                if (suggestion != null && label != null) onUseDefaultNamespace?.let { use ->
                    banner.addAction(label, Runnable { use(suggestion) })
                }
                onRunWizard?.let { runWizard ->
                    banner.addAction(PluginBundle.message("settings.diagnostics.action.runWizard"), Runnable { runWizard() })
                }
            }

            ConfigIssue.DEFAULT_NS_EMPTY, ConfigIssue.KEY_SEPARATOR_EMPTY, ConfigIssue.SEPARATOR_INVALID, ConfigIssue.DEFAULT_NS_FROM_CODE -> Unit
        }
        return banner
    }

    // --- the virtual file system side of the checks ---

    private fun inspect(root: String): DirectoryState {
        val basePath = project.basePath ?: ""
        // Config.resolveRoot, as the scan: `/locales` is the project's `locales`, an absolute path inside the project is kept.
        val dirPath = Config.resolveRoot(root, basePath)
        val dir = LocalFileSystem.getInstance().findFileByPath(dirPath)
            ?: LocalFileSystem.getInstance().findFileByIoFile(File(dirPath))

        if (dir == null || !dir.exists() || !dir.isDirectory) return DirectoryState.MISSING
        return if (dir.holdsTranslations()) DirectoryState.OK else DirectoryState.WITHOUT_TRANSLATIONS
    }

    /** True when the directory holds translation files, either directly or one level down. */
    private fun VirtualFile.holdsTranslations(): Boolean =
        children.any { it.isTranslationFile() } ||
            children.filter { it.isDirectory }.any { locale -> locale.children.any { it.isTranslationFile() } }

    private fun VirtualFile.isTranslationFile(): Boolean {
        val fileExtension = extension ?: return false
        return !isDirectory && fileExtension in TranslationFileScanner.EXTENSIONS
    }
}
