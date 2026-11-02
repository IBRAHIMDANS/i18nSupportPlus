package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class PropertiesTranslationTreeTest {

    private fun values(text: String): List<Pair<String, String?>> =
        PropertiesTranslationTree.parse(text).entries().map { (key, node) -> key to node.value().value }

    @Test
    fun `keys in file order, comments and blank lines skipped, every separator read`() {
        val text = """
            # Greetings
            ! another comment
            user.name = Name

            user.email:Email
            title Welcome home
            empty=
        """.trimIndent()
        Assertions.assertEquals(
            listOf("user.name" to "Name", "user.email" to "Email", "title" to "Welcome home", "empty" to ""),
            values(text),
        )
    }

    @Test
    fun `a line ending with a backslash continues, the next line's indent dropped`() {
        val text = "long = first \\\n    second\\\n  third\nnext=x"
        Assertions.assertEquals(listOf("long" to "first secondthird", "next" to "x"), values(text))
    }

    @Test
    fun `an even number of trailing backslashes is an escaped backslash, not a continuation`() {
        Assertions.assertEquals(listOf("path" to "C:\\", "next" to "x"), values("path=C:\\\\\nnext=x"))
    }

    @Test
    fun `escapes are decoded in the key and in the value`() {
        val text = "caf\\u00e9=Caf\\u00e9\\tbar\nwith\\ space\\=sign = a\\:b\\nc"
        Assertions.assertEquals(listOf("café" to "Café\tbar", "with space=sign" to "a:b\nc"), values(text))
    }

    @Test
    fun `the first of a duplicated key wins`() {
        Assertions.assertEquals(listOf("a" to "1"), values("a=1\na=2"))
    }

    @Test
    fun `the spans locate the key and the value in the source`() {
        val text = "# c\nuser.name = Name\r\n"
        val node = PropertiesTranslationTree.parse(text).findChild("user.name")!!.value()
        Assertions.assertEquals(TextSpan(4, 13), node.keySpan)
        Assertions.assertEquals(TextSpan(16, 20), node.valueSpan)
    }
}
