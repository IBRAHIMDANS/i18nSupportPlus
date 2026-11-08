package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.settings.rules.EditorRuleState
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginCoroutineScope
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Transient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly

/**
 * Plugin settings, persisted in `.idea/i18nSettings.xml`.
 *
 * When the project has an `i18n-check.json` at its root ([ProjectConfigFile]), the settings it names
 * come from it: [config] lays them over the ones stored here, and [setConfig] never writes them back,
 * so that the file stays the single place a team changes them. Read once, then again whenever the file
 * changes ([configFileChanged], called by [ProjectConfigFileListener]).
 *
 * The file's open, unsaved document counts too, so that an undo shows at once — but not while it is
 * typed in. Every keystroke that changes a setting makes each consumer start over (the usage index
 * reads the whole project again), so the keystrokes wait for a pause in the typing
 * ([TYPING_PAUSE_MS]), after which the editors are refreshed. Saving and undoing apply at once.
 */
@State(name = "i18nSettings", storages = [Storage("i18nSettings.xml")])
class Settings(@field:Transient private val project: Project?) : PersistentStateComponent<Settings>, ConfigStore, Disposable {

    /** The state the platform deserializes, and the tests that build settings with no project. */
    constructor() : this(null)

    init {
        if (project != null) {
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = configDocumentChanged(event.document)
            }, this)
        }
    }

    private val default: Config = Config()

    internal var searchInProjectOnly = default.searchInProjectOnly
    internal var nsSeparator = default.nsSeparator
    internal var keySeparator = default.keySeparator
    internal var pluralSeparator = default.pluralSeparator
    internal var defaultNs = default.defaultNs
    internal var fallbackNs = default.fallbackNs
    internal var firstComponentNs = default.firstComponentNs
    internal var jsConfiguration = default.jsConfiguration
    internal var foldingEnabled = default.foldingEnabled
    internal var foldingPreferredLanguage = default.foldingPreferredLanguage
    internal var foldingMaxLength = default.foldingMaxLength
    internal var extractSorted = default.extractSorted
    internal var gettext = default.gettext
    internal var gettextAliases = default.gettextAliases
    internal var flatKeys = default.flatKeys
    internal var partialTranslationInspectionEnabled = default.partialTranslationInspectionEnabled
    internal var preferredLocalization = default.preferredLocalization
    internal var localizationConfig = default.localizationConfig
    internal var sortKeysAlphabetically = default.sortKeysAlphabetically
    internal var keyOrder = default.keyOrder
    internal var previewLocale = default.previewLocale
    internal var translationsRoot = default.translationsRoot
    internal var excludedDirectories = default.excludedDirectories
    internal var excludedFileExtensions = default.excludedFileExtensions
    internal var keptKeys = default.keptKeys
    internal var ignoredLocales = default.ignoredLocales
    internal var regionalFallback = default.regionalFallback
    internal var localeAliases = default.localeAliases
    internal var gutterIconsEnabled = default.gutterIconsEnabled
    internal var setupWizardEnabled = default.setupWizardEnabled

    /**
     * Removed flag, kept only so [loadState] can migrate the states that already carry it.
     * Nothing else may read or write it: "Don't show again" now unticks [setupWizardEnabled].
     */
    internal var wizardDismissed = false

    /**
     * Set once [migratePreviewReference] has run, and on a project with no state yet: past that
     * point a preview locale apart from the folding language is the user's choice, never one to
     * migrate again. False by default so that a state persisted before the migration runs it.
     */
    internal var previewReferenceMigrated = false

    /**
     * The version of the persisted state: each migration of [loadState] is a step, run on a state of a
     * lower version. 0, the default, is a state written before the field existed — it runs every step,
     * each of which still checks the flag it used to rely on, so that nothing migrates twice.
     */
    internal var version = 0
    internal var modules: MutableList<ModuleConfig> = mutableListOf()
    internal var rules: MutableList<EditorRuleState> = mutableListOf()

    /** `i18n-check.json` as last read, null until it is read: see [configFile]. */
    @field:Transient
    @Volatile
    private var configFileState: ConfigFileState? = null

    /** The stamp of the `i18n-check.json` last reported as not reading, so that it is reported once. */
    @field:Transient
    @Volatile
    private var notifiedStamp: Long = -1

    /** When the typing in the open `i18n-check.json` counts as paused: until then, its unsaved text waits. */
    @field:Transient
    @Volatile
    private var typingUntil = 0L

    /** Reads the document and refreshes the editors once the typing has paused. */
    @field:Transient
    private var pauseJob: Job? = null

    /**
     * Returns plugin configuration: the stored settings, with those `i18n-check.json` sets laid over
     * them, and those still blank filled from the project's i18next initialisation ([codeDefaults]).
     */
    override fun config(): Config {
        val config = fileConfig()
        return config.withCodeDefaults(codeDefaults(config))
    }

    /**
     * Whether [config] holds a module: the ones `i18n-check.json` lists when it lists any, the stored
     * ones otherwise — the same choice [config] makes, without building a [Config]. Asked of every PSI
     * element by the presets and the call templates, in projects that mostly have none: the file is
     * read once per change ([configFile]), and a project without it costs a list check.
     *
     * The stored list alone used to answer: a project whose modules the file declares had no preset,
     * no call template and no react-intl handling.
     */
    fun hasModules(): Boolean = (configFile().content?.modules ?: modules).isNotEmpty()

    /** [namespaceFacts] of this project under [config]; none for settings built with no project. */
    fun namespaceFacts(config: Config): NamespaceFacts =
        project?.let { com.ibrahimdans.i18n.plugin.ide.settings.namespaceFacts(it, config) } ?: NamespaceFacts()

    /** The stored settings with `i18n-check.json` laid over them. */
    private fun fileConfig(): Config {
        val stored = storedConfig()
        val content = configFile().content ?: return stored
        return content.applyTo(stored, ignored = IGNORED_FROM_FILE)
    }

    /**
     * What the project's i18next initialisation (`src/i18n.ts`…) says of the settings [config] leaves
     * blank — the default namespace, the reference locale — and where it says it. Null when nothing
     * is blank, which spares reading the code at all.
     */
    fun codeDefaults(config: Config = fileConfig()): CodeDefaults? {
        if (!config.leavesBlankForCode()) return null
        val project = project?.takeUnless { it.isDisposed } ?: return null
        return CodeSettingsReader.codeDefaults(project, config)
    }

    /** The settings `i18n-check.json` sets, as it reads them — empty without the file. */
    fun configFileSettings(): Map<String, String> {
        val content = configFile().content ?: return emptyMap()
        val config = config()
        return (content.names - IGNORED_FROM_FILE).associateWith { name ->
            ProjectConfigFile.valueOf(config, name) ?: ""
        }
    }

    /** `i18n-check.json` at the project's root, when it has one. */
    fun configFileOrNull(): VirtualFile? = configFile().file

    /**
     * Forgets the `i18n-check.json` last read, so the next [config] reads it again, and reads it at
     * once: a file that does not read is reported now, not at the next keystroke.
     *
     * @return true when the file exists and reads
     */
    fun configFileChanged(): Boolean {
        configFileState = null
        return configFile().content != null
    }

    private fun configFile(): ConfigFileState {
        val typing = typing()
        configFileState?.takeIf { it.isCurrent(typing) }?.let { return it }
        return readConfigFile(typing).also { configFileState = it }
    }

    private fun readConfigFile(typing: Boolean): ConfigFileState {
        val project = project?.takeUnless { it.isDisposed } ?: return ConfigFileState.NONE
        val file = runReadAction { project.guessProjectDir()?.findChild(ProjectConfigFile.PATH) }
            ?.takeIf { it.isValid && !it.isDirectory } ?: return ConfigFileState.NONE
        val stamp = file.modificationStamp
        // Typed in: the file is read, and the document once the typing has paused.
        val documentStamp = if (typing) ConfigFileState.TYPED_IN else ConfigFileState.documentStampOf(file)
        if (!typing) unsavedContent(file)?.let { return ConfigFileState(file, stamp, it, documentStamp) }
        return try {
            ConfigFileState(file, stamp, ProjectConfigFile.parse(VfsUtilCore.loadText(file)), documentStamp)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.info("${ProjectConfigFile.PATH} is not read: ${e.message}")
            // Read again at each keystroke of an unsaved document: the file itself is reported once.
            if (notifiedStamp == stamp) return ConfigFileState(file, stamp, null, documentStamp)
            notifiedStamp = stamp
            PluginNotifications.notify(
                project,
                PluginBundle.message("settings.config.file.error", ProjectConfigFile.PATH, e.message.orEmpty()),
                NotificationType.WARNING,
                "",
                NotificationAction.createSimpleExpiring(PluginBundle.message("settings.config.file.open")) {
                    if (file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
                }
            )
            ConfigFileState(file, stamp, null, documentStamp)
        }
    }

    /**
     * What the open, unsaved document of [file] sets — after an undo, a refactoring that has not been
     * saved yet — so that the settings follow the editor rather than the disk. Null without such a
     * document, or while it does not read (a keystroke away from valid JSON): the file on disk then.
     */
    private fun unsavedContent(file: VirtualFile): ProjectConfigFile.Content? {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(file)?.takeIf { documents.isDocumentUnsaved(it) } ?: return null
        return try {
            ProjectConfigFile.parse(runReadAction { document.text })
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Whether the open `i18n-check.json` is being typed in: its unsaved text then waits for the pause. */
    private fun typing(): Boolean = System.currentTimeMillis() < typingUntil

    /**
     * A change of a document named `i18n-check.json`: typed in, the one at this project's root starts
     * the pause; undone or redone, it is read at once. Every other change of the file is a save.
     */
    private fun configDocumentChanged(document: Document) {
        val project = project?.takeUnless { it.isDisposed } ?: return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.name != ProjectConfigFile.PATH || file.parent != project.guessProjectDir()) return
        if (UndoManager.getInstance(project).isUndoOrRedoInProgress) {
            readDocumentNow()
            return
        }
        typingUntil = System.currentTimeMillis() + TYPING_PAUSE_MS
        pauseJob?.cancel()
        pauseJob = PluginCoroutineScope.of(project).launch {
            delay(TYPING_PAUSE_MS)
            afterTypingPause(project)
        }
    }

    /** Reads what the typing left, and shows it in the editors when it changed the settings. */
    private fun afterTypingPause(project: Project) {
        typingUntil = 0L
        val before = configFileState?.content
        if (configFile().content != before) EditorRefresh.afterSettingsChange(project)
    }

    /** Ends the pause: the next [config] reads the unsaved document. */
    private fun readDocumentNow() {
        pauseJob?.cancel()
        typingUntil = 0L
    }

    /** Ends the typing pause, as the time would: the next [config] reads the unsaved document. */
    @TestOnly
    fun endTypingPause() = readDocumentNow()

    override fun dispose() {
        pauseJob?.cancel()
    }

    /**
     * The [file] read, with its [stamp] then and what it set — null when it did not read. A file
     * deleted or saved since is read again; one created since is announced by [configFileChanged].
     */
    private class ConfigFileState(
        val file: VirtualFile?,
        val stamp: Long,
        val content: ProjectConfigFile.Content?,
        val documentStamp: Long = NO_DOCUMENT,
    ) {
        /** Whether what was read still holds. While the document is [typing] in, it does: the keystrokes wait. */
        fun isCurrent(typing: Boolean): Boolean {
            if (file == null) return true
            if (!file.isValid || file.modificationStamp != stamp) return false
            return typing || documentStampOf(file) == documentStamp
        }

        companion object {
            private const val NO_DOCUMENT = -1L

            /** Read from the file while its document was typed in: read again once the typing has paused. */
            const val TYPED_IN = -2L

            val NONE = ConfigFileState(null, 0, null)

            /** The stamp of [file]'s loaded document, which an undo changes without touching the file. */
            fun documentStampOf(file: VirtualFile): Long =
                FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: NO_DOCUMENT
        }
    }

    /** The settings as stored here, before `i18n-check.json` is laid over them. */
    private fun storedConfig(): Config {
        if (preferredLocalization.isEmpty()) {
            preferredLocalization = Extensions.LOCALIZATION.extensionList.firstOrNull()?.config()?.id() ?: ""
        }
        return Config(
            searchInProjectOnly = searchInProjectOnly,
            nsSeparator = nsSeparator,
            keySeparator = keySeparator,
            pluralSeparator = pluralSeparator,
            defaultNs = defaultNs,
            fallbackNs = fallbackNs,
            firstComponentNs = firstComponentNs,
            jsConfiguration = jsConfiguration,
            foldingEnabled = foldingEnabled,
            foldingPreferredLanguage = foldingPreferredLanguage,
            foldingMaxLength = foldingMaxLength,
            extractSorted = extractSorted,
            gettext = gettext,
            gettextAliases = gettextAliases,
            flatKeys = flatKeys,
            partialTranslationInspectionEnabled = partialTranslationInspectionEnabled,
            preferredLocalization = preferredLocalization,
            localizationConfig = localizationConfig,
            sortKeysAlphabetically = sortKeysAlphabetically,
            keyOrder = keyOrder,
            previewLocale = previewLocale,
            translationsRoot = translationsRoot,
            excludedDirectories = excludedDirectories,
            excludedFileExtensions = excludedFileExtensions,
            keptKeys = keptKeys,
            ignoredLocales = ignoredLocales,
            regionalFallback = regionalFallback,
            localeAliases = localeAliases,
            gutterIconsEnabled = gutterIconsEnabled,
            setupWizardEnabled = setupWizardEnabled,
            modules = modules.toList(),
            rules = rules.toList()
        )
    }

    /**
     * Stores [edited], except the settings `i18n-check.json` sets: those keep their stored value. The
     * modules are stored as they come, so that each one's *Active* box is remembered on this machine.
     */
    override fun setConfig(edited: Config) {
        val held = configFile().content?.names.orEmpty() - IGNORED_FROM_FILE
        val stored = storedConfig()
        val config = (if (held.isEmpty()) edited else ProjectConfigFile.restore(edited, stored, held))
            .keepingCodeDefaultsOut(stored, codeDefaults())
        searchInProjectOnly = config.searchInProjectOnly
        nsSeparator = config.nsSeparator
        keySeparator = config.keySeparator
        pluralSeparator = config.pluralSeparator
        defaultNs = config.defaultNs
        fallbackNs = config.fallbackNs
        firstComponentNs = config.firstComponentNs
        jsConfiguration = config.jsConfiguration
        foldingEnabled = config.foldingEnabled
        foldingPreferredLanguage = config.foldingPreferredLanguage
        foldingMaxLength = config.foldingMaxLength
        extractSorted = config.extractSorted
        gettext = config.gettext
        gettextAliases = config.gettextAliases
        flatKeys = config.flatKeys
        partialTranslationInspectionEnabled = config.partialTranslationInspectionEnabled
        preferredLocalization = config.preferredLocalization
        localizationConfig = config.localizationConfig
        sortKeysAlphabetically = config.sortKeysAlphabetically
        keyOrder = config.keyOrder
        previewLocale = config.previewLocale
        translationsRoot = config.translationsRoot
        excludedDirectories = config.excludedDirectories
        excludedFileExtensions = config.excludedFileExtensions
        keptKeys = config.keptKeys
        ignoredLocales = config.ignoredLocales
        regionalFallback = config.regionalFallback
        localeAliases = config.localeAliases
        gutterIconsEnabled = config.gutterIconsEnabled
        setupWizardEnabled = config.setupWizardEnabled
        modules.clear()
        modules.addAll(config.modules.map { it.copy() })
        rules.clear()
        rules.addAll(config.rules.map { it.copy() })
    }

    override fun loadState(state: Settings) {
        XmlSerializerUtil.copyBean(state, this)
        if (version < 1) migrateWizardDismissed()
        if (version < 2) migratePreviewReference()
        version = CURRENT_VERSION
    }

    override fun noStateLoaded() {
        previewReferenceMigrated = true
        version = CURRENT_VERSION
    }

    /**
     * Folds the old `wizardDismissed` flag into [setupWizardEnabled].
     *
     * "Don't show again" used to write its own flag, which the settings checkbox never read —
     * so the two switches disagreed. They are one now, and a state persisted before that has
     * to land on the switch, otherwise everyone who dismissed the wizard silently gets it back.
     * Cleared once applied, so a later save no longer carries it.
     */
    private fun migrateWizardDismissed() {
        if (!wizardDismissed) return
        setupWizardEnabled = false
        wizardDismissed = false
    }

    /**
     * Keeps the reference locale of a project where the preview locale used to decide it.
     *
     * A module without a reference locale of its own was compared against the preview locale,
     * and only then against the folding language; the preview no longer counts. Where the two
     * differed and some module relied on that fallback, the preview locale becomes the folding
     * language — the field the reference now falls back to — so no inspection, *Fill Missing*
     * or statistic changes its reference on update. Runs once: [previewReferenceMigrated].
     */
    private fun migratePreviewReference() {
        if (previewReferenceMigrated) return
        previewReferenceMigrated = true
        if (previewLocale.isBlank() || previewLocale == foldingPreferredLanguage) return
        if (modules.isNotEmpty() && modules.all { it.referenceLocale.isNotBlank() }) return
        foldingPreferredLanguage = previewLocale
    }

    override fun getState(): Settings = this

    /**
     * Service class for persisting settings
     */
    companion object Persistence {
        /** The version [loadState] brings a state to: the number of its migration steps. */
        internal const val CURRENT_VERSION = 2

        /** Read from `i18n-check.json` but not applied: the preview locale is one developer's. */
        private val IGNORED_FROM_FILE = setOf(ProjectConfigFile.PREVIEW_LOCALE)

        /**
         * How long the typing in the open `i18n-check.json` must pause before its text is read: long
         * enough to span the keystrokes of a word, short enough for the editors to follow a finished one.
         */
        const val TYPING_PAUSE_MS = 750L

        private val LOG = logger<Settings>()

        /**
         * Loads project's Settings instance
         */
        fun getInstance(project: Project): Settings = project.getService(Settings::class.java)
    }
}
