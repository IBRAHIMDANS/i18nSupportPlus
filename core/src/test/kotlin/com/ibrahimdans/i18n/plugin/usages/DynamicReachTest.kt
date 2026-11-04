package com.ibrahimdans.i18n.plugin.usages

import com.ibrahimdans.i18n.plugin.usages.DynamicReach.Shape
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The one reading the IDE's *Usage* column and `i18n-check` share: the static head and tail of a
 * key literal built at runtime, and which translation keys they let it produce.
 */
class DynamicReachTest {

    private fun reaches(head: String, tail: String, key: String, nsSeparator: String = ":", keySeparator: String = "."): Boolean =
        Shape(head, tail).reaches(key, key.substringAfter(nsSeparator, key), nsSeparator, keySeparator)

    // ── The shape of a literal ────────────────────────────────────────────────

    @Test
    fun `the shape of a literal is its static head and tail`() {
        // Interpolation at the end, at the start, in the middle.
        Assertions.assertEquals(Shape("common:status.", ""), DynamicReach.shapeOf("common:status.\${kind}"))
        Assertions.assertEquals(Shape("", ":status.ok"), DynamicReach.shapeOf("\${ns}:status.ok"))
        Assertions.assertEquals(Shape("menu.", ".label"), DynamicReach.shapeOf("menu.\${id}.label"))
        // Several interpolations: the text between them is not static.
        Assertions.assertEquals(Shape("a.", ".c"), DynamicReach.shapeOf("a.\${x}.b.\${y}.c"))
        Assertions.assertEquals(Shape("a.", ""), DynamicReach.shapeOf("a.\${x}.\${y}"))
        // An interpolation left open has no tail.
        Assertions.assertEquals(Shape("a.", ""), DynamicReach.shapeOf("a.\${x"))
    }

    @Test
    fun `a literal with nothing computed or nothing static has no shape`() {
        Assertions.assertNull(DynamicReach.shapeOf("common:status.ok"))
        Assertions.assertNull(DynamicReach.shapeOf("\${name}"))
        Assertions.assertNull(DynamicReach.shapeOf("\${ns}\${key}"))
        Assertions.assertNull(DynamicReach.shapeOf(""))
    }

    // ── The keys a shape reaches ──────────────────────────────────────────────

    @ParameterizedTest(name = "head `{0}` tail `{1}` reaches `{2}`: {3}")
    @CsvSource(
        // A bare segment and nothing else says nothing about the namespace: default namespace only.
        "'errors.', '', 'errors.timeout', true",
        "'errors.', '', 'auth:errors.timeout', false",
        // A namespace written in the head keeps the plain rule.
        "'common:errors.', '', 'common:errors.timeout', true",
        "'common:errors.', '', 'auth:errors.timeout', false",
        // A partial prefix, two segments or more, reaches its keys in every namespace.
        "'menu.items.', '', 'menu.items.home', true",
        "'menu.items.', '', 'admin:menu.items.home', true",
        "'menu.items.', '', 'admin:menu.home', false",
        // A head not ending with the separator is a partial segment: it matches by text, as before.
        "'menu.ho', '', 'menu.home', true",
        "'menu.ho', '', 'admin:menu.home', true",
        // A tail narrows the shape enough to let a one-segment head reach every namespace.
        "'menu.', '.label', 'menu.home.label', true",
        "'menu.', '.label', 'admin:menu.home.label', true",
        "'menu.', '.label', 'admin:menu.home.icon', false",
        "'menu.', '.label', 'admin:footer.home.label', false",
        // A dynamic namespace: the tail alone protects the key in every namespace, not a sibling.
        "'', ':status.ok', 'common:status.ok', true",
        "'', ':status.ok', 'admin:status.ok', true",
        "'', ':status.ok', 'common:status.ko', false",
        // The bare key of a namespaced head: `status.` of `common:status.` matches nothing it should not.
        "'common:status.', '', 'common:status.pending', true",
        "'common:status.', '', 'status.pending', false",
        "'common:status.', '', 'common:title', false",
    )
    fun `a key is reached when it starts with the head and ends with the tail`(head: String, tail: String, key: String, expected: Boolean) {
        Assertions.assertEquals(expected, reaches(head, tail, key))
    }

    @Test
    fun `the configured separators decide what a bare segment is`() {
        Assertions.assertTrue(reaches("errors/", "", "errors/timeout", "::", "/"))
        Assertions.assertFalse(reaches("errors/", "", "auth::errors/timeout", "::", "/"))
        // With `.` as the key separator, `errors/` is no segment at all: the plain rule applies.
        Assertions.assertTrue(reaches("errors/", "", "auth:errors/timeout", ":", "."))
    }

    @Test
    fun `without a key separator no head is a bare segment`() {
        Assertions.assertTrue(reaches("errors", "", "auth:errors", ":", ""))
    }
}
