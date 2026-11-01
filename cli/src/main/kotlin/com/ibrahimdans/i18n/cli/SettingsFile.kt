package com.ibrahimdans.i18n.cli

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ProjectConfigFile
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
 * `{ "keySeparator": "/", "modules": [{ "name": "web", "rootDirectory": "locales" }] }`. The IDE
 * reads that file too: [ProjectConfigFile] is shared by both.
 */
object SettingsFile {

    /** Where the IDE keeps the settings, relative to the project directory. */
    const val PATH = ".idea/i18nSettings.xml"

    /** The settings file meant for version control, relative to the project directory. */
    const val JSON_PATH = ProjectConfigFile.PATH

    private const val COMPONENT = "i18nSettings"

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
        return ProjectConfigFile.contentOf(optionsOf(component), modulesOf(component)).applyTo(Config())
    }

    private fun readJson(file: Path): Config = ProjectConfigFile.parse(Files.readString(file)).applyTo(Config())

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
        return list.getElementsByTagName("ModuleConfig").elements().map { ProjectConfigFile.moduleOf(optionsOf(it)) }
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
