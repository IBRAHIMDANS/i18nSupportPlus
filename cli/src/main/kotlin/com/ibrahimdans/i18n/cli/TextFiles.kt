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
 * ([read]).
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
        decode(Files.readAllBytes(path), CodingErrorAction.REPORT)
    } catch (e: IOException) {
        throw CannotCheck("cannot read $shownAs: ${reason(e)}")
    }

    private fun decode(bytes: ByteArray, onError: CodingErrorAction): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(onError)
            .onUnmappableCharacter(onError)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /** Why the file could not be read, for a reader: the exception's own message is the path again. */
    private fun reason(e: IOException): String = when (e) {
        is CharacterCodingException -> "not valid UTF-8"
        is NoSuchFileException -> "no such file"
        is AccessDeniedException -> "permission denied"
        else -> e.message ?: e.javaClass.simpleName
    }
}
