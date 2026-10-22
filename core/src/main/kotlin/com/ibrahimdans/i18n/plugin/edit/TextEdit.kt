package com.ibrahimdans.i18n.plugin.edit

import com.ibrahimdans.i18n.plugin.utils.TextSpan

/**
 * Replaces the text of [span] with [newText]: an insertion when [span] is empty, a deletion when
 * [newText] is. The platform-free counterpart of an LSP `TextEdit`, offsets counted in chars.
 */
data class TextEdit(val span: TextSpan, val newText: String) {

    companion object {
        /** An insertion of [text] at [offset]. */
        fun insert(offset: Int, text: String): TextEdit = TextEdit(TextSpan(offset, offset), text)

        /**
         * [text] with [edits] applied. The edits are all located in [text] as it is, and do not
         * overlap: they are applied from the last one back, so that none shifts another.
         */
        fun apply(text: String, edits: List<TextEdit>): String {
            val builder = StringBuilder(text)
            edits.sortedByDescending { it.span.start }.forEach { builder.replace(it.span.start, it.span.end, it.newText) }
            return builder.toString()
        }
    }
}
