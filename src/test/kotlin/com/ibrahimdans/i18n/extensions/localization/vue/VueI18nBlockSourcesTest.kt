package com.ibrahimdans.i18n.extensions.localization.vue

import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VueI18nBlockSourcesTest : PlatformBaseTest() {

    /** The element of the key literal [key] in the template of the open component. */
    private fun keyIn(name: String, content: String, key: String): PsiElement {
        val file = myFixture.configureByText(name, content)
        val offset = content.indexOf("'$key'") + 1
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, offset)
            ?: file.findElementAt(offset)!!
    }

    private fun sourcesFor(caller: PsiElement): List<LocalizationSource> =
        ReadAction.compute<List<LocalizationSource>, RuntimeException> {
            project.service<LocalizationSourceService>().findSources(emptyList(), caller)
        }

    private val component = """
        <i18n>{"en": {"hello": "Hi"}, "fr": {"hello": "Salut"}}</i18n>
        <template><p>{{ ${'$'}t('hello') }}</p></template>
    """.trimIndent()

    @Test
    fun theBlockGivesOneSourcePerLocaleToItsComponent() {
        val sources = sourcesFor(keyIn("Greeting.vue", component, "hello")).filter { it.displayPath.contains("#i18n") }
        assertEquals(listOf("en", "fr"), sources.map { it.locale })
        val en = sources.first()
        assertNotNull(ReadAction.compute<Any?, RuntimeException> { en.tree?.findChild("hello") })
    }

    @Test
    fun aLangJsonBlockIsReadToo() {
        val content = component.replace("<i18n>", "<i18n lang=\"json\">")
        val sources = sourcesFor(keyIn("Greeting.vue", content, "hello")).filter { it.displayPath.contains("#i18n") }
        assertEquals(listOf("en", "fr"), sources.map { it.locale })
    }

    /** The component's own messages come before the project's files, as vue-i18n resolves them. */
    @Test
    fun theBlockComesBeforeTheProjectFiles() {
        addFileToProject("locales/en.json", """{"hello": "Hello from the project"}""")
        val sources = sourcesFor(keyIn("Greeting.vue", component, "hello"))
        assertTrue(sources.first().displayPath.contains("#i18n"), sources.map { it.displayPath }.toString())
        assertTrue(sources.any { it.displayPath.endsWith("en.json") }, "the project files are still offered")
    }

    @Test
    fun anotherComponentDoesNotSeeTheBlock() {
        myFixture.configureByText("Greeting.vue", component)
        val other = keyIn("Other.vue", "<template><p>{{ ${'$'}t('hello') }}</p></template>", "hello")
        assertTrue(sourcesFor(other).none { it.displayPath.contains("#i18n") })
    }
}
