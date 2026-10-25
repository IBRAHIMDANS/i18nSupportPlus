package com.ibrahimdans.i18n.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The settings the check runs with, read without IntelliJ: [JSON_PATH] when the project has one, else
 * the IDE's [PATH], else the defaults.
 *
 * The plugin's `Settings` writes every field that differs from [Config]'s default as an
 * `<option name="…" value="…"/>`, and [Config.modules] as a list of `<ModuleConfig>` beans.
 * [JSON_PATH] takes the same names, for projects whose `.idea/` is not under version control:
 * `{ "keySeparator": "/", "modules": [{ "name": "web", "rootDirectory": "locales" }] }`.
 * Only the options the check uses are read, the others keep their default.
 */
object SettingsFile {

    /** Where the IDE keeps the settings, relative to the project directory. */
    const val PATH = ".idea/i18nSettings.xml"

    /** The settings file meant for version control, relative to the project directory. */
    const val JSON_PATH = "i18n-check.json"

    private const val COMPONENT = "i18nSettings"

    private val OPTIONS = setOf(
        "nsSeparator", "keySeparator", "flatKeys", "gettext", "previewLocale", "foldingPreferredLanguage",
        "defaultNs", "fallbackNs", "translationsRoot", "excludedDirectories", "ignoredLocales", "localeAliases",
    )
    private val MODULE_OPTIONS = setOf("name", "pathTemplate", "fileTemplate", "rootDirectory", "referenceLocale")

    /**
     * The [Config] of [project], the defaults when it has no settings file.
     *
     * @throws IllegalArgumentException a settings file that cannot be read, its path in the message
     */
    fun read(project: Path): Config {
        val json = project.resolve(JSON_PATH)
        if (Files.isRegularFile(json)) return reading(JSON_PATH) { readJson(json) }
        val xml = project.resolve(PATH)
        if (Files.isRegularFile(xml)) return reading(PATH) { readXml(xml) }
        return Config()
    }

    private fun reading(path: String, read: () -> Config): Config =
        try {
            read()
        } catch (e: Exception) {
            throw IllegalArgumentException("$path: ${e.message}", e)
        }

    private fun readXml(file: Path): Config {
        val component = parse(file).getElementsByTagName("component").elements()
            .firstOrNull { it.getAttribute("name") == COMPONENT } ?: return Config()
        return configOf(optionsOf(component), modulesOf(component))
    }

    private fun readJson(file: Path): Config {
        val root = JsonParser.parseString(Files.readString(file)) as? JsonObject
            ?: throw IllegalArgumentException("expected a JSON object")
        val modules = root.remove("modules")?.let { modules ->
            if (!modules.isJsonArray) throw IllegalArgumentException("'modules' must be an array")
            modules.asJsonArray.map { module ->
                val bean = module as? JsonObject ?: throw IllegalArgumentException("each module must be an object")
                moduleOf(stringsOf(bean, MODULE_OPTIONS))
            }
        }.orEmpty()
        return configOf(stringsOf(root, OPTIONS), modules)
    }

    /** The `name` -> value of [json], each a string, a boolean or a number; an unknown name is likely a typo. */
    private fun stringsOf(json: JsonObject, known: Set<String>): Map<String, String> =
        json.entrySet().associate { (name, value) ->
            if (name !in known) throw IllegalArgumentException("unknown setting '$name', expected one of ${known.sorted()}")
            val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("'$name' must be a string or a boolean")
            name to primitive.asString
        }

    private fun configOf(options: Map<String, String>, modules: List<ModuleConfig>): Config {
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
            localeAliases = options["localeAliases"] ?: default.localeAliases,
            modules = modules,
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
        return list.getElementsByTagName("ModuleConfig").elements().map { moduleOf(optionsOf(it)) }
    }

    private fun moduleOf(options: Map<String, String>) = ModuleConfig(
        name = options["name"].orEmpty(),
        pathTemplate = options["pathTemplate"].orEmpty(),
        fileTemplate = options["fileTemplate"].orEmpty(),
        rootDirectory = options["rootDirectory"].orEmpty(),
        referenceLocale = options["referenceLocale"].orEmpty(),
    )

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
