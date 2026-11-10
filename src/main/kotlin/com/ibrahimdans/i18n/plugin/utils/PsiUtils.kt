package com.ibrahimdans.i18n.plugin.utils

import com.ibrahimdans.i18n.extensions.localization.arb.ArbMetadata
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

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
 * A key of a Flutter ARB catalogue takes its `@key` block with it.
 * Must be called inside a write action.
 */
fun deletePropertyAndSeparator(property: PsiElement) {
    ArbMetadata.blockOf(property)?.let(::deleteWithSeparator)
    deleteWithSeparator(property)
}

private fun deleteWithSeparator(property: PsiElement) {
    if (property is JsonProperty) {
        val prev = generateSequence(property.prevSibling) { it.prevSibling }.firstOrNull { it.text.isNotBlank() }
        val next = generateSequence(property.nextSibling) { it.nextSibling }.firstOrNull { it.text.isNotBlank() }
        when {
            prev?.text == "," -> prev.delete()
            next?.text == "," -> next.delete()
        }
        parseWhileAttached(property)
    }
    property.delete()
}

/**
 * Parses every lazily parsed part of [property] while it still belongs to its JSON file. From 2026.2
 * a JSON object is parsed only when first read; once deleted, the platform walks the detached
 * subtree, parses it there with no JSON around it and logs « No syntax definition found for language
 * TEXT » — an « IDE error occurred » balloon on deleting an ARB key with its `@key` block, or any key
 * whose value is an object. Walking it first leaves nothing to parse after the deletion.
 */
private fun parseWhileAttached(property: PsiElement) {
    property.accept(object : PsiRecursiveElementWalkingVisitor() {})
}

/**
 * Deletes [properties] as [deletePropertyAndSeparator] one after another would, with one PSI deletion
 * per run of neighbouring JSON properties instead of two per property: the comma, then the property.
 * Each PSI deletion is a transaction the platform syncs to the document and announces to every PSI
 * listener; deleting a whole group of keys used to pay that 40 times per file.
 *
 * A run is a sequence of [properties] (and their `@key` blocks) of one object with nothing else
 * between them but whitespace and commas — a kept property, a comment, ends it. Each run takes one
 * comma with it, the one before it if any, else the one after: what deleting its properties one by
 * one left. Properties of other formats (YAML) are deleted one by one. Must be called inside a write
 * action.
 */
fun deletePropertiesAndSeparators(properties: List<PsiElement>) {
    val targets = properties.filter { it.isValid }.flatMap { listOfNotNull(ArbMetadata.blockOf(it), it) }.distinct()
    val (json, others) = targets.partition { it is JsonProperty }
    json.groupBy { it.parent }.forEach { (parent, inParent) ->
        if (parent != null && parent.isValid) runsOf(parent, inParent.toSet()).forEach(::deleteRun)
    }
    others.forEach { if (it.isValid) it.delete() }
}

/** The runs of [targets] among [parent]'s children, in file order: (first, last) of each. */
private fun runsOf(parent: PsiElement, targets: Set<PsiElement>): List<Pair<PsiElement, PsiElement>> {
    val runs = mutableListOf<MutableList<PsiElement>>()
    var open = false
    for (child in generateSequence(parent.firstChild) { it.nextSibling }) {
        when {
            child in targets -> if (open) runs.last() += child else { runs += mutableListOf(child); open = true }
            child.text.isBlank() || child.text == "," -> Unit
            else -> open = false
        }
    }
    return runs.map { it.first() to it.last() }
}

private fun deleteRun(run: Pair<PsiElement, PsiElement>) {
    val (first, last) = run
    if (!first.isValid || !last.isValid) return
    val prev = generateSequence(first.prevSibling) { it.prevSibling }.firstOrNull { it.text.isNotBlank() }
    val next = generateSequence(last.nextSibling) { it.nextSibling }.firstOrNull { it.text.isNotBlank() }
    val start = if (prev?.text == ",") prev else first
    val end = if (prev?.text != "," && next?.text == ",") next else last
    generateSequence(first) { it.nextSibling }.takeWhile { it != last.nextSibling }.forEach(::parseWhileAttached)
    first.parent.deleteChildRange(start, end)
}
