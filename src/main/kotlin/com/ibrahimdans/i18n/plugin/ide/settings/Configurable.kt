package com.ibrahimdans.i18n.plugin.ide.settings

import com.ibrahimdans.i18n.plugin.ide.folding.FoldingBuilderBase
import com.ibrahimdans.i18n.plugin.ide.preview.EditorRefresh
import com.ibrahimdans.i18n.plugin.ide.whatsnew.WhatsNewPreferences
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.options.BaseConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * *Settings | Tools | i18n Support Plus*: the project's settings. The modules and the key assistance
 * rules are on the pages below it, [ModulesConfigurable] and [RulesConfigurable].
 *
 * The page writes [Settings] as it is typed. [snapshot] is what was last validated: closing the
 * dialog without validating, *Cancel*, puts it back.
 */
class Configurable(val project: Project) : BaseConfigurable(), SearchableConfigurable {

    private var gui: JPanel? = null
    private var snapshot: Config? = null
    /** *Announce plugin updates* is an application setting, outside [Config]. */
    private var announceUpdates = true

    override fun createComponent(): JComponent {
        snapshot = Settings.getInstance(project).config()
        announceUpdates = WhatsNewPreferences.announceUpdates
        val page = SettingsPanel(Settings.getInstance(project), project)
        val root = page.getRootPanel()
        gui = root
        LocaleChoice.discover(project, alive = { gui === root }) { page.offerLocales(it) }
        return root
    }

    @Nls
    override fun getDisplayName(): String = PluginBundle.getMessage("app.name")

    /** No help page exists: a topic named here gave the dialog's *?* button nowhere to go. */
    override fun getHelpTopic(): String? = null

    override fun getId(): String = "preference.i18nPlugin"

    override fun isModified(): Boolean =
        Settings.getInstance(project).config() != snapshot || WhatsNewPreferences.announceUpdates != announceUpdates

    /**
     * Validating is when a change of *Enable folding* reaches the open editors, once, as *Show
     * Translations Inline* does it.
     */
    override fun apply() {
        val before = snapshot
        val after = Settings.getInstance(project).config()
        snapshot = after
        announceUpdates = WhatsNewPreferences.announceUpdates
        if (before != null && before.foldingEnabled != after.foldingEnabled) {
            EditorRefresh.afterSettingsChange(project) { FoldingBuilderBase.refreshOpenEditors(project, fold = after.foldingEnabled) }
        }
    }

    /**
     * *OK* has applied by now; anything still apart from [snapshot] was left by *Cancel*. Only this
     * page's settings go back: the modules and the rules belong to their own pages, which put back
     * theirs.
     */
    override fun disposeUIResources() {
        snapshot?.let { kept ->
            val settings = Settings.getInstance(project)
            val current = settings.config()
            val restored = kept.copy(modules = current.modules, rules = current.rules)
            if (restored != current) {
                settings.setConfig(restored)
                EditorRefresh.afterSettingsChange(project)
            }
            WhatsNewPreferences.announceUpdates = announceUpdates
        }
        gui = null
        snapshot = null
    }
}