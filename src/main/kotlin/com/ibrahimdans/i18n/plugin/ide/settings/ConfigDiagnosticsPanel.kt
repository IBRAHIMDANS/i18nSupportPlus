package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
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
    KEY_SEPARATOR_EMPTY
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
    val path: String = ""
)

/**
 * The configuration checks, with no user interface attached.
 *
 * Kept apart from [ConfigDiagnosticsPanel] so the rules can be exercised headlessly against a
 * stub [DirectoryProbe]; the panel only turns the result into banners.
 */
object ConfigDiagnostics {

    /** Every problem [config] currently has, in the order they should be shown. */
    fun inspect(config: Config, probe: DirectoryProbe): List<ConfigDiagnostic> = buildList {
        val modules = config.modules.filter { it.rootDirectory.isNotBlank() }

        if (config.translationsRoot.isBlank()) {
            // Modules carrying their own roots make the project-wide one redundant, so its
            // absence is only worth reporting when nothing else points at the translations.
            if (modules.isEmpty()) add(ConfigDiagnostic(ConfigIssue.ROOT_NOT_CONFIGURED))
        } else {
            addRootIssue(module = null, path = config.translationsRoot, probe = probe)
        }

        modules.forEach { addRootIssue(it.name.ifBlank { it.rootDirectory }, it.rootDirectory, probe) }

        if (config.defaultNs.isBlank()) add(ConfigDiagnostic(ConfigIssue.DEFAULT_NS_EMPTY))
        if (config.keySeparator.isBlank()) add(ConfigDiagnostic(ConfigIssue.KEY_SEPARATOR_EMPTY))
    }

    private fun MutableList<ConfigDiagnostic>.addRootIssue(module: String?, path: String, probe: DirectoryProbe) {
        when (probe.inspect(path)) {
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
    private val onRunWizard: (() -> Unit)? = { SetupWizardDialog(project).show() }
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

        val diagnostics = ConfigDiagnostics.inspect(config, probe)
        diagnostics.forEach { add(bannerFor(it)) }
        isVisible = diagnostics.isNotEmpty()

        revalidate()
        repaint()
    }

    // --- rendering ---

    private fun bannerFor(diagnostic: ConfigDiagnostic): InlineBanner {
        val banner = InlineBanner(diagnostic.text(), EditorNotificationPanel.Status.Warning)
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

            ConfigIssue.DEFAULT_NS_EMPTY, ConfigIssue.KEY_SEPARATOR_EMPTY -> Unit
        }
        return banner
    }

    // --- the virtual file system side of the checks ---

    private fun inspect(root: String): DirectoryState {
        val basePath = project.basePath ?: ""
        val dirPath = if (root.startsWith("/")) root else "$basePath/$root"
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
