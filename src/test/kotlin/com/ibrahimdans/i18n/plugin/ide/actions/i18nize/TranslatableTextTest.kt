package com.ibrahimdans.i18n.plugin.ide.actions.i18nize

import com.ibrahimdans.i18n.plugin.ide.actions.i18nize.TranslatableText.Reason
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class TranslatableTextTest {

    private fun assertReason(expected: Reason?, vararg texts: String) = texts.forEach { text ->
        Assertions.assertEquals(expected, TranslatableText.uncheckedReason(text), "'$text'")
    }

    @Test
    fun aMessageStaysChecked() = assertReason(
        null,
        "OK", "Save", "Delete", "Save changes", "Hello {{name}}", "{{count}} files", "Wi-Fi", "and/or",
        "Page 2 of 3", "set up your e-mail", "Are you sure?"
    )

    @Test
    fun aUrlIsUnchecked() = assertReason(
        Reason.URL, "https://example.com/a?b=c", "//cdn.example.com/x.js", "mailto:support@example.com", "www.example.com"
    )

    @Test
    fun aPathIsUnchecked() = assertReason(Reason.PATH, "/home", "./logo.png", "../a/b", "assets/img/logo", "logo.png", "@/components/Button")

    @Test
    fun anIdentifierIsUnchecked() = assertReason(
        Reason.IDENTIFIER, "userName", "user_name", "USER_NAME", "user-name", "primary-button", "user.name"
    )

    @Test
    fun cssClassesAreUnchecked() = assertReason(Reason.CSS_CLASS, "flex items-center gap-2", "btn btn-primary", "md:flex", "w-[200px]")

    @Test
    fun aColorIsUnchecked() = assertReason(Reason.COLOR, "#fff", "#1a2B3c", "rgba(0, 0, 0, 0.5)", "hsl(120 50% 50%)", "transparent")

    @Test
    fun anIsoDateOrADateFormatIsUnchecked() = assertReason(
        Reason.DATE, "2024-01-31", "2024-01-31T10:00:00Z", "10:30", "YYYY-MM-DD", "dd/MM/yyyy", "HH:mm"
    )

    @Test
    fun aNumberIsUnchecked() = assertReason(Reason.NUMBER, "42", "-1.5", "1,000", "16px", "50%", "0 4px 6px")

    @Test
    fun symbolsAloneAreUnchecked() = assertReason(Reason.SYMBOLS, "—", "•", "...")

    @Test
    fun technicalAttributesAreCodeButAriaLabelsAreMessages() {
        listOf("className", "id", "data-testid", "href", "src", "type", "key", "name", "role", "rel", "target", "htmlFor",
            "aria-labelledby", "aria-hidden").forEach { Assertions.assertTrue(TranslatableText.isTechnicalAttribute(it), it) }
        listOf("aria-label", "aria-description", "title", "placeholder", "alt", "label", "message")
            .forEach { Assertions.assertFalse(TranslatableText.isTechnicalAttribute(it), it) }
    }
}
