package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `docs/configuration.md` lists the names `i18n-check.json` takes by hand, in the bullet that opens
 * with "It takes the names": this test keeps that list and [ProjectConfigFile] together.
 */
class ConfigDocumentationTest {

    private val root = File(".").canonicalFile.let { dir ->
        generateSequence(dir) { it.parentFile }.first { File(it, "docs/configuration.md").isFile }
    }

    /** The names written in code (`` `name` ``) in the bullet listing what the file takes, the module fields aside. */
    private val documented: Set<String> by lazy {
        val bullet = root.resolve("docs/configuration.md").readLines().single { it.startsWith("- It takes the names of the settings") }
        Regex("`([A-Za-z]+)`").findAll(bullet.substringBefore("(`name`")).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every setting the file takes is documented`() {
        val taken = ProjectConfigFile.names - ProjectConfigFile.PREVIEW_LOCALE + ProjectConfigFile.MODULES

        assertEquals(emptySet<String>(), taken - documented, "names i18n-check.json takes that docs/configuration.md does not list")
    }

    @Test
    fun `no machine setting is documented as a field of the file`() {
        val machine = Config.SCOPES.filterValues { it == SettingScope.MACHINE }.keys

        assertEquals(emptySet<String>(), documented intersect machine, "settings that stay on each machine, listed as fields of i18n-check.json")
    }
}
