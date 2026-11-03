package com.ibrahimdans.i18n.plugin.ide

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.util.IconLoader
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the gutter icons' variants: a mapping entry pointing to a missing file, or an SVG the
 * platform cannot read, falls back silently to the classic icon (or an empty one) in the IDE.
 */
class GutterIconMappingTest : PlatformBaseTest() {

    private val names = listOf("gutter_missing", "gutter_partial", "gutter_resolved")

    private fun resource(path: String): URL? = GutterIconMappingTest::class.java.classLoader.getResource(path)

    /** The mapping file as new path -> classic path, its nested objects joined into path segments. */
    private fun mappings(): Map<String, String> {
        val url = resource("I18nIconMappings.json")
        Assertions.assertNotNull(url, "I18nIconMappings.json is not on the classpath")
        val root = JsonParser.parseString(url!!.readText()).asJsonObject
        val result = mutableMapOf<String, String>()
        fun walk(prefix: String, node: JsonObject) {
            node.entrySet().forEach { (key, value) ->
                if (value.isJsonObject) walk("$prefix$key/", value.asJsonObject)
                else result["$prefix$key"] = value.asString
            }
        }
        walk("", root)
        return result
    }

    @Test
    fun mappingCoversEveryGutterIconAndPointsToExistingFiles() {
        val mappings = mappings()
        Assertions.assertEquals(names.map { "icons/expui/$it.svg" }.toSet(), mappings.keys)
        mappings.forEach { (newPath, classicPath) ->
            Assertions.assertNotNull(resource(newPath), "missing New UI icon $newPath")
            Assertions.assertNotNull(resource(classicPath), "missing classic icon $classicPath")
        }
    }

    @Test
    fun everyVariantIsAReadableSvg() {
        val paths = names.flatMap { name ->
            listOf("icons/$name.svg", "icons/${name}_dark.svg", "icons/expui/$name.svg", "icons/expui/${name}_dark.svg")
        }
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        paths.forEach { path ->
            val url = resource(path)
            Assertions.assertNotNull(url, "missing $path")
            Assertions.assertEquals("svg", url!!.openStream().use { xml.parse(it) }.documentElement.tagName, path)
            val icon = IconLoader.findIcon(url)
            Assertions.assertNotNull(icon, "IconLoader cannot load $path")
            Assertions.assertTrue(icon!!.iconWidth > 0 && icon.iconHeight > 0, "empty icon $path")
        }
    }
}
