package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference

/**
 * Navigates from a msgid to the code that calls it.
 *
 * PO/POT files cannot go through `psi.referenceContributor` the way JSON and YAML do: the platform
 * only injects contributed references into a `ContributedReferenceHost`, and no element of the
 * org.jetbrains.plugins.localization tree is one — the references are computed and then dropped.
 * Ctrl+click reaches every element, so the same [PlainObjectReferenceAssistant] is read from here.
 */
class PlainObjectGotoDeclarationHandler : GotoDeclarationHandler {

    private val assistant = PlainObjectReferenceAssistant()

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = sourceElement?.parent?.takeIf { assistant.pattern().accepts(it) } ?: return null
        return assistant.references(element)
            .filterIsInstance<PsiPolyVariantReference>()
            .flatMap { reference -> reference.multiResolve(true).mapNotNull { it.element } }
            .distinct()
            .toTypedArray()
            .takeIf { it.isNotEmpty() }
    }
}
