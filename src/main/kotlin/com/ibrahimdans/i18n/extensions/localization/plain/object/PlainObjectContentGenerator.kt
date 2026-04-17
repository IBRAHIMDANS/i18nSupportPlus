package com.ibrahimdans.i18n.extensions.localization.plain.`object`

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import javax.swing.Icon

// PO uses flat GNU gettext entries (`msgid`/`msgstr` pairs). The GNU GetText PSI plugin
// (org.jetbrains.plugins.localization) is unavailable on IntelliJ 243.x+, so we fall back
// to document-level text insertion.
class PlainObjectContentGenerator : ContentGenerator {

    private object PoFileType : FileType {
        override fun getName(): String = "PO"
        override fun getDescription(): String = "GNU GetText PO file"
        override fun getDefaultExtension(): String = "po"
        override fun getIcon(): Icon? = null
        override fun isBinary(): Boolean = false
    }

    override fun generateContent(compositeKey: List<Literal>, value: String): String {
        val key = compositeKey.joinToString(".") { it.text }
        return "msgid \"${key.escapePo()}\"\nmsgstr \"${value.escapePo()}\"\n"
    }

    override fun getType(): FileType {
        val localeFileType = FileTypeManager.getInstance().getStdFileType("Locale")
        return if (localeFileType != PlainTextFileType.INSTANCE) localeFileType else PoFileType
    }

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
        document.insertString(document.textLength, "${separator}msgid \"${key.escapePo()}\"\nmsgstr \"${value.escapePo()}\"\n")
        manager.commitDocument(document)
    }

    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val key = unresolved.joinToString(".") { it.text }
        generateTranslationEntry(element, key, translationValue ?: "")
    }

    private fun String.escapePo(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
