package com.ibrahimdans.i18n.plugin.ide.inspection

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Flags a translation still the value its review was decided on while the key's text in the
 * module's reference locale changed since — what the table and the key dialog show as *To review
 * (source changed since the decision)*: `fr.json: "save": "Enregistrer"` approved on `Save`, the
 * reference now reading `Save and close`.
 *
 * The source compared is the text the key is translated from ([ReviewSources]): a plural form the reference
 * lacks (`ru`'s `item_few`) compares with the reference's `item_other`.
 *
 * Silent where the module keeps no reviews (no `.i18n-reviews/` folder), in the reference file
 * itself, and on a review that recorded no source. Reads the reviews through [ReviewStore], whose
 * reads are cached: nothing is run nor written. No quick fix: the key dialog re-translates or
 * approves the value.
 */
class StaleTranslationInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "i18n Support Plus"
    override fun getShortName(): String = SHORT_NAME

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        if (DumbService.isDumb(project)) return PsiElementVisitor.EMPTY_VISITOR
        val file = holder.file
        val source = TranslationFileScope.sourceOf(file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val config = Settings.getInstance(project).config()
        val module = ModuleSources.owner(config.modules, TranslationDataLoader.projectPathOf(source))
        val store = ReviewStore.getInstance(project)
        val root = ReviewLocation.rootOf(project, module)?.takeIf { store.isEnabled(it) }
            ?: return PsiElementVisitor.EMPTY_VISITOR
        val locale = source.localeLabel()
        val referenceLocale = TranslationFileScope.referenceLocaleFor(file, source)
        if (locale == referenceLocale) return PsiElementVisitor.EMPTY_VISITOR
        val reviews = store.reviews(root, locale)
        if (reviews.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR

        // The key as the table and the key dialog file its review: spelled without a default
        // namespace, then read back, so that a default namespace other than the first maps alike.
        val defaults = config.defaultNamespaces()
        val namespace = TranslationDataLoader.extractNamespace(source, defaults.first())

        // Not [referenceValueVisitor]: it skips a key the reference lacks, a plural form's included.
        val references: Map<String, String> by lazy { TranslationFileKeys.referenceTranslations(file) }
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val entry = TranslationPsi.entryOf(element) ?: return
                val literal = entry.literal ?: return
                val value = entry.text?.takeIf { it.isNotEmpty() } ?: return
                val path = TranslationFileKeys.keyOf(literal)
                val review = reviews[ReviewKey.fromSpelling(ReviewKey(namespace, path).spelling(defaults), defaults.first())] ?: return
                val current = ReviewSources.sourceTextOf(path, config.pluralSeparator, referenceText = references::get)?.let { ReviewSource.of(referenceLocale, it) }
                if (shown(review, value, current) != ShownReview.SOURCE_CHANGED) return
                holder.registerProblem(literal, PluginBundle.message("inspection.stale.message", referenceLocale))
            }
        }
    }

    internal companion object {
        const val SHORT_NAME = "I18nStaleTranslation"
    }
}
