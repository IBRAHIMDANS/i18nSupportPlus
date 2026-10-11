package com.ibrahimdans.i18n.plugin.review

import java.security.MessageDigest
import java.text.Normalizer

/**
 * The fingerprint of a reviewed value: `sha256:` and the first 16 hex digits of the SHA-256 of the
 * value in Unicode NFC, with `\r\n` read as `\n`.
 *
 * Only what an editor or a checkout changes without anyone meaning it is normalized: a file saved
 * with CRLF, or an `é` decomposed by the file system. Whitespace is kept — a space added at the end
 * of a UI string is a real change — and so are placeholders: `{{name}}` → `{{user}}` must drop an
 * approval. The value itself is not stored: the review file is no second copy of the translations.
 */
object ReviewHash {

    private const val PREFIX = "sha256:"
    private const val HEX_DIGITS = 16

    fun of(value: String): String {
        val normalized = Normalizer.normalize(value.replace("\r\n", "\n"), Normalizer.Form.NFC)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it) }.take(HEX_DIGITS)
    }
}
