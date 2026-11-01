package com.ibrahimdans.i18n.plugin.ide.dialog

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.KeysSynchronizer
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * [DialogKeyField] on its own, without a dialog around it: the status under the field, the key *OK*
 * writes and what it refuses.
 *
 * Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`.
 */
class DialogKeyFieldTest : PlatformBaseTest() {

    private val config = Config(defaultNs = "translation")

    /** A CREATE field opened on `common:`, scoped to the `common` files, `greeting` already there. */
    private fun createField(): DialogKeyField {
        addFileToProject("locales/en/common.json", """{"greeting": "Hello"}""")
        val viewModel = DialogViewModel(project)
        val field = DialogKeyField(project, KeysSynchronizer().buildFullKey("common:", config), Mode.CREATE, viewModel) {}
        field.install {}
        field.namespaceComboBox()
        val namespace = field.selectedNamespace()
        Assertions.assertEquals("common", namespace)
        field.scopeTo(namespace, viewModel.loadSourcesForNamespace("common", null, null).keys, otherModules = false)
        return field
    }

    @Test
    fun `the status follows the key as it is typed`() = myFixture.runWithConfig(config) {
        val field = createField()

        field.field.text = ""
        Assertions.assertEquals("", field.statusLabel.text, "nothing said before anything is typed")

        field.field.text = "greeting"
        Assertions.assertEquals(PluginBundle.message("dialog.translation.key.status.taken"), field.statusLabel.text)

        field.field.text = "farewell"
        Assertions.assertEquals(PluginBundle.message("dialog.translation.key.status.available"), field.statusLabel.text)
    }

    @Test
    fun `the key written goes under the namespace selected`() = myFixture.runWithConfig(config) {
        val field = createField()
        field.field.text = "farewell"
        Assertions.assertEquals("common:farewell", field.effectiveKey()?.source)
        Assertions.assertNull(field.validate())
    }

    @Test
    fun `an empty key is refused, an existing one is not`() = myFixture.runWithConfig(config) {
        val field = createField()
        field.field.text = "  "
        Assertions.assertEquals(PluginBundle.message("dialog.translation.error.key.empty"), field.validate()?.message)
        field.field.text = "greeting"
        Assertions.assertNull(field.validate(), "correcting a value is what the dialog is for")
    }

    @Test
    fun `an edited key is the one opened, and never refused`() = myFixture.runWithConfig(config) {
        val fullKey = KeysSynchronizer().buildFullKey("common:greeting", config)
        val field = DialogKeyField(project, fullKey, Mode.EDIT, DialogViewModel(project)) {}
        field.install {}
        Assertions.assertFalse(field.field.isEditable)
        Assertions.assertSame(fullKey, field.effectiveKey())
        Assertions.assertNull(field.validate())
        Assertions.assertNull(field.initialNamespace)
    }
}
