package com.ibrahimdans.i18n.extract

import com.ibrahimdans.i18n.index.KeyCall
import com.ibrahimdans.i18n.index.TreeSitterKeyUsageIndex
import com.ibrahimdans.i18n.plugin.KeyUsage
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `t(SAVE_KEY)`, `t(KEYS.save)`, `t(Keys.Save)` read as the plugin's `ConstantKeyResolver` reads them: one hop. */
class ConstantKeysTest {

    private val hook = "const { t } = useTranslation('common');\n"

    /** An index over [files], project-relative path → code, each import between them followed. */
    private fun indexOf(vararg files: Pair<String, String>): TreeSitterKeyUsageIndex =
        TreeSitterKeyUsageIndex(imports = ConstantKeys.ProjectFiles(files.toMap())).apply { files.forEach { (path, text) -> update(path, text) } }

    /** The usage of the [occurrence]-th [fragment] of [text], in [path]. */
    private fun at(path: String, text: String, fragment: String, occurrence: Int = 0): KeyUsage {
        val start = generateSequence(text.indexOf(fragment)) { text.indexOf(fragment, it + 1).takeIf { next -> next >= 0 } }.elementAt(occurrence)
        return KeyUsage(path, TextSpan(start, start + fragment.length))
    }

    @Test
    fun `a local constant is read at the call`() {
        val text = "const SAVE = 'actions.save';\n${hook}t(SAVE);\n"
        assertEquals(listOf(at("App.tsx", text, "SAVE", 1)), indexOf("App.tsx" to text).usages("common:actions.save"))
    }

    @Test
    fun `a property of a nested object as const is read, the call's hook giving its namespace`() {
        val text = "const KEYS = { actions: { save: 'actions.save' } } as const;\n${hook}t(KEYS.actions.save);\n"
        assertEquals(listOf(at("App.tsx", text, "KEYS.actions.save")), indexOf("App.tsx" to text).usages("common:actions.save"))
    }

    @Test
    fun `a string enum member and a template without expression are read`() {
        val text = "enum Keys { Save = 'actions.save' }\nconst TITLE = `page.title`;\n${hook}t(Keys.Save);\nt(TITLE);\n"
        val index = indexOf("App.tsx" to text)
        assertEquals(listOf(at("App.tsx", text, "Keys.Save")), index.usages("common:actions.save"))
        assertEquals(listOf(at("App.tsx", text, "TITLE", 1)), index.usages("common:page.title"))
    }

    @Test
    fun `each branch of a conditional constant counts`() {
        val text = "const key = failed ? 'errors.failed' : 'errors.unknown';\n${hook}t(key);\n"
        val index = indexOf("App.tsx" to text)
        assertEquals(listOf(at("App.tsx", text, "key", 1)), index.usages("common:errors.failed"))
        assertEquals(listOf(at("App.tsx", text, "key", 1)), index.usages("common:errors.unknown"))
    }

    @Test
    fun `a constant imported by a relative path is read, from a file or a folder's index`() {
        val keys = "export const KEYS = { save: 'actions.save' };\n"
        val app = "import { KEYS } from './keys';\nimport { Titles as T } from '../i18n';\n${hook}t(KEYS.save);\nt(T.Home);\n"
        val index = indexOf("src/keys.ts" to keys, "i18n/index.ts" to "export enum Titles { Home = 'page.home' }\n", "src/App.tsx" to app)
        assertEquals(listOf(at("src/App.tsx", app, "KEYS.save")), index.usages("common:actions.save"))
        assertEquals(listOf(at("src/App.tsx", app, "T.Home")), index.usages("common:page.home"))
    }

    @Test
    fun `an exported name declared apart is read, a re-export is not`() {
        val keys = "const SAVE = 'actions.save';\nexport { SAVE };\nexport { HOME } from './other';\n"
        val other = "export const HOME = 'page.home';\n"
        val app = "import { SAVE, HOME } from './keys';\n${hook}t(SAVE);\nt(HOME);\n"
        val index = indexOf("keys.ts" to keys, "other.ts" to other, "App.tsx" to app)
        assertEquals(listOf(at("App.tsx", app, "SAVE", 1)), index.usages("common:actions.save"))
        assertEquals(emptyList<KeyUsage>(), index.usages("common:page.home"))
    }

    @Test
    fun `a path alias, a reassigned variable, a parameter and a computed value are not read`() {
        val text = """
            import { KEYS } from '@/keys';
            let changing = 'a.changing';
            changing = 'a.other';
            const SHADOWED = 'a.shadowed';
            const COMPUTED = prefix + 'a.computed';
            const { t } = useTranslation('common');
            t(KEYS.save);
            t(changing);
            function title(SHADOWED: string) { return t(SHADOWED); }
            t(COMPUTED);
        """.trimIndent()
        val index = indexOf("App.tsx" to text, "keys.ts" to "export const KEYS = { save: 'actions.save' };")
        for (key in listOf("common:actions.save", "common:a.changing", "common:a.other", "common:a.shadowed", "common:a.computed")) {
            assertEquals(emptyList<KeyUsage>(), index.usages(key), key)
        }
    }

    @Test
    fun `a let never reassigned is read, an exported one is not`() {
        val keys = "export let HOME = 'page.home';\n"
        val app = "import { HOME } from './keys';\nlet SAVE = 'actions.save';\n${hook}t(SAVE);\nt(HOME);\n"
        val index = indexOf("keys.ts" to keys, "App.tsx" to app)
        assertEquals(listOf(at("App.tsx", app, "SAVE", 1)), index.usages("common:actions.save"))
        assertEquals(emptyList<KeyUsage>(), index.usages("common:page.home"))
    }

    @Test
    fun `a missing key passed through a constant is reported at the call`() {
        val text = "const KEYS = { gone: 'actions.gone' } as const;\n${hook}t(KEYS.gone);\n"
        assertEquals(
            listOf(KeyCall("actions.gone", at("App.tsx", text, "KEYS.gone"))),
            indexOf("App.tsx" to text).unknownUsages(listOf("common:actions.save")),
        )
    }

    @Test
    fun `a hook's namespace stays as written, and a constant outside a translation call is no key`() {
        val text = "const NS = 'admin';\nconst SAVE = 'actions.save';\nconst { t } = useTranslation(NS);\nconsole.log(SAVE);\nt('title');\n"
        val index = indexOf("App.tsx" to text)
        assertEquals(emptyList<KeyUsage>(), index.usages("actions.save"))
        // Read as `admin`, the hook would keep `title` out of every other namespace.
        assertTrue(index.usages("other:title").isNotEmpty())
    }

    @Test
    fun `the frameworks read by rules follow constants too`() {
        val text = "import { _ } from 'svelte-i18n';\nconst HOME = 'nav.home';\n\$_(HOME);\n"
        val index = TreeSitterKeyUsageIndex(extractors = CodeExtractors.forPreset(CodeExtractors.SVELTE_I18N)).apply { update("App.svelte", text) }
        assertEquals(listOf(at("App.svelte", text, "HOME", 1)), index.usages("nav.home"))
    }
}
