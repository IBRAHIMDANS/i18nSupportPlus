package com.ibrahimdans.i18n.plugin.ide.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProjectConfigSchemaTest {

    private val schema: JsonObject = JsonParser.parseString(ProjectConfigSchema.text()).asJsonObject
    private val properties: JsonObject = schema.getAsJsonObject("properties")
    private val module: JsonObject = properties.getAsJsonObject(ProjectConfigFile.MODULES).getAsJsonObject("items")
    private val moduleProperties: JsonObject = module.getAsJsonObject("properties")

    @Test
    fun `every team setting of Config SCOPES has its entry, and nothing else`() {
        val team = Config.SCOPES.filterValues { it == SettingScope.TEAM }.keys
        assertEquals(team + ProjectConfigFile.PREVIEW_LOCALE, properties.keySet())
    }

    @Test
    fun `every field a module takes has its entry, and nothing else`() {
        assertEquals(ProjectConfigFile.moduleShapes.keys, moduleProperties.keySet())
    }

    @Test
    fun `a name the file does not take is refused, at the top and in a module`() {
        assertFalse(schema.get("additionalProperties").asBoolean)
        assertFalse(module.get("additionalProperties").asBoolean)
    }

    @Test
    fun `a flag is a boolean, a setting with fixed values lists them, the others are strings`() {
        assertEquals("boolean", properties.getAsJsonObject("flatKeys").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("keySeparator").get("type").asString)
        assertEquals(
            KeyOrder.entries.map { it.name }.sorted(),
            properties.getAsJsonObject("keyOrder").getAsJsonArray("enum").map { it.asString },
        )
        assertEquals("boolean", moduleProperties.getAsJsonObject("enabled").get("type").asString)
        assertEquals("string", moduleProperties.getAsJsonObject("pathTemplate").get("type").asString)
    }

    @Test
    fun `a module's code roots are a string or an array of strings`() {
        val anyOf = moduleProperties.getAsJsonObject(ProjectConfigFile.CODE_ROOTS).getAsJsonArray("anyOf")
        assertEquals(listOf("string", "array"), anyOf.map { it.asJsonObject.get("type").asString })
    }

    @Test
    fun `a file the IDE exports is valid against the schema's names`() {
        val config = Config(keySeparator = "/", flatKeys = true, modules = listOf(ModuleConfig(name = "web", rootDirectory = "apps/web")))
        val exported = JsonParser.parseString(ProjectConfigFile.export(config)).asJsonObject
        assertEquals(emptySet<String>(), exported.keySet() - properties.keySet())
        val exportedModule = exported.getAsJsonArray(ProjectConfigFile.MODULES)[0].asJsonObject
        assertEquals(emptySet<String>(), exportedModule.keySet() - moduleProperties.keySet())
    }
}
