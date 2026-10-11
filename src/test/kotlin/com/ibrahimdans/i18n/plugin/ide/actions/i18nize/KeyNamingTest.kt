package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Assertions are qualified: `BasePlatformTestCase` inherits JUnit 3's reversed `assertEquals(message, …)`. */
class KeyNamingTest : PlatformBaseTest() {

    private fun naming(existing: Map<String, String> = emptyMap()) =
        KeyNaming({ namespace, path -> "$namespace:" + path.joinToString(".") }, existing)

    @Test
    fun aKeyIsTheNamespaceTheComponentAndTheSlug() {
        Assertions.assertEquals("common:userProfile.save_changes", naming().keyFor("common", "userProfile", "Save changes"))
    }

    @Test
    fun accentsAreDroppedFromTheSlug() {
        Assertions.assertEquals("common:signup.creer_un_compte", naming().keyFor("common", "signup", "Créer un compte !"))
    }

    @Test
    fun aTextWithNothingToNameItByGetsAPlaceholderSlug() {
        Assertions.assertEquals("common:menu.text", naming().keyFor("common", "menu", "!!!"))
    }

    @Test
    fun theSameTextInTheSameComponentIsOneKey() {
        val naming = naming()
        Assertions.assertEquals(naming.keyFor("common", "menu", "Save"), naming.keyFor("common", "menu", " Save "))
    }

    @Test
    fun theSameTextInTwoComponentsIsTwoKeys() {
        val naming = naming()
        Assertions.assertNotEquals(naming.keyFor("common", "menu", "Save"), naming.keyFor("common", "footer", "Save"))
    }

    @Test
    fun twoTextsGivingTheSameSlugAreSuffixed() {
        val naming = naming()
        Assertions.assertEquals("common:menu.save", naming.keyFor("common", "menu", "Save"))
        Assertions.assertEquals("common:menu.save_2", naming.keyFor("common", "menu", "Save!"))
        Assertions.assertEquals("common:menu.save_3", naming.keyFor("common", "menu", "save"))
    }

    @Test
    fun anExistingKeyHoldingAnotherValueIsNeverReused() {
        val naming = naming(mapOf("common:menu.save" to "Save the file", "common:menu.save_2" to "Save"))
        Assertions.assertEquals("common:menu.save_2", naming.keyFor("common", "menu", "Save"), "the key holding the same text is the one")
        Assertions.assertEquals("common:menu.open", naming(mapOf("common:menu.save" to "x")).keyFor("common", "menu", "Open"))
        Assertions.assertEquals("common:menu.save_2", naming(mapOf("common:menu.save" to "x")).keyFor("common", "menu", "Save"))
    }

    @Test
    fun namesAreCamelCased() {
        Assertions.assertEquals("userProfile", KeyNaming.camelCase("UserProfile"))
        Assertions.assertEquals("userProfile", KeyNaming.camelCase("user-profile"))
        Assertions.assertEquals("userProfile", KeyNaming.camelCase("user_profile"))
        Assertions.assertEquals("ecranPrincipal", KeyNaming.camelCase("ÉcranPrincipal"))
        Assertions.assertEquals("component", KeyNaming.camelCase("---"))
    }

    private fun textOf(name: String, code: String, text: String): PsiElement {
        myFixture.configureByText(name, code.trimIndent())
        return ReadAction.compute<PsiElement, RuntimeException> {
            PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).first { it.text.trim() == text }
        }
    }

    @Test
    fun theComponentIsReadFromAFunction() {
        val text = textOf("Profile.tsx", "export function UserProfile() { return <p>Save</p>; }", "Save")
        Assertions.assertEquals("userProfile", ReadAction.compute<String, RuntimeException> { KeyNaming.componentOf(text) })
    }

    @Test
    fun theComponentIsReadFromAnArrowFunctionConstant() {
        val text = textOf("Profile.tsx", "export const UserCard = () => { const onClick = () => 1; return <p>Open</p>; };", "Open")
        Assertions.assertEquals("userCard", ReadAction.compute<String, RuntimeException> { KeyNaming.componentOf(text) })
    }

    @Test
    fun withoutAComponentTheFileNamesIt() {
        val text = textOf("order-summary.tsx", "export default function () { return <p>Total</p>; }", "Total")
        Assertions.assertEquals("orderSummary", ReadAction.compute<String, RuntimeException> { KeyNaming.componentOf(text) })
    }
}
