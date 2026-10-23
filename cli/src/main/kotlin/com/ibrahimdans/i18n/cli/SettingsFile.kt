package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The plugin's settings as the IDE stores them in `.idea/i18nSettings.xml`, read without IntelliJ.
 *
 * The plugin's `Settings` writes every field that differs from [Config]'s default as an
 * `<option name="…" value="…"/>`, and [Config.modules] as a list of `<ModuleConfig>` beans:
 * only the options the check uses are read, the others keep their default.
 */
object SettingsFile {

    /** Where the IDE keeps the settings, relative to the project directory. */
    const val PATH = ".idea/i18nSettings.xml"

    private const val COMPONENT = "i18nSettings"

    /** The [Config] of [project], the defaults when it has no settings file. */
    fun read(project: Path): Config {
        val file = project.resolve(PATH)
        if (!Files.isRegularFile(file)) return Config()
        val component = parse(file).getElementsByTagName("component").elements()
            .firstOrNull { it.getAttribute("name") == COMPONENT } ?: return Config()
        val options = optionsOf(component)
        val default = Config()
        return default.copy(
            nsSeparator = options["nsSeparator"] ?: default.nsSeparator,
            keySeparator = options["keySeparator"] ?: default.keySeparator,
            flatKeys = options["flatKeys"]?.toBoolean() ?: default.flatKeys,
            gettext = options["gettext"]?.toBoolean() ?: default.gettext,
            previewLocale = options["previewLocale"] ?: default.previewLocale,
            foldingPreferredLanguage = options["foldingPreferredLanguage"] ?: default.foldingPreferredLanguage,
            defaultNs = options["defaultNs"] ?: default.defaultNs,
            fallbackNs = options["fallbackNs"] ?: default.fallbackNs,
            translationsRoot = options["translationsRoot"] ?: default.translationsRoot,
            excludedDirectories = options["excludedDirectories"] ?: default.excludedDirectories,
            ignoredLocales = options["ignoredLocales"] ?: default.ignoredLocales,
            modules = modulesOf(component),
        )
    }

    private fun parse(file: Path) = DocumentBuilderFactory.newInstance().apply {
        // A settings file has no reason to declare a DTD: refusing one rules out external entities.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }.newDocumentBuilder().parse(file.toFile())

    /** The `name` -> `value` of [element]'s own `<option>` children, not those of the beans under it. */
    private fun optionsOf(element: Element): Map<String, String> =
        element.childNodes.elements()
            .filter { it.tagName == "option" && it.hasAttribute("value") }
            .associate { it.getAttribute("name") to it.getAttribute("value") }

    private fun modulesOf(component: Element): List<ModuleConfig> {
        val list = component.childNodes.elements()
            .firstOrNull { it.tagName == "option" && it.getAttribute("name") == "modules" } ?: return emptyList()
        return list.getElementsByTagName("ModuleConfig").elements().map { bean ->
            val options = optionsOf(bean)
            ModuleConfig(
                name = options["name"].orEmpty(),
                pathTemplate = options["pathTemplate"].orEmpty(),
                fileTemplate = options["fileTemplate"].orEmpty(),
                rootDirectory = options["rootDirectory"].orEmpty(),
                referenceLocale = options["referenceLocale"].orEmpty(),
            )
        }
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
