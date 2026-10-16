package com.ibrahimdans.i18n.plugin.ide.annotator

import com.ibrahimdans.i18n.plugin.utils.PluginBundle

/**
 * Why a key or a namespace did not resolve: how the key was read and where it was looked up.
 *
 * "Unresolved key" alone left the user guessing whether the key is missing or the configuration
 * (modules, key templates, separators, translations root) is wrong. The facts are gathered by
 * [AnnotationHelper] from the resolution that produced the annotation — nothing is resolved here —
 * and [explain] turns them into the one to three lines of the annotation's tooltip.
 *
 * Pure: no PSI, no project, so every reason is testable on its own.
 */
data class KeyDiagnosis(
    /** The namespaces the key was looked up in: its own, or the default ones. */
    val namespaces: List<String>,
    val origin: NamespaceOrigin,
    /** The key template of [module], when it decided how the key was read. */
    val keyTemplate: String? = null,
    /** The module owning the file the key is written in, or null outside any module. */
    val module: String? = null,
    /** The translation files the key was looked up in. */
    val searched: List<String> = emptyList(),
    /** Files of [namespaces] lying outside [module], which the lookup therefore skipped. */
    val outsideModule: List<String> = emptyList(),
    /** The part of the key the most complete file holds (`user`), empty when none of it. */
    val found: String = "",
    /** The first segment no file holds (`name`), or null when the key itself was not looked up. */
    val missing: String? = null,
) {

    /** Where the namespaces the key was looked up in come from. */
    enum class NamespaceOrigin {
        /** Written in the key: `t('common:title')`. */
        WRITTEN,

        /** Read out of the key by its module's key template (`{ns}.{key}`). */
        KEY_TEMPLATE,

        /** Declared by the call: its `useTranslation('ns')` hook or its `{ ns }` option. */
        CALL,

        /** None anywhere: the default namespaces. */
        DEFAULT,

        /** The module's key template (`{key}`) reads none: the default namespaces. */
        KEY_TEMPLATE_DEFAULT,

        /** The message files hold no namespace, one file per locale (Paraglide): nothing to say about it. */
        NONE,
    }

    /** The tooltip lines, in the IDE's language. */
    fun explain(): List<String> = explain(PluginBundle::getMessage)

    /** The tooltip lines, with [message] resolving the bundle keys. */
    fun explain(message: (String, Array<out Any>) -> String): List<String> {
        fun msg(key: String, vararg params: Any) = message(key, params)
        val ns = namespaces.joinToString(", ")
        val reading = when (origin) {
            NamespaceOrigin.WRITTEN -> msg("annotator.diagnosis.ns.written", ns)
            NamespaceOrigin.KEY_TEMPLATE -> msg("annotator.diagnosis.ns.template", ns, keyTemplate.orEmpty())
            NamespaceOrigin.CALL -> msg("annotator.diagnosis.ns.call", ns)
            NamespaceOrigin.DEFAULT -> msg("annotator.diagnosis.ns.default", ns)
            NamespaceOrigin.KEY_TEMPLATE_DEFAULT -> msg("annotator.diagnosis.ns.template.default", ns, keyTemplate.orEmpty())
            NamespaceOrigin.NONE -> null
        }
        val first = when {
            module == null -> reading
            reading == null -> msg("annotator.diagnosis.module.only", module)
            else -> msg("annotator.diagnosis.module", reading, module)
        }
        val where = when {
            searched.isNotEmpty() -> msg("annotator.diagnosis.searched", files(searched))
            origin == NamespaceOrigin.NONE ->
                module?.let { msg("annotator.diagnosis.no.message.file.module", it) } ?: msg("annotator.diagnosis.no.message.file.project")
            module != null && outsideModule.isNotEmpty() ->
                msg("annotator.diagnosis.outside.module", ns, module, files(outsideModule))
            module != null -> msg("annotator.diagnosis.no.file.module", ns, module)
            else -> msg("annotator.diagnosis.no.file.project", ns)
        }
        val segment = missing?.let {
            if (found.isEmpty()) msg("annotator.diagnosis.missing.root", it)
            else msg("annotator.diagnosis.missing.segment", found, it)
        }
        return listOfNotNull(first, where, segment)
    }

    private fun files(paths: List<String>): String {
        val shown = paths.take(MAX_FILES).joinToString(", ")
        val more = paths.size - MAX_FILES
        return if (more > 0) "$shown (+$more)" else shown
    }

    private companion object {
        /** A tooltip line, not a listing: the rest is counted. */
        const val MAX_FILES = 3
    }
}
