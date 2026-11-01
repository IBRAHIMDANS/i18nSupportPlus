package com.ibrahimdans.i18n.plugin.review.ui

import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileKeys
import com.ibrahimdans.i18n.plugin.ide.inspection.TranslationFileScope
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.ide.toolwindow.ReviewBadge
import com.ibrahimdans.i18n.plugin.ide.toolwindow.TranslationDataLoader
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewLocation
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ReviewStore
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import com.ibrahimdans.i18n.plugin.utils.ModuleSources
import com.ibrahimdans.i18n.plugin.utils.PluginBundle
import com.ibrahimdans.i18n.plugin.utils.TranslationEntry
import com.ibrahimdans.i18n.plugin.utils.TranslationPsi
import com.ibrahimdans.i18n.plugin.utils.localeLabel
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent

/**
 * The review of each value of a JSON or YAML translation file, in its gutter: the icon the table
 * shows in the value's cell ([ReviewBadge]) — to review, approved, changes requested, source
 * changed — and its tooltip. A click offers *Approve*, *Request Changes…* and *Open Thread…*, the
 * decisions of the table's menu, written by the same [ReviewActions].
 *
 * Only where the module keeps reviews (its `.i18n-reviews/` folder), and only on a value with a
 * review: a value never reviewed shows nothing, as in the table. The marker sits on the first leaf
 * of the key, as the platform asks of a line marker.
 *
 * Slow markers: the file's scope — its source, module, locale and reviews — is read once per pass,
 * not once per element. The reviews are [ReviewStore]'s cached reads; nothing is written.
 */
class ReviewLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (DumbService.isDumb(file.project)) return
        val scope = ReviewFileScope.of(file) ?: return
        for (element in elements) {
            ProgressManager.checkCanceled()
            if (element.firstChild != null) continue
            val entry = entryKeyedBy(element) ?: continue
            scope.markerOf(element, entry)?.let(result::add)
        }
    }

    /** The key-value whose key starts with [leaf], or null: one marker per value, on its key. */
    private fun entryKeyedBy(leaf: PsiElement): TranslationEntry? {
        val property = TranslationPsi.propertyOf(leaf) ?: return null
        val entry = TranslationPsi.entryOf(property) ?: return null
        return entry.takeIf { PsiTreeUtil.getDeepestFirst(it.keyElement) === leaf }
    }
}

/**
 * The reviews of one translation file: the [root] its module's reviews hang under, its [locale],
 * the module's [referenceLocale], and how a key of the file is spelled in the table — with its
 * namespace unless it is a default one — so that the gutter files a decision where the table does.
 */
internal class ReviewFileScope private constructor(
    private val project: Project,
    val root: VirtualFile,
    val locale: String,
    val referenceLocale: String,
    private val namespace: String,
    private val defaultNamespaces: List<String>,
    private val pluralSeparator: String,
    private val reviews: Map<ReviewKey, Review>,
    private val file: PsiFile,
) {
    private val references: Map<String, String> by lazy { TranslationFileKeys.referenceTranslations(file) }

    /** The locale cell [entry] stands for, as the table builds it, or null when it holds no string value. */
    fun cellOf(entry: TranslationEntry): ReviewCell? {
        val literal = entry.literal ?: return null
        val value = entry.text?.takeIf { it.isNotEmpty() } ?: return null
        val path = TranslationFileKeys.keyOf(literal)
        val key = ReviewKey(namespace, path).spelling(defaultNamespaces)
        val source = if (locale == referenceLocale) null
        else ReviewSources.sourceTextOf(path, pluralSeparator, referenceText = references::get)
        return ReviewCell(key, locale, value, source)
    }

    /** The stored review of [cell], or null. */
    fun reviewOf(cell: ReviewCell): Review? = reviews[ReviewKey.fromSpelling(cell.key, defaultNamespaces.first())]

    /** The marker of [entry], anchored on [leaf]; null when its value has no review to show. */
    fun markerOf(leaf: PsiElement, entry: TranslationEntry): ReviewLineMarkerInfo? {
        val cell = cellOf(entry) ?: return null
        val review = reviewOf(cell) ?: return null
        val state = shown(review, cell.value, ReviewActions.sourceOf(cell, referenceLocale))
        if (state == ShownReview.NONE) return null
        return ReviewLineMarkerInfo(leaf, project, root, cell, referenceLocale, review, state)
    }

    companion object {
        /** The scope of [file], or null when it is no translation file or its module keeps no reviews. */
        fun of(file: PsiFile): ReviewFileScope? {
            val project = file.project
            val source = TranslationFileScope.sourceOf(file) ?: return null
            val config = Settings.getInstance(project).config()
            val module = ModuleSources.owner(config.modules, TranslationDataLoader.projectPathOf(source))
            val store = ReviewStore.getInstance(project)
            val root = ReviewLocation.rootOf(project, module)?.takeIf { store.isEnabled(it) } ?: return null
            val locale = source.localeLabel()
            val reviews = store.reviews(root, locale)
            if (reviews.isEmpty()) return null
            val defaults = config.defaultNamespaces()
            return ReviewFileScope(
                project, root, locale, TranslationFileScope.referenceLocaleFor(file, source),
                TranslationDataLoader.extractNamespace(source, defaults.first()), defaults, config.pluralSeparator, reviews, file
            )
        }
    }
}

/**
 * The review marker of one value: [cell] the locale cell it stands for, [state] what its [review]
 * means for the value held now. A click opens [ReviewGutterActions]' menu.
 */
internal class ReviewLineMarkerInfo(
    leaf: PsiElement,
    project: Project,
    val root: VirtualFile,
    val cell: ReviewCell,
    val referenceLocale: String,
    review: Review,
    val state: ShownReview,
) : LineMarkerInfo<PsiElement>(
    leaf,
    leaf.textRange,
    ReviewBadge.icon(state)!!,
    { ReviewBadge.tooltip(null, review, state, cell.source) },
    GutterIconNavigationHandler { e: MouseEvent, _: PsiElement -> ReviewGutterActions.showMenu(e, project, root, cell, referenceLocale) },
    GutterIconRenderer.Alignment.LEFT,
    { ReviewBadge.label(state) },
)

/** What a click on a review marker offers: the table's decisions on the marker's one cell. */
internal object ReviewGutterActions {

    fun showMenu(e: MouseEvent, project: Project, root: VirtualFile, cell: ReviewCell, referenceLocale: String) {
        val group = DefaultActionGroup(
            DumbAwareAction.create(PluginBundle.message("review.action.approve")) { approve(project, root, cell, referenceLocale) },
            DumbAwareAction.create(PluginBundle.message("review.action.request.changes")) { requestChanges(project, root, cell, referenceLocale) },
            DumbAwareAction.create(PluginBundle.message("review.gutter.open.thread")) { openThread(project, root, cell, referenceLocale) },
        )
        JBPopupFactory.getInstance().createActionGroupPopup(
            PluginBundle.message("review.gutter.menu.title", cell.key, cell.locale), group,
            DataManager.getInstance().getDataContext(e.component), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
        ).show(RelativePoint(e))
    }

    /** [cell] approved, as the table's *Approve* does. False when nothing was written. */
    fun approve(project: Project, root: VirtualFile, cell: ReviewCell, referenceLocale: String): Boolean =
        ReviewActions.approve(project, root, listOf(cell), referenceLocale = referenceLocale)
            .also { ReviewPrompts.reportIfUnwritten(project, it, listOf(cell)) }

    private fun requestChanges(project: Project, root: VirtualFile, cell: ReviewCell, referenceLocale: String) {
        val text = ReviewPrompts.askChangeRequest(project) ?: return
        val written = ReviewActions.requestChanges(project, root, listOf(cell), text, referenceLocale = referenceLocale)
        ReviewPrompts.reportIfUnwritten(project, written, listOf(cell))
    }

    private fun openThread(project: Project, root: VirtualFile, cell: ReviewCell, referenceLocale: String) {
        ReviewThreadDialog(project, root, cell, referenceLocale = referenceLocale).show()
    }
}
