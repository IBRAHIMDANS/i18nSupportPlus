package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.ide.actions.Candidate
import com.ibrahimdans.i18n.plugin.ide.actions.ExistingKeyFinder
import com.ibrahimdans.i18n.plugin.ide.actions.ExtractionCandidates
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.psi.PsiFile

/**
 * What *Internationalize…* would extract from a set of files — computed, never written.
 *
 * Each file lists its hard-coded texts ([I18nizeSources]) — the way *Batch Extract* finds them,
 * or those of a Vue `<template>` — each with its namespace (the hook's around it, or the default one) and its key: the key already
 * holding its text in the reference locale when there is one, else a new one named by [KeyNaming].
 * A file without any text is left out.
 *
 * A string that is code by its place — a hook's namespace, an import, a `className` — is never
 * planned ([TranslatableText.isNeverProposed]); one that looks like data — a URL, an identifier —
 * is planned unchecked, with the reason ([Entry.uncheckedReason]).
 *
 * Reads the file-type index: call it in a read action, in smart mode, off the EDT.
 */
internal data class I18nizePlan(val files: List<FilePlan>) {

    data class FilePlan(val file: PsiFile, val entries: List<Entry>)

    /**
     * [candidate]'s text, to be written under [key] in [namespace]; [reused] when the key exists already.
     * [uncheckedReason] says why the preview should leave it unchecked; null for a message.
     */
    data class Entry(
        val candidate: Candidate,
        val namespace: String,
        val key: String,
        val reused: Boolean,
        val uncheckedReason: TranslatableText.Reason? = null
    ) {
        /** Whether the preview checks this text at first: only a text reading as a message is. */
        val checkedByDefault: Boolean get() = uncheckedReason == null
    }

    companion object {

        fun of(files: List<PsiFile>): I18nizePlan {
            val project = files.firstOrNull()?.project ?: return I18nizePlan(emptyList())
            val config = Settings.getInstance(project).config()
            val defaultNamespace = config.defaultNamespaces().first()
            val extractors = I18nizeSources.extractors()
            // One naming per set of reference values, i.e. per module: keys never clash inside a module.
            val namings = mutableMapOf<Map<String, String>, KeyNaming>()

            return I18nizePlan(files.mapNotNull { file ->
                val candidates = ExtractionCandidates.withExistingKeys(
                    I18nizeSources.candidates(file).filterNot { TranslatableText.isNeverProposed(it.literal) }, file
                )
                if (candidates.isEmpty()) return@mapNotNull null
                val existing = ExistingKeyFinder.referenceValues(file)
                val naming = namings.getOrPut(existing) {
                    KeyNaming({ namespace, path -> ExistingKeyFinder.spell(namespace, path, config) }, existing)
                }
                FilePlan(file, candidates.map { candidate ->
                    val extractor = extractors.first { it.canExtract(candidate.literal) }
                    val namespace = extractor.scopeNamespaces(candidate.literal).firstOrNull() ?: defaultNamespace
                    val reused = candidate.existingKeys.firstOrNull()
                    Entry(
                        candidate, namespace,
                        reused ?: naming.keyFor(namespace, KeyNaming.componentOf(candidate.literal), candidate.originalText),
                        reused != null,
                        TranslatableText.uncheckedReason(candidate.originalText)
                    )
                })
            })
        }
    }
}
