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
        keys: Map<String, Set<String>> = mapOf("account" to setOf("title")),
        text: String = "Save changes",
    ) =
        ExtractKeyModel(
            text = text,
            existingKeys = emptyList(),
            namespaces = namespaces,
            sourcesByNamespace = emptyMap(),
            keysByNamespace = keys,
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
        assertEquals("save_changes", model().proposedName("common"))
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

    private val trustees = mapOf(
        "account" to setOf("myAccount.myTrustees.title", "myAccount.myTrustees.noTrustee.title", "myAccount.help")
    )

    @Test
    fun theParentsAreTheGroupsOfTheNamespace() {
        assertEquals(
            listOf("myAccount", "myAccount.myTrustees", "myAccount.myTrustees.noTrustee"),
            model(keys = trustees).parents("account")
        )
        assertEquals(emptyList<String>(), model(keys = trustees).parents("common"))
        assertEquals(emptyList<String>(), model(Config(defaultNs = "common", flatKeys = true), keys = trustees).parents("account"))
    }

    @Test
    fun theNameGoesUnderTheParent() {
        assertEquals("myAccount.myTrustees.add", model().join("myAccount.myTrustees", "add"))
        assertEquals("myAccount.add", model().join("myAccount.", " add "))
        assertEquals("add", model().join("", "add"))
    }

    @Test
    fun aKeyCannotGoUnderATranslationNorReplaceAGroup() {
        val model = model(keys = trustees)
        assertEquals(ExtractKeyModel.Conflict.LeafAsParent("myAccount.help"), model.conflict("account", "myAccount.help.more"))
        assertEquals(ExtractKeyModel.Conflict.GroupAsLeaf("myAccount.myTrustees"), model.conflict("account", "myAccount.myTrustees"))
        assertEquals(null, model.conflict("account", "myAccount.myTrustees.add"))
        assertEquals(null, model.conflict("common", "myAccount.help.more"))
    }

    /** cbox-front names its keys `noTrustee`, `removeTrustee`: the proposal follows. */
    @Test
    fun theProposedNameFollowsTheStyleOfTheProject() {
        assertEquals("testExtract", model(keys = trustees, text = "test extract").proposedName("account"))
        // A namespace whose keys tell nothing follows the project.
        assertEquals("testExtract", model(keys = trustees + ("common" to setOf("title")), text = "test extract").proposedName("common"))
        // Plural suffixes are not a style.
        assertEquals(ExtractKeyModel.KeyStyle.CAMEL, ExtractKeyModel.keyStyle(listOf("fileCount_one", "fileCount_other", "noTrustee")))
        assertEquals(ExtractKeyModel.KeyStyle.SNAKE, ExtractKeyModel.keyStyle(listOf("a.no_trustee", "b.title")))
        assertEquals(ExtractKeyModel.KeyStyle.KEBAB, ExtractKeyModel.keyStyle(listOf("no-trustee")))
        assertEquals("pleaseConfirmTheAdditionOf", ExtractKeyModel.proposeName("Please confirm the addition of a trustee", ExtractKeyModel.KeyStyle.CAMEL))
    }
}
