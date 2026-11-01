package com.ibrahimdans.i18n.plugin.translate

import com.ibrahimdans.i18n.plugin.ide.IdeKeyUsageIndex
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

/**
 * What the markup using a key makes of its text, worked out from the code without sending it
 * (#702): `caption of <SettingsOption>`, `placeholder of <input>`, `text of <h2>`. The few words
 * that tell an engine a switch caption from a verb, where a code snippet would send the file.
 *
 * Read from JSX/TSX and from Vue templates through the platform's XML PSI alone: a Vue template
 * is read when the Vue plugin parses it, with no dependency on that plugin. A call outside any
 * markup (`const label = t('k')`), or in an event handler (`onClick={() => toast(t('k'))}`), has
 * no role: its text is not shown where it is written.
 */
object KeyUiRoles {

    /** A longer attribute or tag name is not one a person wrote; it would only cost the budget. */
    private const val MAX_NAME = 40

    /** Vue's ways of binding an attribute to an expression: `:placeholder`, `v-bind:placeholder`. */
    private val VUE_BINDING = Regex("^(v-bind:|:)")

    /** Vue directives writing the tag's content: `<p v-text="$t('k')">`. */
    private val VUE_CONTENT = setOf("v-text", "v-html")

    /** A JSX handler (`onClick`) or a Vue one (`@click`, `v-on:click`): code, not displayed text. */
    private val HANDLER = Regex("^(on[A-Z].*|@.*|v-on:.*)$")

    /**
     * The distinct roles of [key] (`ns:path`, spelled as the table spells it) in [module]'s code,
     * the whole project's without one. Searches the code: off the EDT, in a read action.
     */
    fun of(project: Project, key: String, module: ModuleConfig? = null): List<String> =
        IdeKeyUsageIndex(project, module).readUsages(key, ::roleOf).distinct().take(TranslationContext.MAX_ROLES)

    /**
     * The role of the call holding [element]: the attribute it is the value of, with its tag, or
     * the tag it is the content of; null outside markup. An injected fragment (a Vue attribute
     * value, a `{{ }}` interpolation) is followed out to its host in the template.
     */
    fun roleOf(element: PsiElement): String? {
        val injections = InjectedLanguageManager.getInstance(element.project)
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is XmlAttribute -> return attributeRole(current)
                is XmlTag -> return nameOf(current)?.let { "text of <$it>" }
                is JSFunction -> return null
                is PsiFile -> current = injections.getInjectionHost(current)
                else -> current = current.parent
            }
        }
        return null
    }

    private fun attributeRole(attribute: XmlAttribute): String? {
        val raw = attribute.name
        if (HANDLER.matches(raw)) return null
        val tag = attribute.parent?.let(::nameOf) ?: return null
        if (raw in VUE_CONTENT) return "text of <$tag>"
        val name = raw.replace(VUE_BINDING, "")
        // Another directive (`v-if`, `v-for`) or a slot (`#title`) says nothing of what is shown.
        if (name.isEmpty() || name.startsWith("v-") || name.startsWith("#") || name.length > MAX_NAME) return null
        return "$name of <$tag>"
    }

    /** [tag]'s name as written, or null for a fragment (`<>`) or a name too long to be one. */
    private fun nameOf(tag: XmlTag): String? =
        tag.name.takeIf { it.isNotBlank() && it.length <= MAX_NAME }
}
