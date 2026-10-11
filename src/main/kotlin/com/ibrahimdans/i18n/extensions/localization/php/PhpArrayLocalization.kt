package com.ibrahimdans.i18n.extensions.localization.php

import com.ibrahimdans.i18n.ContentGenerator
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationConfig
import com.ibrahimdans.i18n.LocalizationConfigImpl
import com.ibrahimdans.i18n.LocalizationFileType
import com.ibrahimdans.i18n.TranslationReferenceAssistant
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.ibrahimdans.i18n.plugin.utils.LocalizationSourceService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import javax.swing.Icon

/**
 * Laravel's PHP translation files: `lang/<locale>/<file>.php` returning an array of keys, the file
 * name being the namespace (`__('messages.welcome')` reads `welcome` in `lang/en/messages.php`).
 *
 * Declared in `phpConfig.xml`, loaded only with the PHP plugin. A PHP file is code far more often
 * than a translation: it is read as one only from a locale directory, and only when it returns an
 * array ([PhpArrayTree.create]).
 */
class PhpArrayLocalization : Localization<StringLiteralExpression> {
    override fun types(): List<LocalizationFileType> = listOf(LocalizationFileType(PhpFileType.INSTANCE))
    override fun contentGenerator(): ContentGenerator = PhpArrayContentGenerator()
    override fun referenceAssistant(): TranslationReferenceAssistant<StringLiteralExpression> = PhpArrayReferenceAssistant()
    // The scan of all sources reads any file under the translation root, or next to a locale-named
    // stem (`app/en.php`): only a file of a locale directory is a translation file.
    override fun elementsTree(file: PsiElement): Tree<PsiElement>? =
        (file as? PsiFile)?.originalFile?.virtualFile?.takeIf(::isInLocaleDirectory)?.let { PhpArrayTree.create(file) }
    override fun matches(localizationFileType: LocalizationFileType, file: VirtualFile?, fileNames: List<String>): Boolean =
        file != null && file.extension == PHP_EXTENSION && file.nameWithoutExtension in fileNames && isInLocaleDirectory(file)
    override fun icon(): Icon = PhpFileType.INSTANCE.icon ?: super.icon()
    override fun config(): LocalizationConfig = LocalizationConfigImpl("php")

    companion object {
        private const val PHP_EXTENSION = "php"
        private const val VENDOR = "vendor"

        /**
         * Whether [file] lies in a locale directory (`lang/en/messages.php`) of the application:
         * a package's overrides (`lang/vendor/<package>/en/messages.php`) are keys of another
         * namespace, `package::messages`, and must not answer for `messages`.
         */
        fun isInLocaleDirectory(file: VirtualFile): Boolean {
            val localeDir = file.parent ?: return false
            if (!LocalizationSourceService.looksLikeLocale(localeDir.name)) return false
            return localeDir.parent?.parent?.name != VENDOR
        }
    }
}
