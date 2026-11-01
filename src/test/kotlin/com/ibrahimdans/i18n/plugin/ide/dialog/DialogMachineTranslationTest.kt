package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.translate.Cancellation
import com.ibrahimdans.i18n.plugin.translate.Translation
import com.ibrahimdans.i18n.plugin.translate.TranslationProvider
import com.ibrahimdans.i18n.plugin.translate.TranslationRequest
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * [DialogMachineTranslation] on its own, without a dialog around it: what it shows, the context it
 * proposes, and the proposals it keeps.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class DialogMachineTranslationTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")
    private val original = TranslationDialog.machineTranslator
    private val originalReadsInstruction = TranslationDialog.machineReadsInstruction

    private val engine = object : TranslationProvider {
        override fun translate(request: TranslationRequest, cancellation: Cancellation?): List<Translation> =
            listOf(Translation.Done("[${request.target}] ${request.texts.single()}"))
    }

    @AfterEach
    fun restore() {
        TranslationDialog.machineTranslator = original
        TranslationDialog.machineReadsInstruction = originalReadsInstruction
    }

    private fun machine(key: () -> String, areas: Map<LocalizationSource, JBTextArea> = emptyMap()): DialogMachineTranslation {
        val viewModel = DialogViewModel(project)
        return DialogMachineTranslation(
            project, KeysSynchronizer().buildFullKey("common:greeting", config), null, null, viewModel,
            typedKey = key,
            textAreas = { areas },
            moduleOf = { null },
        )
    }

    @Test
    fun `nothing is offered without an engine`() = myFixture.runWithConfig(config) {
        TranslationDialog.machineTranslator = { null }
        TranslationDialog.machineReadsInstruction = { true }
        val machine = machine({ "common:greeting" })
        Assertions.assertFalse(machine.enabled)
        Assertions.assertFalse(machine.instructionField.isVisible)
    }

    @Test
    fun `the context follows the key until the user edits it`() = myFixture.runWithConfig(config) {
        TranslationDialog.machineTranslator = { engine }
        var key = "common:greeting"
        val machine = machine({ key })
        val parent = Disposer.newDisposable()
        try {
            machine.start(parent)
            Assertions.assertTrue("common:greeting" in machine.contextField.text, machine.contextField.text)

            key = "common:farewell"
            machine.refreshContext()
            Assertions.assertTrue("common:farewell" in machine.contextField.text, machine.contextField.text)

            machine.contextField.text = "A button"
            key = "common:other"
            machine.refreshContext()
            Assertions.assertEquals("A button", machine.contextField.text)
        } finally {
            Disposer.dispose(parent)
        }
    }

    @Test
    fun `only empty locales are filled, and each fill is kept as a proposal`() = myFixture.runWithConfig(config) {
        TranslationDialog.machineTranslator = { engine }
        addFileToProject("locales/en/common.json", """{"greeting": "Hello"}""")
        addFileToProject("locales/fr/common.json", """{"greeting": ""}""")
        val sources = DialogViewModel(project).loadTranslations(KeysSynchronizer().buildFullKey("common:greeting", config), null, null)
        val areas = sources.mapValues { (_, value) -> JBTextArea(value.orEmpty()) }
        val machine = machine({ "common:greeting" }, areas)

        machine.translateEmptyLocales()

        val byLocale = areas.entries.associate { it.key.localeLabel() to it.value.text }
        Assertions.assertEquals(mapOf("en" to "Hello", "fr" to "[fr] Hello"), byLocale)
        Assertions.assertEquals(listOf("[fr] Hello"), machine.proposals.values.map { it.text })
        Assertions.assertEquals("", machine.translateStatus.text)
    }

    @Test
    fun `a plural form names its category and its other form`() {
        Assertions.assertEquals("few" to "common:file_other", DialogMachineTranslation.pluralFormOf("common:file_few", ".", "_"))
        Assertions.assertNull(DialogMachineTranslation.pluralFormOf("common:file", ".", "_"))
    }
}
