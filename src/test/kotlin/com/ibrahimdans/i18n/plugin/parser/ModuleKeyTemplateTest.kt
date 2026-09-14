package com.ibrahimdans.i18n.plugin.parser

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.ibrahimdans.i18n.plugin.utils.KeyElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A module's key template decides how the keys of its code are read. */
class ModuleKeyTemplateTest : PlatformBaseTest() {

    @Test
    fun theAcceptedTemplates() {
        assertEquals(KeySyntax.Namespaced(":"), KeyTemplate.parse("{ns}:{key}"))
        assertEquals(KeySyntax.Namespaced("."), KeyTemplate.parse(" {namespace}.{key} "))
        assertEquals(KeySyntax.NoNamespace, KeyTemplate.parse("{key}"))
        assertNull(KeyTemplate.parse(""))
        assertNull(KeyTemplate.parse("{key}:{ns}"), "the namespace must lead")
        assertNull(KeyTemplate.parse("{ns}{key}"), "a namespace needs a separator")
        assertNull(KeyTemplate.parse("{ns}->{key}"), "keys are split on single characters")
    }

    private val modules = Config(modules = listOf(
        ModuleConfig(name = "dotted", rootDirectory = "apps/dotted", keyTemplate = "{ns}.{key}"),
        ModuleConfig(name = "bare", rootDirectory = "apps/bare", keyTemplate = "{key}"),
        ModuleConfig(name = "slash", rootDirectory = "apps/slash", keyTemplate = "{ns}/{key}"),
    ))

    private fun parseIn(path: String, key: String): Pair<String?, List<String>>? {
        val file = myFixture.addFileToProject(path, "t('$key')")
        val fullKey = RawKeyParser(project).parse(RawKey(listOf(KeyElement.literal(key))), file) ?: return null
        return fullKey.ns?.text to fullKey.compositeKey.map { it.text }
    }

    @Test
    fun aModuleTemplateReplacesTheProjectSeparators() = myFixture.runWithConfig(modules) {
        assertEquals("common" to listOf("title"), parseIn("apps/dotted/src/A.js", "common.title"))
        assertEquals(null to listOf("common", "title"), parseIn("apps/bare/src/A.js", "common.title"))
        assertEquals("common" to listOf("menu", "home"), parseIn("apps/slash/src/A.js", "common/menu.home"))
    }

    @Test
    fun outsideAnyModuleTheProjectSeparatorsApply() = myFixture.runWithConfig(modules) {
        assertEquals("common" to listOf("title"), parseIn("src/A.js", "common:title"))
        assertEquals(null to listOf("common", "title"), parseIn("src/B.js", "common.title"))
    }
}
