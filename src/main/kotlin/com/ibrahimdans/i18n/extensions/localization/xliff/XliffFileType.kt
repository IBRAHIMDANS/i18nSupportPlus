package com.ibrahimdans.i18n.extensions.localization.xliff

import com.ibrahimdans.i18n.plugin.utils.XliffTranslationCodec
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.lang.xml.XMLLanguage
import javax.swing.Icon

/**
 * XLIFF translation files (`messages.xlf`, `messages.fr.xlf`), parsed as XML.
 *
 * A type of their own rather than `.xlf` added to the XML type: the formats find their files by type,
 * and an XLIFF format registered on the XML type would have read every `pom.xml` and every file of `.idea`
 * as a translation candidate. The IDE registers no type for these extensions.
 */
class XliffFileType private constructor() : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName(): String = NAME
    override fun getDescription(): String = "XLIFF translation file"
    override fun getDefaultExtension(): String = DEFAULT_EXTENSION
    override fun getIcon(): Icon = AllIcons.FileTypes.Xml

    companion object {
        const val NAME = "XLIFF"
        const val DEFAULT_EXTENSION = "xlf"

        /** The extensions an XLIFF file is recognised by: the same as the export and import use. */
        val EXTENSIONS: Set<String> = XliffTranslationCodec.EXTENSIONS

        @JvmField
        val INSTANCE = XliffFileType()
    }
}
