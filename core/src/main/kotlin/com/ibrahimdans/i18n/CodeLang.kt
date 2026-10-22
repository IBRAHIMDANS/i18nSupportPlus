package com.ibrahimdans.i18n

import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey

/**
 * The key reading of a source language, on a [CodeNode]: the parser-neutral side of the plugin's
 * `Lang`, which every PSI language answers through its `PsiCodeNode`.
 */
interface CodeLang {

    /** Whether [node] holds a key passed to one of [translationFunctionNames], or to a call the language knows. */
    fun canExtractKey(node: CodeNode, translationFunctionNames: List<String>): Boolean

    /** The key [node] holds, null when it holds none. */
    fun extractRawKey(node: CodeNode): RawKey?
}
