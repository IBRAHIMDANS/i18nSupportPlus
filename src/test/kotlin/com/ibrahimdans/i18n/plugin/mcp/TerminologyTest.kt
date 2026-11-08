package com.ibrahimdans.i18n.plugin.mcp

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** The translations in place joined to a page of missing values: those sharing a term with it, the rarest words first. */
class TerminologyTest {

    @Test
    fun `placeholders, tags and short words are not words`() {
        Assertions.assertEquals(setOf("save", "trustee"), Terminology.words("Save {{what}} <0>for</0> the trustee %s"))
    }

    @Test
    fun `a word few translations share weighs more than a common one`() {
        val existing = listOf(
            "Open your profile" to "Ouvrir votre profil",
            "Edit your settings" to "Modifier vos réglages",
            "Invite a trustee" to "Inviter un trustee",
        )

        val examples = Terminology.examples(listOf("Remove your trustee", "Close your profile"), existing, max = 2)

        Assertions.assertEquals(listOf("Open your profile", "Invite a trustee"), examples.keys.toList())
    }

    @Test
    fun `no word in common, no example`() {
        Assertions.assertEquals(emptyMap<String, String>(), Terminology.examples(listOf("Close"), listOf("Open" to "Ouvrir")))
    }
}
