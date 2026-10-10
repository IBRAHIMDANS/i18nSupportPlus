package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * *Settings | Tools | i18n Support Plus | Machine Translation*.
 *
 * Unlike the main page, which writes as it is typed, this one edits a copy: an opt-in that sends
 * text to a third party is only switched on by *Apply*, and *Cancel* leaves it off. API keys go to
 * the password safe on *Apply*, never into `.idea/`.
 */
class MachineTranslationConfigurable(private val project: Project) : SearchableConfigurable {

    private var working: MachineTranslationSettings.Settings? = null
    private var panel: MachineTranslationPanel? = null

    override fun getId(): String = "preference.i18nPlugin.machineTranslation"

    override fun getDisplayName(): String = PluginBundle.message("settings.translate.title")

    override fun createComponent(): JComponent {
        val copy = MachineTranslationSettings.getInstance(project).copy()
        working = copy
        return MachineTranslationPanel(copy, PasswordSafeApiKeys::get, ::test).also { panel = it }
    }

    override fun isModified(): Boolean =
        working != MachineTranslationSettings.getInstance(project).state || panel?.keys?.isNotEmpty() == true

    override fun apply() {
        val copy = working ?: return
        MachineTranslationSettings.getInstance(project).loadState(copy)
        panel?.keys?.forEach { (id, key) -> PasswordSafeApiKeys.set(id, key) }
        panel?.keys?.clear()
        working = MachineTranslationSettings.getInstance(project).copy()
    }

    override fun disposeUIResources() {
        panel = null
        working = null
    }

    /** Translates a sample off the EDT, then reports on it. */
    private fun test(engine: EngineConfig, key: String, report: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val message = testMessage(HttpTranslationProvider(engine, key))
            ApplicationManager.getApplication().invokeLater({ report(message) }, ModalityState.any())
        }
    }

    companion object {
        /** What *Test* shows: the sample's translation, or why there is none. */
        internal fun testMessage(provider: TranslationProvider): String =
            when (val result = provider.translate(TranslationRequest(listOf("Hello {{name}}"), "en", "fr", "a greeting")).single()) {
                is Translation.Done -> PluginBundle.message("settings.translate.test.done", result.text)
                is Translation.Failed -> result.reason
            }
    }
}
