package com.ibrahimdans.i18n.extensions.localization.js

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.PluginNotifications
import com.intellij.lang.Language
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Writes keys into the object literals of a TS/JS catalogue: a catalogue keyed by locale (i18n-js)
 * or the `resources` of an i18next configuration file.
 */
class TsContentGenerator : ContentGenerator {

    private val tabChar = "  "

    /** A whole new catalogue object: `{ a: { b: 'value' } }`, one key per line. */
    override fun generateContent(compositeKey: List<Literal>, value: String): String =
        compositeKey.foldRightIndexed(quote(value)) { i, key, acc ->
            val tab = tabChar.repeat(i)
            "{\n$tabChar$tab${key.text}: $acc,\n$tab}"
        }

    private fun tsFileType(): FileType =
        FileTypeManager.getInstance().findFileTypeByName("TypeScript") ?: UnknownFileType.INSTANCE

    override fun getType(): FileType = tsFileType()

    override fun getLanguage(): Language = (tsFileType() as? LanguageFileType)?.language ?: Language.ANY

    override fun getDescription(): String = PluginBundle.getMessage("quickfix.create.typeScript.translation.files")

    override fun isSuitable(element: PsiElement): Boolean = element is JSObjectLiteralExpression

    /** Adds `key: 'value'` to [item], an object literal; [value] is the text of the value. */
    override fun generateTranslationEntry(item: PsiElement, key: String, value: String) {
        insert(item as JSObjectLiteralExpression, listOf(key), value)
    }

    /**
     * Writes the [unresolved] segments of [fullKey] into [element], the deepest object of the
     * catalogue the key reaches, unless that object is built in code — then nothing is written, and
     * a warning says which key and file to complete by hand.
     */
    override fun generate(element: PsiElement, fullKey: FullKey, unresolved: List<Literal>, translationValue: String?) {
        val obj = element as? JSObjectLiteralExpression ?: return
        val first = unresolved.firstOrNull() ?: return
        // A property of that name already there holds a value the catalogue builds (`home: homeEn`):
        // writing a second one would hide it.
        if (TsObjectInsertion.isBuilt(obj) || obj.findProperty(first.text) != null) {
            PluginNotifications.notify(
                obj.project,
                PluginBundle.getMessage("ts.catalog.write.refused", fullKey.source, obj.containingFile.name),
                NotificationType.WARNING
            )
            return
        }
        insert(obj, unresolved.map { it.text }, translationValue ?: fullKey.source)
    }

    private fun insert(obj: JSObjectLiteralExpression, segments: List<String>, value: String) {
        val project = obj.project
        val settings = Settings.getInstance(project)
        val key = segments.first()
        CodeStyleManager.getInstance(project).performActionWithFormatterDisabled {
            TsObjectInsertion(obj).insert(segments, value) { props ->
                if (settings.extractSorted) props.takeWhile { settings.keyOrder.comparator.compare(it.name ?: "", key) < 0 } else props
            }
        }
    }

    private fun quote(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
