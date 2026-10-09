package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.dialog.DialogViewModel
import com.ibrahimdans.i18n.plugin.ide.dialog.KeyCheck
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.distance
import com.ibrahimdans.i18n.plugin.utils.hasRecognizedLocale
import com.ibrahimdans.i18n.plugin.utils.hostVirtualFile
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.ibrahimdans.i18n.plugin.utils.pathToRoot
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import java.text.Normalizer

/**
 * What the user decided in the extraction dialog.
 */
sealed interface ExtractAnswer {
    /** Point the code at [key], which already holds the text: nothing is written to the translations. */
    data class Reuse(val key: String) : ExtractAnswer

    /**
     * Create [key] under [namespace] with the [values] typed, one per file, blank ones included:
     * see [ExtractKeyModel.writes] for what a blank one receives. A file absent from [values] is
     * left untouched.
     */
    data class Create(
        val namespace: String?,
        val key: String,
        val values: Map<LocalizationSource, String>,
        val copyReference: Boolean = false,
    ) : ExtractAnswer
}

/**
 * Everything the extraction dialog shows, read once outside the EDT: the namespaces, their files
 * and keys, the keys already holding the text — so that the dialog itself never walks the index.
 *
 * The rules are plain functions of that snapshot, exercised by tests without a dialog: the key
 * the code gets ([codeKey]), the call inserted ([preview]), the key check, the files written.
 */
internal class ExtractKeyModel(
    /** The text extracted, as it will be stored. */
    val text: String,
    /** Keys whose reference-locale value already is [text], spelled as the code writes them. */
    val existingKeys: List<String>,
    /** The namespaces offered, in display order; empty when the project has no translation file. */
    val namespaces: List<String>,
    private val sourcesByNamespace: Map<String, List<LocalizationSource>>,
    private val keysByNamespace: Map<String, Set<String>>,
    /** The locale whose field is filled with [text]: the declared reference, else the most complete. */
    val referenceLocale: String?,
    private val config: Config,
    private val template: (argument: String) -> String,
    /**
     * What an unqualified key resolves against where the text stands, the first by default:
     * the namespaces of the `useTranslation` whose `t` the [template] calls. Empty for the
     * project's default namespaces.
     */
    private val scopeNamespaces: List<String> = emptyList(),
) {
    private val sourceCache = sourcesByNamespace.toMutableMap()
    private val keyCache = keysByNamespace.toMutableMap()

    /** Empty when keys are flat: a dot is then part of the key, not a level. */
    val keySeparator: String = if (config.usesFlatKeys()) "" else config.keySeparator

    /**
     * The namespace selected first: the one the `t` in scope reads by default, else a default
     * namespace of the project, when it has files — else the first.
     */
    val initialNamespace: String? =
        (scopeNamespaces.take(1) + config.defaultNamespaces()).firstOrNull { it in namespaces } ?: namespaces.firstOrNull()

    /**
     * The name field's starting point: the text in the naming style the keys of [namespace]
     * follow, or those of the whole project when its own tell nothing — `Save changes` →
     * `saveChanges` where keys read `noTrustee`, `save_changes` where they read `no_trustee`.
     */
    fun proposedName(namespace: String?): String {
        val style = keyStyle(keyCache[namespace].orEmpty()) ?: keyStyle(keyCache.values.flatten()) ?: KeyStyle.SNAKE
        return proposeName(text, style)
    }

    /**
     * The groups of [namespace] a key can go under, as typed in the parent field: `myAccount`,
     * `myAccount.myTrustees`… None when keys are flat.
     */
    fun parents(namespace: String?): List<String> {
        if (keySeparator.isEmpty()) return emptyList()
        return keyCache[namespace].orEmpty()
            .flatMap { key -> key.split(LOADER_SEPARATOR).let { parts -> (1 until parts.size).map { parts.take(it) } } }
            .map { it.joinToString(keySeparator) }
            .distinct()
            .sorted()
    }

    /** [name] under [parent]: `myAccount.myTrustees` and `title` give `myAccount.myTrustees.title`. */
    fun join(parent: String, name: String): String =
        listOf(parent.trim().removeSuffix(keySeparator), name.trim())
            .filter { it.isNotEmpty() }
            .joinToString(keySeparator)

    /**
     * Why [key] cannot be written in [namespace] — a level of it already is a translation, which
     * cannot hold keys, or the key already holds keys, which a value would wipe out — or null.
     */
    fun conflict(namespace: String?, key: String): Conflict? {
        if (keySeparator.isEmpty() || key.isBlank()) return null
        val existing = keyCache[namespace].orEmpty().map { it.replace(LOADER_SEPARATOR, keySeparator) }.toSet()
        val parts = key.trim().split(keySeparator)
        (1 until parts.size).map { parts.take(it).joinToString(keySeparator) }.firstOrNull { it in existing }
            ?.let { return Conflict.LeafAsParent(it) }
        if (existing.any { it.startsWith(key.trim() + keySeparator) }) return Conflict.GroupAsLeaf(key.trim())
        return null
    }

    /** See [conflict]. */
    sealed interface Conflict {
        /** [path] is a translation: nothing can go under it. */
        data class LeafAsParent(val path: String) : Conflict

        /** [path] already holds keys: a value there would replace them all. */
        data class GroupAsLeaf(val path: String) : Conflict
    }

    /** How the keys of a project name their levels. */
    enum class KeyStyle { CAMEL, SNAKE, KEBAB }

    /**
     * The prefix the key field shows before the key, as the code will read it: empty for a
     * default namespace, or when the project's keys carry no namespace.
     */
    fun prefix(namespace: String?): String = codeKey(namespace, PROBE).removeSuffix(PROBE)

    /** The files of [namespace], the reference locale first, then by locale. */
    fun sources(namespace: String?): List<LocalizationSource> =
        sourceCache[namespace].orEmpty().sortedWith(compareBy({ it.localeLabel() != referenceLocale }, { it.localeLabel() }))

    /** Records the files of a namespace created while the dialog is open. */
    fun addNamespace(namespace: String, sources: List<LocalizationSource>) {
        sourceCache[namespace] = sources
        keyCache[namespace] = emptySet()
    }

    fun checkKey(namespace: String?, key: String): KeyCheck =
        DialogViewModel.checkKey(key, keySeparator, keyCache[namespace].orEmpty())

    /**
     * [key] in [namespace] as the code writes it, exactly as [ExistingKeyFinder] spells the keys
     * it finds: a default namespace is left out, and so is any namespace when keys are flat.
     *
     * The intention used to insert the key as typed, whatever file the popup then wrote it to:
     * `'ddd'` written to `deposit-box.json` resolved nowhere.
     */
    fun codeKey(namespace: String?, key: String): String {
        val resolved = namespace ?: config.defaultNamespaces().first()
        if (scopeNamespaces.isEmpty() || !qualifies()) return ExistingKeyFinder.spell(resolved, path(key), config)
        // Under `useTranslation('account')`, an unqualified key reads `account`, not the defaults:
        // every other namespace, a default one included, is written out.
        val joined = path(key).joinToString(config.keySeparator)
        return if (resolved == scopeNamespaces.first()) joined else qualify(resolved, joined)
    }

    /**
     * The call pointing at [key], a key that already exists, spelled as [ExistingKeyFinder] found
     * it: without its namespace when that is a default one — which the `t` of a
     * `useTranslation('account')` would look up in `account`. Such a key is qualified then.
     */
    fun reusePreview(key: String): String {
        val unqualified = qualifies() && !key.contains(config.nsSeparator)
        val default = config.defaultNamespaces().first()
        val spelled = if (unqualified && scopeNamespaces.isNotEmpty() && scopeNamespaces.first() != default) qualify(default, key) else key
        return template("'$spelled'")
    }

    /** True when the code writes namespaces with the namespace separator, which [qualify] adds. */
    private fun qualifies(): Boolean =
        !config.usesFlatKeys() && !config.firstComponentNs && config.nsSeparator.isNotEmpty()

    private fun qualify(namespace: String, key: String): String = namespace + config.nsSeparator + key

    /** The call replacing the text: `{i18n.t('account:save')}`. */
    fun preview(namespace: String?, key: String): String = template("'${codeKey(namespace, key)}'")

    /** A value to write into [source]; when not [overwrite], only where the key is missing. */
    data class Write(val source: LocalizationSource, val value: String, val overwrite: Boolean)

    /**
     * What [answer] writes. A value typed is written as is; a field left blank receives the
     * reference locale's value when [ExtractAnswer.Create.copyReference], an empty string
     * otherwise — the key then exists in every locale, and *Empty translation value* points at
     * the ones left to translate. A blank field never overwrites a translation the key already has.
     */
    fun writes(answer: ExtractAnswer.Create): List<Write> {
        val reference = answer.values.entries.firstOrNull { it.key.localeLabel() == referenceLocale && it.value.isNotBlank() }?.value
            ?: answer.values.values.firstOrNull { it.isNotBlank() }
            ?: text
        return answer.values.map { (source, value) ->
            if (value.isNotBlank()) Write(source, value, overwrite = true)
            else Write(source, if (answer.copyReference) reference else "", overwrite = false)
        }
    }

    /** The key the files are written under. Built rather than parsed: the files are already chosen. */
    fun fullKey(namespace: String?, key: String): FullKey =
        FullKey(codeKey(namespace, key), namespace?.let { Literal(it) }, path(key).map { Literal(it) })

    private fun path(key: String): List<String> =
        if (keySeparator.isEmpty()) listOf(key.trim()) else key.trim().split(keySeparator)

    companion object {
        /** A one-segment key whose spelling leaves the namespace prefix around it. */
        private const val PROBE = "k"

        /** How [TranslationDataLoader] joins levels in the keys [keysByNamespace] holds, whatever the settings. */
        private const val LOADER_SEPARATOR = "."

        private val PLURAL_SUFFIX = Regex("_(zero|one|two|few|many|other)$")

        /** Words kept in a proposed name: a sentence makes a poor key. */
        private const val MAX_NAME_WORDS = 5

        /**
         * The style most levels of [keys] follow, groups included — a last level is often a
         * single word (`title`) that tells nothing; null when no level tells. i18next's plural
         * suffixes (`item_one`, `item_other`) are not a style, and are left out.
         */
        internal fun keyStyle(keys: Collection<String>): KeyStyle? {
            val names = keys.flatMap { it.split(LOADER_SEPARATOR) }.map { it.replace(PLURAL_SUFFIX, "") }.distinct()
            val counts = mapOf(
                KeyStyle.CAMEL to names.count { name -> name.any { it.isUpperCase() } && '_' !in name && '-' !in name },
                KeyStyle.SNAKE to names.count { '_' in it },
                KeyStyle.KEBAB to names.count { '-' in it },
            )
            return counts.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key
        }

        /** The first words of [text] as a name in [style]: `Créer un compte` → `creerUnCompte`. */
        internal fun proposeName(text: String, style: KeyStyle): String {
            val words = proposeKey(text).split('_').filter { it.isNotEmpty() }.take(MAX_NAME_WORDS)
            return when (style) {
                KeyStyle.CAMEL -> words.mapIndexed { i, word -> if (i == 0) word else word.replaceFirstChar(Char::uppercaseChar) }.joinToString("")
                KeyStyle.SNAKE -> words.joinToString("_")
                KeyStyle.KEBAB -> words.joinToString("-")
            }
        }

        /**
         * Reads the snapshot. Walks the file-type index and every translation file: never on the
         * EDT, always in a read action.
         */
        fun load(
            project: Project,
            caller: PsiElement,
            text: String,
            existingKeys: List<String>,
            template: (String) -> String,
            scopeNamespaces: List<String> = emptyList(),
        ): ExtractKeyModel {
            val viewModel = DialogViewModel(project)
            val callerPath = caller.hostVirtualFile()?.let { pathToRoot(project.basePath ?: "", it.path) }.orEmpty()
            val sources = viewModel.loadNamespaces()
                .associateWith { offered(viewModel.sourcesFor(listOf(it), caller), callerPath) }
                .filterValues { it.isNotEmpty() }
            val namespaces = sources.keys.toList()
            return ExtractKeyModel(
                text = text,
                existingKeys = existingKeys,
                namespaces = namespaces,
                sourcesByNamespace = sources,
                keysByNamespace = namespaces.associateWith { viewModel.existingKeys(it) },
                referenceLocale = viewModel.localeToCopyFrom(sources.values.flatten()),
                config = Settings.getInstance(project).config(),
                template = template,
                scopeNamespaces = scopeNamespaces,
            )
        }

        /**
         * The files of a namespace the dialog offers: those of a recognised locale — all of them
         * when none is, in a project with a single, unnamed language — under the translation root
         * nearest [callerPath], the code file's project-relative path.
         *
         * Without a module configuration, a namespace is looked up project-wide: a `common.json`
         * lying in another folder of the repository was offered as a locale called `common`.
         */
        internal fun offered(sources: List<LocalizationSource>, callerPath: String): List<LocalizationSource> {
            val located = sources.filter { it.hasRecognizedLocale() }.ifEmpty { sources }
            if (located.isEmpty()) return emptyList()
            val byRoot = located.groupBy { rootOf(it) }
            val callerDir = callerPath.trim('/').substringBeforeLast('/', "")
            val nearest = byRoot.keys.minBy { distance(it, callerDir) }
            return byRoot.getValue(nearest)
        }

        /** `public/locales` for `public/locales/en/common.json` as for `public/locales/en.json`. */
        private fun rootOf(source: LocalizationSource): String {
            val directory = source.displayPath.trim('/').substringBeforeLast('/', "")
            return if (source.isLocaleNamedFile()) directory else directory.substringBeforeLast('/', "")
        }

        /**
         * `Créer un compte !` → `creer_un_compte`: accents dropped, lower case, runs of anything
         * but letters and digits as `_`, at most 50 characters.
         */
        internal fun proposeKey(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .lowercase().trim()
                .replace(NOT_KEY_CHARS, "_")
                .take(MAX_KEY_LENGTH)
                .trim('_')

        private val DIACRITICS = Regex("\\p{Mn}+")
        private val NOT_KEY_CHARS = Regex("[^a-z0-9]+")
        private const val MAX_KEY_LENGTH = 50
    }
}
