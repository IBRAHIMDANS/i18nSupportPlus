package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.psi.PhpPsiElementFactory
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement

/**
 * Writes keys into the array a PHP translation file returns, as single-quoted strings.
 */
class PhpArrayContentGenerator : ContentGenerator {

    /** A whole new file: `<?php return [...];`. */
    override fun generateContent(compositeKey: List<Literal>, value: String): String =
        "<?php\n\nreturn ${arrayOf(compositeKey, value, 0)};\n"

    override fun getType(): FileType = PhpFileType.INSTANCE
    override fun getLanguage(): Language = PhpLanguage.INSTANCE
    override fun getDescription(): String = "Create PHP array translation files"
    override fun isSuitable(element: PsiElement): Boolean = element is ArrayCreationExpression

    /**
     * Adds `'key' => value` to [item], an array; [value] is PHP code. The entry goes in first and
     * the comma joining it to its neighbour second, as for JSON: a failure in between leaves no
     * dangling comma.
     */
    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val array = item as ArrayCreationExpression
        val project = item.project
        val entry = createEntry(project, key, value) ?: return
        val entries = array.hashElements.toList()
        val before = if (Settings.getInstance(project).extractSorted) {
            entries.takeWhile { (PhpArrays.keyOf(it)?.contents ?: "") < key }
        } else {
            entries
        }
        val inserted = when {
            entries.isEmpty() -> array.addAfter(entry, openingBracket(array) ?: return)
            before.isEmpty() -> array.addBefore(entry, entries.first()).also {
                array.addAfter(PhpPsiElementFactory.createComma(project), it)
            }
            else -> array.addAfter(entry, before.last()).also {
                array.addBefore(PhpPsiElementFactory.createComma(project), it)
            }
        }
        array.addBefore(PhpPsiElementFactory.createNewLine(project), inserted)
        val start = inserted.prevSibling?.textRange?.startOffset ?: inserted.textRange.startOffset
        CodeStyleManager.getInstance(project).reformatRange(array, start, inserted.textRange.endOffset)
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val first = unresolved.firstOrNull() ?: return
        generateTranslationEntry(element, first.text, arrayOf(unresolved.drop(1), translationValue ?: fullKey.source, 0))
    }

    /**
     * The entry `key => value`, read from a file returning it: the factory's search by class
     * returns the innermost entry of a nested value (`'throttle' => …` of `'auth' => ['throttle' => …]`).
     */
    private fun createEntry(project: Project, key: String, value: String): ArrayHashElement? {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("entry.php", PhpFileType.INSTANCE, "<?php\nreturn [${quote(key)} => $value];\n")
        return PhpArrays.rootArray(file)?.hashElements?.firstOrNull()
    }

    /** `[` of a short array, `(` of `array(...)`. */
    private fun openingBracket(array: ArrayCreationExpression): PsiElement? =
        generateSequence(array.firstChild) { it.nextSibling }.firstOrNull { it.text == "[" || it.text == "(" }

    companion object {
        private const val INDENT = "    "

        /** [value] under the nested keys of [compositeKey], or [value] alone when there is none. */
        internal fun arrayOf(compositeKey: List<Literal>, value: String, depth: Int): String {
            val key = compositeKey.firstOrNull() ?: return quote(value)
            val inner = arrayOf(compositeKey.drop(1), value, depth + 1)
            return "[\n${INDENT.repeat(depth + 1)}${quote(key.text)} => $inner,\n${INDENT.repeat(depth)}]"
        }

        /** [text] as a single-quoted PHP string: only `\` and `'` are escaped there. */
        internal fun quote(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}
