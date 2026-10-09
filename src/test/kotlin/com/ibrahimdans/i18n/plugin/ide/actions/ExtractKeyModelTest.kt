package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.dialog.KeyCheck
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The rules of the extraction dialog, read off a snapshot without any dialog or project. */
class ExtractKeyModelTest {

    private fun model(
        config: Config = Config(defaultNs = "common"),
        namespaces: List<String> = listOf("account", "common"),
        scopeNamespaces: List<String> = emptyList(),
    ) =
        ExtractKeyModel(
            text = "Save changes",
            existingKeys = emptyList(),
            namespaces = namespaces,
            sourcesByNamespace = emptyMap(),
            keysByNamespace = mapOf("account" to setOf("title")),
            referenceLocale = "en",
            config = config,
            template = { "{t($it)}" },
            scopeNamespaces = scopeNamespaces,
        )

    @Test
    fun theCodeGetsTheNamespaceOfTheFilesWritten() {
        assertEquals("account:save", model().codeKey("account", "save"))
        assertEquals("{t('account:save')}", model().preview("account", "save"))
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

    /** Under `useTranslation('account')`, `t('save')` reads `account`: only that namespace goes unwritten. */
    @Test
    fun theNamespaceOfTheHookInScopeIsLeftOutAndSelectedFirst() {
        val model = model(namespaces = listOf("auth", "account", "common"), scopeNamespaces = listOf("account"))
        assertEquals("account", model.initialNamespace)
        assertEquals("save", model.codeKey("account", "save"))
        assertEquals("", model.prefix("account"))
        assertEquals("common:save", model.codeKey("common", "save"))
        assertEquals("auth:save", model.codeKey("auth", "save"))
    }

    /** A reused key found without its default namespace gets it back under a hook reading another one. */
    @Test
    fun aReusedDefaultKeyIsQualifiedUnderAHook() {
        assertEquals("{t('common:save')}", model(scopeNamespaces = listOf("account")).reusePreview("save"))
        assertEquals("{t('save')}", model(scopeNamespaces = listOf("common")).reusePreview("save"))
        assertEquals("{t('save')}", model().reusePreview("save"))
    }
}
