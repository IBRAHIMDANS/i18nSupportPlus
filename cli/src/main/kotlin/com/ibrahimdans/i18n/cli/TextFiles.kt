package com.ibrahimdans.i18n.cli

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The files `i18n-check` reads from disk, decoded as UTF-8. A `.js` minified with a byte no UTF-8 encodes, or a
 * Latin-1 source, used to end the command with the stack trace of a `MalformedInputException`. Code is searched
 * for keys, so a replacement character in place of such a byte changes nothing: it is read anyway ([readOrNull]).
 * Every byte of a manifest or a translation file matters: one that is not UTF-8 stops the check, naming the file
 * ([read]) — except a Java resource bundle, which Java itself reads as UTF-8 and then as ISO-8859-1 ([readBundle]).
 */
internal object TextFiles {

    /**
     * The code at [path], a byte no UTF-8 encodes replaced by U+FFFD; null when the file cannot be read at all, which
     * leaves its code out of the check. Either way [warn] is told once, the file named as [shownAs].
     */
    fun readOrNull(path: Path, shownAs: String = path.toString(), warn: (String) -> Unit): String? {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            warn("cannot read $shownAs (${reason(e)}): its code is not checked")
            return null
        }
        return try {
            decode(bytes, CodingErrorAction.REPORT)
        } catch (e: CharacterCodingException) {
            warn("$shownAs is not UTF-8: its code is read with replacement characters")
            decode(bytes, CodingErrorAction.REPLACE)
        }
    }

    /** The manifest or translation file at [path], whole; one that is not UTF-8, or cannot be read, stops the check naming it as [shownAs]. */
    fun read(path: Path, shownAs: String = path.toString()): String = try {
        text(path)
    } catch (e: IOException) {
        throw CannotCheck("cannot read $shownAs: ${reason(e)}")
    }

    /**
     * The `.properties` bundle at [path], as `PropertyResourceBundle` reads it since Java 9: UTF-8, and ISO-8859-1
     * when a byte is no UTF-8 — a bundle in Latin-1 is a bundle still. One that cannot be read stops the check naming it as [shownAs].
     */
    fun readBundle(path: Path, shownAs: String = path.toString()): String {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw CannotCheck("cannot read $shownAs: ${reason(e)}")
        }
        return try {
            decode(bytes, CodingErrorAction.REPORT)
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /** The UTF-8 text at [path], for a caller that reports its own errors: a byte no UTF-8 encodes is an [IOException] like the others. */
    fun text(path: Path): String = decode(Files.readAllBytes(path), CodingErrorAction.REPORT)

    private fun decode(bytes: ByteArray, onError: CodingErrorAction): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(onError)
            .onUnmappableCharacter(onError)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /** Why the file could not be read, for a reader: the exception's own message is the path again, or `Input length = 1`. */
    fun reason(e: IOException): String = when (e) {
        is CharacterCodingException -> "not valid UTF-8"
        is NoSuchFileException -> "no such file"
        is AccessDeniedException -> "permission denied"
        else -> e.message ?: e.javaClass.simpleName
    }
}
