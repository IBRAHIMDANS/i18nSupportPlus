package com.ibrahimdans.i18n.plugin.format

import com.ibrahimdans.i18n.plugin.format.PhpArrayTranslationTree.Reading
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.TextSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhpArrayTranslationTreeTest {

    private fun parse(text: String): PhpArrayTranslationTree = PhpArrayTranslationTree.parse(text)!!

    private fun Tree<ParsedNode>.names(): List<String> = entries()!!.map { it.first }

    private fun String.at(span: TextSpan?): String = substring(span!!.start, span.end)

    @Test
    fun `the returned array - string keys, nested arrays, literal values`() {
        val text = """
            <?php
            declare(strict_types=1);
            namespace App\Lang;
            use Foo\Bar;

            /* The messages. */
            return [
                'welcome' => 'Welcome, :name!', // a comment
                "quote" => "It's \"fine\"\n",
                'escaped' => 'C\'est \\ ici',
                'auth' => array('failed' => 'Failed', 'count' => 3,),
                0 => 'an integer key',
                'no key',
                'list' => ['a', 'b'],
            ];
        """.trimIndent()
        val tree = parse(text)
        assertEquals(listOf("welcome", "quote", "escaped", "auth", "list"), tree.names())
        assertEquals("Welcome, :name!", tree.findChild("welcome")!!.value().value)
        assertEquals("It's \"fine\"\n", tree.findChild("quote")!!.value().value)
        assertEquals("C'est \\ ici", tree.findChild("escaped")!!.value().value)
        assertEquals(listOf("failed", "count"), tree.findChild("auth")!!.names())
        assertEquals("3", tree.findChild("auth")!!.findChild("count")!!.value().value)
        // A list has no string key: no translation in it.
        assertEquals(emptyList<String>(), tree.findChild("list")!!.names())
        assertEquals("'welcome'", text.at(tree.findChild("welcome")!!.value().keySpan))
    }

    @Test
    fun `a value built by code - partial, with the construct and its line`() {
        fun partial(value: String) = PhpArrayTranslationTree.read("<?php\nreturn [\n    'a' => $value,\n];")
        assertEquals(Reading.Partial("concatenation", 3), partial("'Hi ' . 'there'"))
        assertEquals(Reading.Partial("call", 3), partial("trans('x')"))
        assertEquals(Reading.Partial("constant", 3), partial("Foo::BAR"))
        assertEquals(Reading.Partial("variable", 3), partial("\$name"))
        assertEquals(Reading.Partial("interpolated string", 3), partial("\"Hi \$name\""))
        assertEquals(Reading.Partial("spread", 3), PhpArrayTranslationTree.read("<?php\nreturn [\n    ...\$common,\n];"))
        assertEquals(Reading.Partial("call", 2), PhpArrayTranslationTree.read("<?php\nreturn array_merge([], []);"))
    }

    @Test
    fun `a file of code - no array`() {
        assertEquals(Reading.NoArray, PhpArrayTranslationTree.read("<?php\nclass User extends Model {}"))
        assertEquals(Reading.NoArray, PhpArrayTranslationTree.read("<?php\n\$x = 1;\nreturn [];"))
        assertTrue(PhpArrayTranslationTree.read("<?php return [];") is Reading.Catalogue)
    }
}
