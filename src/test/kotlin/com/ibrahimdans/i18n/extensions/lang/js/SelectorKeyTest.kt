package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.inspection.UnusedTranslationKeyInspection
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.unQuote
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * i18next's selector API, `t($ => $.button.save)`: the key is the path the arrow reads. Before, no
 * extractor read it — no annotation, no navigation, and its keys were reported unused.
 */
class SelectorKeyTest : PlatformBaseTest() {

    private fun translations() {
        addFileToProject(
            "locales/en/common.json",
            """{"button": {"save": "Save", "not-found": "Missing"}, "item_one": "{{count}} item", "item_other": "{{count}} items", "unused": "Nobody"}""",
        )
        addFileToProject("locales/en/auth.json", """{"login": "Log in"}""")
    }

    /** The text of each range reported as an unresolved key in the open file, in order. */
    private fun unresolved(): List<String> {
        val text = myFixture.file.text
        return myFixture.doHighlighting()
            .filter { it.description == "Unresolved key" }
            .sortedBy { it.startOffset }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    private fun component(body: String) = myFixture.configureByText(
        "Form.tsx",
        "import { useTranslation } from 'react-i18next';\nexport function Form({ count }) {\n$body\n}",
    )

    @Test
    fun `a selector is read under its hook's namespace`() {
        translations()
        component(
            """
            const { t } = useTranslation('common');
            return [t($ => $.button.save), t($ => $.button.absent), t($ => $.button['not-found'])];
            """.trimIndent()
        )
        assertEquals(listOf("absent"), unresolved())
    }

    @Test
    fun `the hook's key prefix and an explicit namespace apply`() {
        translations()
        component(
            """
            const { t } = useTranslation('common', { keyPrefix: 'button' });
            const { t: tAuth } = useTranslation();
            return [t($ => $.save), t($ => $.gone), tAuth($ => $.login, { ns: 'auth' })];
            """.trimIndent()
        )
        assertEquals(listOf("gone"), unresolved())
    }

    /** Under several namespaces, a first segment naming another one routes the rest of the path there; the first is never routed to. */
    @Test
    fun `a first segment naming a secondary namespace routes the key`() {
        translations()
        component(
            """
            const { t } = useTranslation(['common', 'auth']);
            return [t($ => $.auth.login), t($ => $.button.save), t($ => $.common.button.save)];
            """.trimIndent()
        )
        assertEquals(listOf("common.button.save"), unresolved())
    }

    @Test
    fun `a plural key is resolved through its forms`() {
        translations()
        component(
            """
            const { t } = useTranslation('common');
            return t($ => $.item, { count });
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), unresolved())
    }

    /** Redux's `useSelector`, a computed body, a function of two parameters: no key. */
    @Test
    fun `an arrow that is no selector of a translation call is left alone`() {
        translations()
        component(
            """
            const { t } = useTranslation('common');
            const name = useSelector(state => state.user.name);
            return [name, t($ => count ? $.button.save : $.button.absent), t(($, x) => $.button.absent), t($ => other.button.absent)];
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), unresolved())
    }

    /** A key the code reaches by a selector only is used: *Unused translation key* leaves it alone. */
    @Test
    fun `a key read by a selector is not reported unused`() {
        translations()
        addFileToProject(
            "src/Form.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export function Form({ count }) {
              const { t } = useTranslation('common');
              return [t($ => $.button.save), t($ => $.button['not-found']), t($ => $.item, { count })];
            }
            """.trimIndent()
        )
        myFixture.enableInspections(UnusedTranslationKeyInspection::class.java)
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("locales/en/common.json"))
        val text = myFixture.editor.document.text
        val unused = myFixture.doHighlighting().filter { it.description == "No reference to this key found in code" }
            .map { text.substring(it.startOffset, it.endOffset).trim('"') }
        assertEquals(listOf("unused"), unused)
    }

    @Test
    fun `ctrl+click on a selector opens its translation`() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        translations()
        component(
            """
            const { t } = useTranslation('common');
            return [t($ => $.button.sa<caret>ve), t($ => $.button.absent)];
            """.trimIndent()
        )
        val targets = ReadAction.compute<List<String>?, RuntimeException> {
            val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)
            SelectorGotoDeclarationHandler().getGotoDeclarationTargets(leaf, myFixture.caretOffset, myFixture.editor)
                ?.map { "${it.containingFile.name}: ${it.text.unQuote()}" }
        }
        assertEquals(listOf("common.json: Save"), targets)
    }

    /** A key no file holds, not even its first level: null, so TypeScript's own navigation applies. */
    @Test
    fun `ctrl+click on an unknown selector key leaves the TypeScript navigation`() = myFixture.runWithConfig(Config(previewLocale = "en")) {
        translations()
        component(
            """
            const { t } = useTranslation('common');
            return t($ => $.nowhere.abs<caret>ent);
            """.trimIndent()
        )
        val targets = ReadAction.compute<Array<*>?, RuntimeException> {
            val leaf = myFixture.file.findElementAt(myFixture.caretOffset - 1)
            SelectorGotoDeclarationHandler().getGotoDeclarationTargets(leaf, myFixture.caretOffset, myFixture.editor)
        }
        assertNull(targets)
    }

    @Test
    fun `the handler is registered`() {
        assertTrue(GotoDeclarationHandler.EP_NAME.extensionList.any { it is SelectorGotoDeclarationHandler })
    }
}
