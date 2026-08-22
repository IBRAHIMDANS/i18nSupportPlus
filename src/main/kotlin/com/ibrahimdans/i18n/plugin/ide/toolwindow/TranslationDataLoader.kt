package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.ibrahimdans.i18n.plugin.utils.isLocaleNamedFile
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/**
 * Loads all translation data from the project's localization sources.
 * Shared utility used by both Tree and Table views.
 */
object TranslationDataLoader {

    private val LOG = Logger.getInstance(TranslationDataLoader::class.java)

    /**
     * Loads all translations as a flat map: fullKey -> (locale -> value).
     * Keys are prefixed with the namespace when it differs from the configured defaultNs,
     * e.g. "common:menu.home" or just "menu.home" for the default namespace.
     *
     * When [moduleConfig] is non-null, only sources whose displayPath starts with
     * [ModuleConfig.rootDirectory] are included (module-scoped view).
     */
    fun loadAllTranslations(project: Project, moduleConfig: ModuleConfig? = null): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        val sources = findSources(project, moduleConfig)
        val defaultNamespaces = Settings.getInstance(project).config().defaultNamespaces()
        // Never empty: defaultNamespaces() falls back to Config's own default ("translation").
        val defaultNamespace = defaultNamespaces.first()

        // One read action for the whole batch rather than one per source. Opening it per source
        // let a write land between two iterations, invalidating the elements the next walk was
        // about to read — the PsiInvalidElementAccessException the cache guard in
        // LocalizationSourceService exists to prevent, reintroduced one step further down. The
        // batch is bounded by the number of locale files, and the walk only reads PSI, so it
        // holds the lock no longer than the work it replaces.
        ReadAction.run<RuntimeException> {
            for (source in sources) {
                val locale = extractLocale(source)
                val namespace = extractNamespace(source, defaultNamespace)
                val nsPrefix = if (namespace in defaultNamespaces) "" else "$namespace:"
                val tree = source.tree
                if (tree != null) {
                    collectLeaves(tree, "", nsPrefix, locale, result)
                } else {
                    LOG.warn("loadAllTranslations: null tree for source '${source.displayPath}' (locale=$locale, ns=$namespace)")
                }
            }
        }
        return result
    }

    /**
     * Discovers all locales available in the project (or in a specific module when [moduleConfig] is non-null).
     */
    fun discoverLocales(project: Project, moduleConfig: ModuleConfig? = null): List<String> {
        val sources = findSources(project, moduleConfig)
        return sources.map { extractLocale(it) }.distinct().sorted()
    }

    /**
     * All localization sources, restricted to [moduleConfig]'s root directory when given.
     * Internal so that write paths (in-place table edit, CSV import) scope their target
     * files exactly like the read paths do — otherwise a module's edit can land in
     * another module's file that happens to share the same namespace and locale.
     */
    internal fun findSources(project: Project, moduleConfig: ModuleConfig? = null): List<LocalizationSource> {
        val service = project.getService(LocalizationSourceService::class.java)
        val all = service.findAllSources(project)
        if (moduleConfig == null || moduleConfig.rootDirectory.isBlank()) return all
        // Filter to only sources whose displayPath starts with the module's rootDirectory
        val rootDir = moduleConfig.rootDirectory.trimEnd('/')
        return all.filter { source -> source.displayPath.startsWith(rootDir) }
    }

    /**
     * Extracts the locale code from a localization source, through the rule shared with the
     * gutter tooltip and the hover popup — see [localeLabel]. This used to carry a private
     * copy of the shape-only regex #122 replaced, under which `src/api/common.json` was
     * loaded as the locale `api`, filling the tool window with a source folder's JSON.
     *   "en/common.json"       -> "en"      (parent dir is the locale)
     *   "en.json"              -> "en"      (stem is the locale)
     *   "locales/en-US/a.json" -> "en-US"
     *   "api/common.json"      -> "common"  (neither is a locale: the file names itself)
     */
    internal fun extractLocale(source: LocalizationSource): String = source.localeLabel()

    /**
     * Extracts the namespace a source contributes its keys under.
     *   "en/common.json"      -> "common"
     *   "en/translation.json" -> "translation"
     *   "en.json"             -> [defaultNamespace] — the file is named after its locale,
     *                            so it holds no namespace at all
     *
     * That last case is what the "one file per locale" layout needs. Returning the stem
     * there — as this did — made `en` and `fr` two namespaces, so `loadAllTranslations`
     * prefixed the very same key twice, once per locale (`en:menu.home`, `fr:menu.home`),
     * each present in one locale only: keys shown in double in the tree and the table,
     * about half the translations counted as missing in the stats, the doubling carried
     * into the CSV export, and *Sync Keys* offering to create the `en:*` keys inside
     * `fr.json`. The decision is delegated to [isLocaleNamedFile], the same rule
     * [extractLocale] uses on the same source, so the two cannot drift apart.
     *
     * [defaultNamespace] defaults to [Config]'s own value rather than the configured one:
     * the callers that omit it (key routing in the synchronizer, the cleanup, the CSV
     * import, the stats navigation, the in-place table edit) only ever compare the result
     * to a namespace written explicitly in a key, and a key prefixed with the default
     * namespace means the same thing as a key carrying no prefix at all. Only
     * [loadAllTranslations], which builds those prefixes, needs the configured value.
     */
    internal fun extractNamespace(
        source: LocalizationSource,
        defaultNamespace: String = Config().defaultNs,
    ): String =
        if (source.isLocaleNamedFile()) defaultNamespace
        else source.name.substringBeforeLast('.')

    /**
     * Recursively collects leaf values from a translation tree.
     * [nsPrefix] is prepended to each full key, e.g. "common:" or "" for the default namespace.
     *
     * Note: findChildren() returns key-literal wrappers (for both JSON and YAML). We use
     * tree.findChild(name) to navigate to the actual value, which correctly distinguishes
     * leaf values from nested objects.
     */
    private fun collectLeaves(
        tree: Tree<PsiElement>,
        prefix: String,
        nsPrefix: String,
        locale: String,
        result: MutableMap<String, MutableMap<String, String>>
    ) {
        val children = tree.findChildren("")
        for (child in children) {
            val childName = extractNodeName(child)
            if (childName.isNullOrEmpty()) continue

            val fullPath = if (prefix.isEmpty()) childName else "$prefix.$childName"
            val valueTree = tree.findChild(childName)
            if (valueTree == null || valueTree.isLeaf()) {
                val value = if (valueTree != null) extractLeafValue(valueTree) else ""
                result.getOrPut("$nsPrefix$fullPath") { mutableMapOf() }[locale] = value
            } else {
                collectLeaves(valueTree, fullPath, nsPrefix, locale, result)
            }
        }
    }

    /**
     * Extracts the key name from a tree node's underlying PSI element.
     */
    private fun extractNodeName(node: Tree<PsiElement>): String? {
        val psi = node.value()
        return when (psi) {
            is JsonProperty -> psi.name
            is JsonStringLiteral -> psi.value
            else -> {
                // Try YAMLKeyValue via reflection to avoid hard dependency on YAML plugin
                try {
                    val klass = Class.forName("org.jetbrains.yaml.psi.YAMLKeyValue")
                    if (klass.isInstance(psi)) {
                        klass.getMethod("getKeyText").invoke(psi) as? String
                    } else null
                } catch (e: Exception) {
                    LOG.debug("extractNodeName: YAML reflection failed: ${e.message}", e)
                    null
                }
            }
        }
    }

    /**
     * Extracts the text value from a leaf node.
     */
    private fun extractLeafValue(node: Tree<PsiElement>): String {
        val psi = node.value()
        if (psi is JsonProperty) {
            return psi.value?.text?.removeSurrounding("\"") ?: ""
        }
        // Try YAMLKeyValue via reflection to avoid hard dependency on YAML plugin
        try {
            val klass = Class.forName("org.jetbrains.yaml.psi.YAMLKeyValue")
            if (klass.isInstance(psi)) {
                return klass.getMethod("getValueText").invoke(psi) as? String ?: ""
            }
        } catch (e: Exception) {
            LOG.debug("extractLeafValue: YAML reflection failed: ${e.message}", e)
        }
        return psi.text?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
    }
}
