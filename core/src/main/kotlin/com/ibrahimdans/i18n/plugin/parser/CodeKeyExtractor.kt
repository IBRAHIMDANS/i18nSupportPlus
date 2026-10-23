package com.ibrahimdans.i18n.plugin.parser

/**
 * Extractor of an i18n key from a [CodeNode]: the parser-neutral side of the plugin's
 * `KeyExtractor`, which every PSI extractor answers through its `PsiCodeNode`.
 */
interface CodeKeyExtractor {

    /** Whether this extractor reads a key from [node]. */
    fun canExtract(node: CodeNode): Boolean

    /** The key [node] holds, null when this extractor reads none from it. */
    fun extract(node: CodeNode): RawKey?
}
