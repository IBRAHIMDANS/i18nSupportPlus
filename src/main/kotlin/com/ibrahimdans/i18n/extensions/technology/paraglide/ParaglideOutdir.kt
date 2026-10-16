package com.ibrahimdans.i18n.extensions.technology.paraglide

import com.intellij.openapi.vfs.VirtualFile

/**
 * The folder Paraglide compiles the messages into (`src/paraglide/` by default, any `--outdir`):
 * `runtime.js` next to `messages.js` or a `messages/` folder holding `_index.js`, one file per message.
 *
 * Its code names every message — `export const hello`, `"user.profile.title"` as an export alias —
 * so a search for a key's words finds it there, and every key read as used. Its hits are set aside
 * by the usage searches; the folder itself is never excluded from the project, which would break
 * the IDE's own resolution of `import { m } from './paraglide/messages.js'`.
 */
object ParaglideOutdir {

    private const val MAX_DEPTH = 3

    /** Whether [file] lies in a Paraglide output folder: itself, or up to [MAX_DEPTH] folders above it. */
    fun contains(file: VirtualFile): Boolean =
        generateSequence(file.parent) { it.parent }.take(MAX_DEPTH).any(::isOutdir)

    private fun isOutdir(folder: VirtualFile): Boolean {
        if (folder.findChild("runtime.js") == null) return false
        return folder.findChild("messages.js") != null || folder.findChild("messages")?.findChild("_index.js") != null
    }
}
