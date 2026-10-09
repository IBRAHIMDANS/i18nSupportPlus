package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.actions.ContextKeys.Placed
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContextKeysTest : PlatformBaseTest() {

    private fun placed(key: String): Placed =
        Placed(key.substringBefore(':'), key.substringAfter(':').split('.'))

    /** MyTrusteesTab.tsx: the new key goes next to the others, in `account`, under `myAccount.myTrustees`. */
    @Test
    fun theDeepestGroupHoldingMostKeys() {
        val keys = listOf(
            "account:myAccount.myTrustees.description",
            "account:myAccount.myTrustees.help",
            "account:myAccount.myTrustees.noTrustee.title",
            "account:myAccount.myTrustees.modal.removeTrustee.title",
            "common:button.cancel",
        ).map(::placed)
        assertEquals("account" to listOf("myAccount", "myTrustees"), ContextKeys.placement(keys, listOf("account", "common")))
    }

    /** One stray key does not cut the guess short, as a common prefix would. */
    @Test
    fun aStrayKeyDoesNotCutTheGroupShort() {
        val keys = listOf("a:x.y.one", "a:x.y.two", "a:x.y.three", "a:z.four").map(::placed)
        assertEquals("a" to listOf("x", "y"), ContextKeys.placement(keys, listOf("a")))
    }

    @Test
    fun noKeyInAnOfferedNamespaceTellsNothing() {
        assertEquals(null, ContextKeys.placement(listOf(placed("other:x.y")), listOf("account")))
        assertEquals(null, ContextKeys.placement(emptyList(), listOf("account")))
    }

    /** The keys of a component are read the way the annotator reads them. */
    @Test
    fun readsTheKeysOfTheFile() {
        val file = myFixture.configureByText(
            "MyTrusteesTab.tsx",
            """
                export const MyTrusteesTab = () => {
                    const { t } = useTranslation();
                    return <div><p>{t('account:myAccount.myTrustees.help')}</p><p>{t('common:button.cancel')}</p></div>;
                };
            """.trimIndent()
        )
        val keys = ReadAction.compute<List<Placed>, RuntimeException> { ContextKeys.around(file, "translation") }
        assertEquals(
            setOf(Placed("account", listOf("myAccount", "myTrustees", "help")), Placed("common", listOf("button", "cancel"))),
            keys.toSet()
        )
    }
}
