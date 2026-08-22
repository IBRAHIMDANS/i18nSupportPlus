package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.extensions.lang.js.JsFoldingBuilder
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.hint.HintProvider
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.intellij.openapi.application.ReadAction
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * What a nested plural key *shows*, once it resolves.
 *
 * #162 made `t('common.box')` resolve onto `{ one: …, other: … }` instead of reporting a false
 * "Reference to object". Folding, hints and inlay hints all required a leaf, so the key was
 * annotated as resolved while displaying nothing at all — the same gap `IcuMessageRenderer`
 * closed for react-intl, whose plurals live inside the value.
 */
class NestedPluralDisplayTest : PlatformBaseTest() {

    private val catalog = """
        export const translations = {
          en: {
            common: { box: { one: '1 box', other: '%{count} boxes' } },
            noOther: { few: 'a few', many: 'many' },
            plain: { title: 'Title', subtitle: 'Subtitle' },
          },
        } as const;
    """.trimIndent()

    private val config = Config(foldingPreferredLanguage = "en", foldingMaxLength = 40, foldingEnabled = true)

    private fun foldedTextOf(key: String): String? {
        addFileToProject("src/i18n/translations.ts", catalog)
        val file = myFixture.configureByText("test.ts", "function test() { return i18n.t(\"$key\"); }")
        return ReadAction.compute<String?, RuntimeException> {
            JsFoldingBuilder()
                .buildFoldRegions(file, myFixture.editor.document, false)
                .firstOrNull()
                ?.placeholderText
        }
    }

    /** The branch that stands for the group, matching what ICU rendering picks. */
    @Test
    fun foldingShowsTheOtherBranch() = myFixture.runWithConfig(config) {
        Assertions.assertEquals("%{count} boxes", foldedTextOf("common.box"))
    }

    @Test
    fun foldingFallsBackToTheFirstCategoryDeclared() = myFixture.runWithConfig(config) {
        Assertions.assertEquals("a few", foldedTextOf("noOther"))
    }

    /** An ordinary object holds no value to show, and must stay unfolded as before. */
    @Test
    fun foldingIgnoresAnOrdinaryObject() = myFixture.runWithConfig(config) {
        Assertions.assertNull(foldedTextOf("plain"))
    }

    @Test
    fun foldingStillShowsAPlainLeaf() = myFixture.runWithConfig(config) {
        Assertions.assertEquals("Title", foldedTextOf("plain.title"))
    }

    @Test
    fun hintShowsTheOtherBranch() = myFixture.runWithConfig(config) {
        addFileToProject("src/i18n/translations.ts", catalog)
        myFixture.configureByText("hint.ts", "function test() { return i18n.t(\"common.<caret>box\"); }")

        read {
            val element = myFixture.file.findElementAt(myFixture.caretOffset)
            val hint = HintProvider().generateDoc(null, element)

            Assertions.assertNotNull(hint, "a resolved plural key must produce a hint")
            Assertions.assertTrue(
                hint!!.contains("%{count} boxes"),
                "the hint must show the representative branch, not the object: $hint"
            )
        }
    }
}
