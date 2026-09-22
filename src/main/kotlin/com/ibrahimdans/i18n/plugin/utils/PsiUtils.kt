package com.ibrahimdans.i18n.plugin.utils

import com.intellij.json.psi.JsonProperty
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Gets element's type string
 */
fun PsiElement.type(): String = this.node?.elementType.toString()

/**
 * The file the element is *written in*: the host component for an element living in an injected
 * fragment, [containingFile] otherwise.
 *
 * A `{{ }}` interpolation in a Vue or Svelte component is an injected JS file, and that file
 * belongs to no directory on disk and imports nothing. Every caller asking which module owns a key,
 * or what the file around it imports, has to cross back to the host first; the ones that read
 * [containingFile] directly saw the fragment and fell back to project-wide behaviour without
 * saying so.
 */
fun PsiElement.hostFile(): PsiFile? =
    InjectedLanguageManager.getInstance(project).getTopLevelFile(this) ?: containingFile

/**
 * [hostFile] on disk — through `originalFile`, so an element taken from the non-physical copy
 * completion works on still yields the real file. Null when the host has no backing virtual file,
 * which no module root can hold.
 */
fun PsiElement.hostVirtualFile(): VirtualFile? = hostFile()?.originalFile?.virtualFile

/**
 * Deletes a translation property together with its separating comma —
 * plain JsonProperty.delete() leaves `{,"b":…}` / `{…,}` behind, corrupting
 * the file. YAML entries have no separator and are deleted as-is.
 * Must be called inside a write action.
 */
fun deletePropertyAndSeparator(property: PsiElement) {
    if (property is JsonProperty) {
        val prev = generateSequence(property.prevSibling) { it.prevSibling }.firstOrNull { it.text.isNotBlank() }
        val next = generateSequence(property.nextSibling) { it.nextSibling }.firstOrNull { it.text.isNotBlank() }
        when {
            prev?.text == "," -> prev.delete()
            next?.text == "," -> next.delete()
        }
    }
    property.delete()
}
