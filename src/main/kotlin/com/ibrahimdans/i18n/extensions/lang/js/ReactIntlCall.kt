package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.factory.CallTemplate
import com.ibrahimdans.i18n.plugin.factory.MessageVariable
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.utils.ModulePresets
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.hostFile
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.ibrahimdans.i18n.plugin.utils.of
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.psi.PsiElement

/**
 * What an extraction writes in a react-intl project: `<FormattedMessage id="key" />` for a JSX text,
 * `intl.formatMessage({ id: 'key' })` for a string — react-intl has no `t`, and the `i18n.t('key')`
 * written before named nothing the component imports.
 *
 * react-intl applies where the module's preset says so, or — in a file no module holds — once the
 * file imports `react-intl`. A module's *call template* wins: the project calls its own wrapper.
 */
internal object ReactIntlCall {

    const val PRESET = "react-intl"
    private val IMPORT = Regex("""from\s*['"]react-intl['"]""")

    /** Whether an extraction at [element] writes react-intl's own call. */
    fun applies(element: PsiElement): Boolean {
        if (CallTemplate.of(element) != null) return false
        val preset = ModulePresets.presetOf(element)
        if (preset != null) return preset == PRESET
        return module(element) == null && element.hostFile()?.text?.let(IMPORT::containsMatchIn) == true
    }

    /**
     * `<FormattedMessage id="key" defaultMessage="Hello {name}" values={{ name }} />` for the key
     * [argument], quoted as the extraction quotes it.
     */
    fun component(argument: String, variables: List<MessageVariable>, defaultMessage: String?): String {
        val attributes = buildList {
            add("id=${attribute(argument.unQuote())}")
            defaultMessage?.let { add("defaultMessage=${attribute(it)}") }
            if (variables.isNotEmpty()) add("values={${MessageVariables.inputs(variables)}}")
        }
        return "<FormattedMessage ${attributes.joinToString(" ")} />"
    }

    /** `intl.formatMessage({ id: 'key', defaultMessage: 'Hello {name}' }, { name })`, [function] the callee in scope. */
    fun call(function: String, argument: String, variables: List<MessageVariable>, defaultMessage: String?): String {
        val descriptor = "id: $argument" + defaultMessage?.let { ", defaultMessage: ${CallTemplate.quote(it)}" }.orEmpty()
        return "$function({ $descriptor }${MessageVariables.options(variables)})"
    }

    /**
     * The `defaultMessage` for the variables as the extraction dialog names them — [text] with the
     * placeholders of [original] renamed — or null when the module holding [element]'s file does not
     * ask for the text in the call.
     */
    fun defaultMessage(element: PsiElement, text: String, original: List<MessageVariable>): (List<MessageVariable>) -> String? {
        if (module(element)?.writeDefaultValue != true) return { null }
        return { variables -> original.zip(variables).fold(text) { acc, (was, now) -> acc.replace(was.placeholder, now.placeholder) } }
    }

    /** Why a string at [element] cannot become a `formatMessage` call: no `useIntl()` in scope. */
    fun refusal(element: PsiElement): String? =
        if (formatMessageInScope(element) == null) PluginBundle.message("action.intention.extract.key.no.intl") else null

    /** The callee written when none is in scope, by a bulk extraction that asks no one. */
    const val DEFAULT_FORMAT_MESSAGE = "intl.formatMessage"

    /** A JSX attribute value: a plain string, or an expression when [value] holds a double quote. */
    private fun attribute(value: String): String =
        if ('"' in value) "{${CallTemplate.quote(value)}}" else "\"$value\""

    private fun module(element: PsiElement) =
        element.hostVirtualFile()?.let { file ->
            val project = element.project
            if (Settings.getInstance(project).modules.isEmpty()) return@let null
            ModuleSources.owner(Settings.getInstance(project).config().modules, ModuleSources.FilePath.of(file, project.basePath ?: ""))
        }
}
