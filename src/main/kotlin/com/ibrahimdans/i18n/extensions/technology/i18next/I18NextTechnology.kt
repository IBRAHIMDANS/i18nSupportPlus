package com.ibrahimdans.i18n.extensions.technology.i18next

import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.Technology
import com.ibrahimdans.i18n.Workspace
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.utils.project

/**
 * i18next and react-i18next: the call names, and what the project's JavaScript says of the
 * namespaces and sources, read by the JavaScript plugin's [I18nextCodeReader] when it is there.
 * Without the JavaScript plugin the technology keeps its names and reads no code: the translation
 * files are found by the settings alone.
 *
 * Qualified names are listed in full: a call qualified by anything else than `this` is only
 * accepted when its whole text is a published name, which is what keeps `toast.t('…')` out.
 * `props.t` / `this.props.t` are what the `withTranslation` HOC injects, and `i18next.t` is the
 * instance API used outside React.
 */
class I18NextTechnology : Technology {

    override fun frameworkId(): String = "i18next"

    override fun translationFunctionNames(): List<String> = FrameworkPresets.I18NEXT.callNames

    /** The JavaScript plugin's reader, null without the plugin. Looked up at each call: the extension point is dynamic. */
    private val reader: I18nextCodeReader?
        get() = Extensions.CODE_READER.extensionList.filterIsInstance<I18nextCodeReader>().firstOrNull()

    override fun findSourcesByConfiguration(workspace: Workspace): List<LocalizationSource> =
        reader?.initSources(workspace.project) ?: emptyList()

    override fun initialize(workspace: Workspace) {
        reader?.initialize(workspace.project)
    }

    override fun cfgNamespaces(): List<String> = reader?.cfgNamespaces() ?: emptyList()

    override fun fallbackNamespaces(workspace: Workspace): List<String> = reader?.fallbackNamespaces(workspace.project) ?: emptyList()
}
