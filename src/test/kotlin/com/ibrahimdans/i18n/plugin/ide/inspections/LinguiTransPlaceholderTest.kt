package com.ibrahimdans.i18n.plugin.ide.inspections

import com.ibrahimdans.i18n.extensions.lang.js.extractors.LinguiTransKeyExtractor
import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The msgid [LinguiTransKeyExtractor] builds from a `<Trans>` tag, which must be the message
 * Lingui's macro writes into the catalogue: a simple identifier keeps its name (`{name}`), any
 * other expression is numbered (`{0}`), and an element becomes an indexed tag (`<0>…</0>`).
 */
class LinguiTransPlaceholderTest : PlatformBaseTest() {

    private fun msgidOf(transTag: String): String {
        myFixture.configureByText("test.tsx", "const C = () => ($transTag);")
        val tag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.name == "Trans" }
        val extractor = LinguiTransKeyExtractor()
        check(extractor.canExtract(tag as PsiElement)) { "the tag must be extractable" }
        return extractor.extract(tag).keyElements.joinToString("") { it.text }
    }

    @Test
    fun plainTextKeepsItsMsgid() {
        assertEquals("Hello world!", msgidOf("<Trans>Hello world!</Trans>"))
    }

    @Test
    fun aSimpleIdentifierKeepsItsName() {
        // Lingui's documented example: `<Trans>Attachment {name} saved</Trans>` → "Attachment {name} saved".
        assertEquals("Hello {name}!", msgidOf("<Trans>Hello {name}!</Trans>"))
    }

    @Test
    fun aMemberExpressionIsNumbered() {
        assertEquals("Hello {0}!", msgidOf("<Trans>Hello {user.name}!</Trans>"))
    }

    @Test
    fun onlyTheExpressionsThatAreNotIdentifiersConsumeAnIndex() {
        assertEquals(
            "{payer} owes {0} to {payee}, due {1}",
            msgidOf("<Trans>{payer} owes {amount.value} to {payee}, due {formatDate(due)}</Trans>"),
        )
    }

    @Test
    fun aNestedBraceIsConsumedWholeAsOnePlaceholder() {
        assertEquals("Total: {0}, {1}", msgidOf("""<Trans>Total: {cart.items[0]}, {open ? "{" : ""}</Trans>"""))
    }

    @Test
    fun elementsBecomeIndexedTagsInTheOrderTheyOpen() {
        assertEquals(
            "Read the <0>docs</0>, then <1>{0} <2>now</2></1><3/>",
            msgidOf("""<Trans>Read the <a href="/docs">docs</a>, then <strong>{user.name} <em>now</em></strong><br/></Trans>"""),
        )
    }

    @Test
    fun aLabelledExpressionTakesItsLabel() {
        assertEquals(
            "Welcome back, {username}! {count}",
            msgidOf("<Trans>Welcome back, {ph({ username: getUser().name })}! {{ count: items.length }}</Trans>"),
        )
    }

    @Test
    fun typeScriptWrappersAreLookedThrough() {
        assertEquals("{name} {total}", msgidOf("<Trans>{name!} {(total as number)}</Trans>"))
    }

    @Test
    fun aStringLiteralIsTextAndACommentIsDropped() {
        assertEquals("Hi {name}", msgidOf("""<Trans>Hi{" "}{/* the user */}{name}</Trans>"""))
    }

    @Test
    fun linesAreTrimmedAndJoinedByOneSpace() {
        val tag = """
            <Trans>
              Hello {name},
              welcome   back!
            </Trans>
        """.trimIndent()
        assertEquals("Hello {name}, welcome   back!", msgidOf(tag))
    }
}
