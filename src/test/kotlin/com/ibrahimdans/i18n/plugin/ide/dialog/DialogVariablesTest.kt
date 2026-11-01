package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * [DialogVariables] on its own: a locale dropping a variable the others carry is warned, and the
 * variables are highlighted where they appear.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class DialogVariablesTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    @Test
    fun `a locale dropping a variable is warned, the others are not`() = myFixture.runWithConfig(config) {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello {{name}}"}""")
        addFileToProject("locales/fr/common.json", """{"greeting": "Bonjour"}""")
        val sources = DialogViewModel(project).loadTranslations(KeysSynchronizer().buildFullKey("common:greeting", config), null, null)
        val areas = sources.mapValues { (_, value) -> JBTextArea(value.orEmpty()) }
        val variables = DialogVariables()
        sources.keys.forEach { variables.warningOf(it) }

        variables.refresh(areas)

        Assertions.assertEquals(PluginBundle.message("dialog.translation.variables.missing", "{{name}}"), variables.warningTextOf("fr"))
        Assertions.assertEquals("", variables.warningTextOf("en"))
        val en = areas.entries.single { it.key.localeLabel() == "en" }.value
        Assertions.assertEquals(1, en.highlighter.highlights.size, "{{name}} highlighted")

        areas.entries.single { it.key.localeLabel() == "fr" }.value.text = "Bonjour {{name}}"
        variables.refresh(areas)
        Assertions.assertEquals("", variables.warningTextOf("fr"))
    }
}
