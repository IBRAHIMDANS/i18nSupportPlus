package com.ibrahimdans.i18n.plugin.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReviewerIdentityTest {

    @Test
    fun `the configured name comes first, the VCS is not even asked`() {
        assertEquals("Alice", ReviewerIdentity.resolve("  Alice ", { error("must not be asked") }, "alice"))
    }

    @Test
    fun `without a configured name, the VCS identity is used`() {
        assertEquals("Alice <alice@example.com>", ReviewerIdentity.resolve("", { "Alice <alice@example.com>" }, "alice"))
    }

    @Test
    fun `without Git, the OS account signs`() {
        assertEquals("alice", ReviewerIdentity.resolve(null, { null }, "alice"))
        assertEquals("alice", ReviewerIdentity.resolve(" ", { " " }, "alice"))
    }

    @Test
    fun `nobody known at all is unknown`() {
        assertEquals(ReviewerIdentity.UNKNOWN, ReviewerIdentity.resolve(null, { null }, null))
    }

    @Test
    fun `a name and an email are written as a mail address`() {
        assertEquals("Alice <alice@example.com>", ReviewerIdentity.format("Alice", "alice@example.com"))
        assertEquals("Alice", ReviewerIdentity.format("Alice", ""))
        assertEquals("<alice@example.com>", ReviewerIdentity.format(null, "alice@example.com"))
        assertNull(ReviewerIdentity.format(" ", null))
    }
}
