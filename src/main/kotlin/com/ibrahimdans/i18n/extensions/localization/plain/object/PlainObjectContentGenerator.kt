package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

// PO is a flat list of msgid/msgstr entries with no nested blocks. The GNU GetText PSI plugin
// (org.jetbrains.plugins.localization) is unavailable on IntelliJ 243.x+, so we fall back to
// document-level text insertion.
class PlainObjectContentGenerator : ContentGenerator {

    override fun generateContent(compositeKey: List<Literal>, value: String): String {
        val key = compositeKey.joinToString(".") { it.text }
        return "msgid \"${key.escapePo()}\"\nmsgstr \"${value.escapePo()}\"\n"
    }

    override fun getType(): FileType = PlainTextFileType.INSTANCE

    override fun getLanguage(): Language = PlainTextLanguage.INSTANCE

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.plainObject.translation.files")

    override fun isSuitable(element: PsiElement): Boolean {
        val ext = element.containingFile?.virtualFile?.extension?.lowercase()
        return ext == "po" || ext == "pot"
    }

    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        val file = item.containingFile ?: return
        val manager = PsiDocumentManager.getInstance(item.project)
        val document = manager.getDocument(file) ?: return
        val existing = document.text
        val separator = when {
            existing.isEmpty() -> ""
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val entry = generateContent(listOf(Literal(key)), value)
        document.insertString(document.textLength, separator + entry)
        manager.commitDocument(document)
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val key = unresolved.joinToString(".") { it.text }
        generateTranslationEntry(element, key, translationValue ?: "")
    }

    private fun String.escapePo(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
