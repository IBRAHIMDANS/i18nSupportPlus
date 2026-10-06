package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.ide.actions.ExtractI18nIntentionAction
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Flags a text written as is in JSX — `<button>Save</button>`, `<input placeholder="Name"/>` —
 * with an *Extract i18n key* quick fix that runs [ExtractI18nIntentionAction] on it.
 *
 * Off by default, and silent whenever in doubt: an inspection that underlines every `id` or `—`
 * gets disabled in the first minute. A text is reported only when all of these hold:
 *  - [JsxTranslationExtractor] can extract it and it is not extracted already — the rules the
 *    *Extract* intention applies, so the quick fix never meets a text it refuses. This leaves out
 *    the text of a tag holding another tag (`Hi` in `<p>Hi <b>you</b></p>`, where `you` is
 *    reported) and any file that is not `.jsx` / `.tsx`;
 *  - it holds a letter once HTML entities are removed: punctuation, numbers, whitespace and
 *    `&nbsp;` alone are not text to translate;
 *  - each JSX expression of its tag can become a variable of the message — `{name}`, `{user.name}`
 *    ([JsxTranslationExtractor.variables]): `<p>Hello {name}</p>` is reported and extracts to
 *    `Hello {{name}}`, while a call, a condition or a comment keeps the tag silent;
 *  - an attribute is one a user reads ([HardcodedTextRules.VISIBLE_ATTRIBUTES]) — `className`, `style`, `key`, `id`,
 *    `data-*` and every other attribute are never reported — and holds a plain string, not `{…}`;
 *  - the tag is neither a translation component whose text is already the message or its fallback
 *    (`<Trans>`) nor one whose text is code ([UNTRANSLATED_TAGS]).
 */
class HardcodedJsxTextInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = "I18nHardcodedJsxText"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is XmlText -> checkText(element, holder)
                    is XmlAttributeValue -> checkAttribute(element, holder)
                }
            }
        }

    private fun checkText(text: XmlText, holder: ProblemsHolder) {
        val tag = PsiTreeUtil.getParentOfType(text, XmlTag::class.java) ?: return
        // The extractor takes every text of the tag at once: report it once, on the first.
        if (tag.value.textElements.firstOrNull() != text) return
        if (EXTRACTOR.variables(tag) == null) return
        check(text.firstChild ?: return, tag, holder)
    }

    private fun checkAttribute(value: XmlAttributeValue, holder: ProblemsHolder) {
        val attribute = value.parent as? XmlAttribute ?: return
        if (attribute.name !in HardcodedTextRules.VISIBLE_ATTRIBUTES) return
        // The string between the quotes; `title={…}` holds an expression instead, and `title=""` nothing.
        val token = generateSequence(value.firstChild) { it.nextSibling }
            .firstOrNull { it.firstChild == null && it.textRange == value.valueTextRange && !it.textRange.isEmpty }
            ?: return
        check(token, attribute.parent ?: return, holder)
    }

    /** Reports the text [leaf] holds in [tag], over the range the extraction replaces, when it is one to translate. */
    private fun check(leaf: PsiElement, tag: XmlTag, holder: ProblemsHolder) {
        if (tag.name in UNTRANSLATED_TAGS) return
        if (!EXTRACTOR.canExtract(leaf) || EXTRACTOR.isExtracted(leaf)) return
        if (!HardcodedTextRules.isTranslatable(EXTRACTOR.text(leaf))) return
        val range = EXTRACTOR.textRange(leaf).shiftLeft(tag.textRange.startOffset)
        holder.registerProblem(tag, range, PluginBundle.message("inspection.hardcoded.jsx.text.message"), ExtractFix(leaf))
    }

    /** Delegates to [ExtractI18nIntentionAction], which asks for the key and writes it. */
    private class ExtractFix(leaf: PsiElement) : LocalQuickFixAndIntentionActionOnPsiElement(leaf) {
        override fun getText(): String = PluginBundle.message("action.intention.extract.key")
        override fun getFamilyName(): String = text
        override fun startInWriteAction(): Boolean = false
        override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
            IntentionPreviewInfo.EMPTY

        override fun invoke(project: Project, psiFile: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
            ExtractI18nIntentionAction().invoke(project, editor ?: return, startElement)
        }
    }

    private companion object {

        private val EXTRACTOR = JsxTranslationExtractor()

        /** `Trans` (react-i18next, lingui) holds a message or its fallback; the others hold code. */
        private val UNTRANSLATED_TAGS = HardcodedTextRules.CODE_TAGS + "Trans"
    }
}
