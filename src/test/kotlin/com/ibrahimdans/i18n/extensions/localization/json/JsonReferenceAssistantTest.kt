package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.references.translation.TranslationToCodeReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Ctrl+click on a key of a translation file leads to the calls naming it. A file named after its
 * locale (`messages/fr.json`: next-intl, Paraglide, vue-i18n) holds no namespace: its name used to
 * be read as one, so the key looked for was `fr:hello`, which no call writes, and no reference was
 * given at all.
 */
class JsonReferenceAssistantTest : PlatformBaseTest() {

    /** The reference on the property name [key] of the translation file at [path]. */
    private fun referenceOn(path: String, key: String): TranslationToCodeReference? {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
        val offset = myFixture.editor.document.text.indexOf("\"$key\"") + 1
        val name = myFixture.file.findElementAt(offset)?.parent
        assertNotNull(name, "no property name '$key' in $path")
        return name!!.references.filterIsInstance<TranslationToCodeReference>().firstOrNull()
    }

    private fun TranslationToCodeReference.callSites(): Set<String?> =
        multiResolve(false).map { it.element?.containingFile?.name }.toSet()

    @Test
    fun aLocaleFileLeadsToTheCallsOfItsKeys() {
        addFileToProject("messages/fr.json", """{"hello": "Bonjour"}""")
        addFileToProject("src/Page.js", "t('hello');")
        val reference = referenceOn("messages/fr.json", "hello")
        assertNotNull(reference, "no reference on 'hello' of messages/fr.json")
        assertEquals("hello", reference!!.composedKey)
        assertEquals(setOf("Page.js"), reference.callSites())
    }

    /** `m.hello` is a call; Paraglide's generated `hello.js`, which names every message, is not. */
    @Test
    fun aParaglideMessageFileLeadsToTheMessageCalls() {
        addFileToProject("messages/fr.json", """{"hello": "Bonjour"}""")
        addFileToProject("src/paraglide/runtime.js", "export const getLocale = () => 'en';")
        addFileToProject("src/paraglide/messages.js", "export * from './messages/_index.js'\nexport * as m from './messages/_index.js'")
        addFileToProject("src/paraglide/messages/_index.js", "export * from './hello.js'")
        addFileToProject("src/paraglide/messages/hello.js", "export const hello = () => 'Hello';")
        addFileToProject(
            "src/routes/Page.svelte",
            "<script>\n  import { m } from '\$lib/paraglide/messages.js';\n</script>\n<p>{m.hello()}</p>"
        )
        val reference = referenceOn("messages/fr.json", "hello")
        assertNotNull(reference, "no reference on 'hello' of messages/fr.json")
        assertEquals(setOf("Page.svelte"), reference!!.callSites())
    }

    /** A file named after its namespace keeps it: `locales/en/common.json` holds `common:title`. */
    @Test
    fun aNamespaceFileKeepsItsNamespace() {
        addFileToProject("locales/en/common.json", """{"title": "Title"}""")
        addFileToProject("src/App.js", "t('common:title');")
        val reference = referenceOn("locales/en/common.json", "title")
        assertNotNull(reference, "no reference on 'title' of locales/en/common.json")
        assertEquals("common:title", reference!!.composedKey)
        assertEquals(setOf("App.js"), reference.callSites())
    }
}
