package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.dialog.KeyCheck
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The rules of the extraction dialog, read off a snapshot without any dialog or project. */
class ExtractKeyModelTest {

    private fun model(config: Config = Config(defaultNs = "common"), namespaces: List<String> = listOf("account", "common")) =
        ExtractKeyModel(
            text = "Save changes",
            existingKeys = emptyList(),
            namespaces = namespaces,
            sourcesByNamespace = emptyMap(),
            keysByNamespace = mapOf("account" to setOf("title")),
            referenceLocale = "en",
            config = config,
            template = { "{i18n.t($it)}" },
        )

    @Test
    fun theCodeGetsTheNamespaceOfTheFilesWritten() {
        assertEquals("account:save", model().codeKey("account", "save"))
        assertEquals("{i18n.t('account:save')}", model().preview("account", "save"))
    }

    @Test
    fun aDefaultNamespaceIsLeftOut() {
        assertEquals("save", model().codeKey("common", "save"))
        assertEquals("", model().prefix("common"))
        assertEquals("account:", model().prefix("account"))
    }

    @Test
    fun aFirstComponentNamespaceIsJoinedWithTheKeySeparator() {
        val model = model(Config(defaultNs = "common", firstComponentNs = true))
        assertEquals("account.save", model.codeKey("account", "save"))
        assertEquals("account.", model.prefix("account"))
    }

    @Test
    fun flatKeysCarryNoNamespace() {
        val model = model(Config(defaultNs = "common", flatKeys = true))
        assertEquals("app.save", model.codeKey("account", "app.save"))
        assertEquals(listOf("app.save"), model.fullKey("account", "app.save").compositeKey.map { it.text })
    }

    @Test
    fun theDefaultNamespaceIsSelectedFirst() {
        assertEquals("common", model().initialNamespace)
        assertEquals("account", model(namespaces = listOf("account", "auth")).initialNamespace)
    }

    @Test
    fun theKeyIsCheckedInTheSelectedNamespace() {
        assertEquals(KeyCheck.TAKEN, model().checkKey("account", "title"))
        assertEquals(KeyCheck.AVAILABLE, model().checkKey("common", "title"))
        assertEquals(KeyCheck.INVALID_SEGMENT, model().checkKey("account", "a..b"))
        assertEquals(KeyCheck.EMPTY, model().checkKey("account", " "))
    }

    @Test
    fun theProposedKeyIsTheTextInLowerSnakeCase() {
        assertEquals("save_changes", model().proposedKey)
        assertEquals("paap_le_roi", ExtractKeyModel.proposeKey(" Paap le roi! "))
        assertEquals("creer_un_compte", ExtractKeyModel.proposeKey("Créer un compte"))
    }
}
