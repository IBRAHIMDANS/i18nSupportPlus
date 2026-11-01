package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.review.Review
import com.ibrahimdans.i18n.plugin.review.ReviewKey
import com.ibrahimdans.i18n.plugin.review.ReviewSource
import com.ibrahimdans.i18n.plugin.review.ReviewSources
import com.ibrahimdans.i18n.plugin.review.ShownReview
import com.ibrahimdans.i18n.plugin.review.shown
import java.util.concurrent.ConcurrentHashMap

/**
 * The reviews of the table's module, by locale, as `.i18n-reviews/<locale>.json` holds them.
 *
 * [OFF] where the module has no reviews folder: no cell shows a review, the status combo does not
 * offer the review filters and the context menu adds nothing — a team that never asked for reviews
 * sees the table it always had. Pure: the panel reads the store, the filters and the renderer read
 * this, so both are tested without a project.
 *
 * A review is read against the source text its key holds now in [referenceLocale], so that a value
 * decided on an older source shows as [ShownReview.SOURCE_CHANGED]. The source is passed in by the
 * caller, read from the row whether its column is shown or not; its hash is kept per key, so the
 * renderer does not hash it again for every cell it paints.
 */
class TableReviews(
    private val byLocale: Map<String, Map<ReviewKey, Review>>,
    /** The namespace a key spelled without one lives in: `Config.defaultNamespaces().first()`. */
    private val defaultNamespace: String,
    /** False for [OFF]: reviews are not on for the module. */
    val enabled: Boolean = true,
    /**
     * The module's reference locale, as `ReferenceLocale.of` picks it among the loaded locales —
     * the locale a decision records its source in. Null when none matches: no source is known then.
     */
    val referenceLocale: String? = null,
    /** The module's `Config.pluralSeparator`: how a legacy numeric plural form is spelled. */
    val pluralSeparator: String = Config().pluralSeparator,
    /**
     * The row of a key, among the rows loaded now: where a plural form the reference lacks finds its
     * `other` form ([ReviewSources]). Read at each call, so that it follows the rows of the day.
     */
    private val rowOf: (String) -> TranslationRow? = { null },
) {
    /** Per key, the source text last hashed and its [ReviewSource]: one hash per key and text. */
    private val sources = ConcurrentHashMap<String, Pair<String, ReviewSource>>()

    /** The review of [key] (spelled as the table spells it) in [locale], or null. */
    fun reviewOf(key: String, locale: String): Review? =
        if (!enabled) null else byLocale[locale]?.get(reviewKey(key))

    /** The review file's name for [key], spelled as the table spells it. */
    fun reviewKey(key: String): ReviewKey = ReviewKey.fromSpelling(key, defaultNamespace)

    /**
     * What the review of [key] in [locale] means for [raw], the cell's value — an empty cell has no
     * entry, hence no value anyone reviewed (see [shown]) — given [source], the text [key] holds now
     * in [referenceLocale]. The reference locale itself has no source: it is never
     * [ShownReview.SOURCE_CHANGED].
     */
    fun shownOf(key: String, locale: String, raw: String?, source: String? = null): ShownReview {
        val review = reviewOf(key, locale) ?: return ShownReview.NONE
        val current = if (locale == referenceLocale) null else sourceOf(key, source)
        return shown(review, raw?.takeIf { it.isNotEmpty() }, current)
    }

    /** What the review of [row]'s key in [locale] means for its value, against its own source. */
    fun shownOf(row: TranslationRow, locale: String): ShownReview =
        shownOf(row.key, locale, row.values[locale], sourceText(row))

    /**
     * The text [row] is translated from in [referenceLocale], whatever locales are shown: its own, or
     * its plural group's `other` form's when the reference lacks its category ([ReviewSources]); null
     * when it has none.
     */
    fun sourceText(row: TranslationRow?): String? {
        val locale = referenceLocale ?: return null
        row ?: return null
        return ReviewSources.sourceTextOf(row.key, pluralSeparator) { key ->
            (if (key == row.key) row else rowOf(key))?.values?.get(locale)
        }
    }

    /**
     * [text], the source [key] holds now, as a [ReviewSource] in [referenceLocale]; null when either
     * is unknown or [text] is empty. Hashed once per key and text: an edit of the source is seen.
     */
    fun sourceOf(key: String, text: String?): ReviewSource? {
        val locale = referenceLocale ?: return null
        if (!enabled || text.isNullOrEmpty()) return null
        sources[key]?.let { (hashed, source) -> if (hashed == text) return source }
        return ReviewSource.of(locale, text).also { sources[key] = text to it }
    }

    companion object {
        val OFF = TableReviews(emptyMap(), "", enabled = false)

        /** What the *To review* filter keeps: no approval holds for the value shown, and no request is pending. */
        val TO_REVIEW_STATES = setOf(
            ShownReview.TO_REVIEW, ShownReview.CHANGED_SINCE_APPROVAL, ShownReview.FIXED_SINCE_REQUEST, ShownReview.SOURCE_CHANGED,
        )
    }
}
