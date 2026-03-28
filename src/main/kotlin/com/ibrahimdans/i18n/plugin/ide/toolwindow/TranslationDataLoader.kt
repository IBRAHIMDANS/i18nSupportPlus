package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
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

        for (source in sources) {
            val locale = extractLocale(source)
            val namespace = extractNamespace(source)
            val nsPrefix = if (namespace in defaultNamespaces) "" else "$namespace:"
            val tree = source.tree
            if (tree == null) {
                LOG.warn("loadAllTranslations: null tree for source '${source.displayPath}' (locale=$locale, ns=$namespace)")
                continue
            }
            ReadAction.compute<Unit, Throwable> {
                collectLeaves(tree, "", nsPrefix, locale, result)
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

    private fun findSources(project: Project, moduleConfig: ModuleConfig? = null): List<LocalizationSource> {
        val service = project.getService(LocalizationSourceService::class.java)
        val all = service.findAllSources(project)
        if (moduleConfig == null || moduleConfig.rootDirectory.isBlank()) return all
        // Filter to only sources whose displayPath starts with the module's rootDirectory
        val rootDir = moduleConfig.rootDirectory.trimEnd('/')
        return all.filter { source -> source.displayPath.startsWith(rootDir) }
    }

    /**
     * Extracts the locale code from a localization source.
     *   "en/common.json"     -> "en"   (parent dir is locale)
     *   "en.json"            -> "en"   (stem is locale)
     *   "locales/en-US/a.json" -> "en-US"
     */
    internal fun extractLocale(source: LocalizationSource): String {
        val parent = source.parent
        return if (looksLikeLocale(parent)) parent
        else source.name.substringBeforeLast('.')
    }

    /**
     * Extracts the namespace from a localization source (the filename stem).
     *   "en/common.json"     -> "common"
     *   "en/translation.json" -> "translation"
     *   "en.json"            -> "en"
     */
    internal fun extractNamespace(source: LocalizationSource): String =
        source.name.substringBeforeLast('.')

    private fun looksLikeLocale(name: String): Boolean =
        name.matches(Regex("[a-zA-Z]{2,3}([_-][a-zA-Z]{2,4})?"))

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
