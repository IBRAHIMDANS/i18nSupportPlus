package com.ibrahimdans.i18n.extensions.technology.angulari18n

import com.ibrahimdans.i18n.extensions.lang.js.MarkupText
import com.ibrahimdans.i18n.extensions.technology.transloco.TranslocoScopes
import com.ibrahimdans.i18n.plugin.ide.settings.FrameworkDetector
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType

/**
 * Extraction into Angular's own i18n: the text stays in the template, marked with the id of its
 * message, and the message goes into the XLIFF files as a new unit:
 *  - `<p>Save</p>` becomes `<p i18n="@@save">Save</p>`;
 *  - `placeholder="Name"` becomes `placeholder="Name" i18n-placeholder="@@name"`;
 *  - a text outside any tag, in a `@if` block, is wrapped: `<ng-container i18n="@@id">Inside</ng-container>`.
 *
 * What is replaced never overlaps the tag's attributes: a tag's content and one of its attributes are
 * extracted together by *Internationalize…*.
 */
internal object AngularI18nMarkers {

    /** Where a [MarkupText] is marked, and the text written there for an id. */
    class Marking(val range: TextRange, private val written: (id: String, escape: (String) -> String) -> String) {
        /** The text replacing [range] for [id], the marker passed through [escape] — what an inline template's quote needs. */
        fun write(id: String, escape: (String) -> String): String = written(id, escape)
    }

    /**
     * Whether a text extracted at [element] is marked for Angular's own i18n rather than written as a
     * library's pipe: the module's [preset] names it, or, without one, the project's `angular.json` lists
     * translations and the project does not use Transloco.
     */
    fun applies(element: PsiElement, preset: String?): Boolean = when (preset) {
        FrameworkDetector.ANGULAR_I18N -> true
        null -> listsTranslations(element) && !TranslocoScopes.isTransloco(element.project)
        else -> false
    }

    private fun listsTranslations(element: PsiElement): Boolean {
        val project = element.project
        if (DumbService.isDumb(project)) return false
        return runReadAction { AngularI18nTechnology.workspaces(project).any { (_, projects) -> projects.any { it.translations.isNotEmpty() } } }
    }

    /** The id the extraction dialog's key [argument] names: `'home.title'` → `home.title`. */
    fun idOf(argument: String): String = argument.trim().removeSurrounding("'")

    /** Where [text] is marked: see the class. */
    fun of(text: MarkupText): Marking {
        val fileText = text.element.containingFile.text
        return when (text) {
            is MarkupText.Attribute -> Marking(text.range) { id, escape ->
                text.attribute.text + escape(" i18n-${text.attribute.name}=\"@@$id\"")
            }
            is MarkupText.Content -> {
                val tagEnd = (text.element.parent as? XmlTag)?.node?.findChildByType(XmlTokenType.XML_TAG_END)?.startOffset
                if (tagEnd != null && tagEnd <= text.range.startOffset) {
                    // From the `>` of the start tag: the attributes before it are left alone.
                    val range = TextRange(tagEnd, text.range.endOffset)
                    Marking(range) { id, escape -> escape(" i18n=\"@@$id\"") + range.substring(fileText) }
                } else Marking(text.range) { id, escape ->
                    escape("<ng-container i18n=\"@@$id\">") + text.range.substring(fileText) + escape("</ng-container>")
                }
            }
        }
    }
}
