package com.ibrahimdans.i18n.plugin.review

import com.ibrahimdans.i18n.plugin.ide.toolwindow.KeySpelling

/**
 * The review of translations: for one key and one locale, whether its value was reviewed, by whom,
 * and the thread of what was said about it.
 *
 * Nothing here touches the IDE: the model and [ReviewCodec] are meant to move to a portable core as
 * they are. [ReviewStore] reads and writes them in `<module root>/.i18n-reviews/<locale>.json`.
 */

/** The decision stored for a key and a locale — the last one made wins, the thread keeps the others. */
enum class ReviewState(val id: String) {
    /** Someone has to review the value: machine translation, *Internationalize…*, *Mark for review*. */
    TO_REVIEW("to_review"),

    /** The value [Review.hash] names was reviewed and is right. */
    APPROVED("approved"),

    /** The value [Review.hash] names is wrong; the thread says why. */
    CHANGES_REQUESTED("changes_requested");

    companion object {
        fun of(id: String?): ReviewState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a review means for the value a key holds now — see [shown]. Three of these are never stored:
 * they are computed from the hashes, so that an edit made anywhere, even outside the IDE, is seen.
 */
enum class ShownReview {
    /** No review, or a review whose key or value is gone (an orphan, hidden). */
    NONE,
    TO_REVIEW,

    /** Approved, but the value changed since: the approval no longer holds. */
    CHANGED_SINCE_APPROVAL,

    /** Changes were requested, and the value changed since: it is to be reviewed again. */
    FIXED_SINCE_REQUEST,

    /** The value is the one decided on, but its source text changed since: the decision no longer holds. */
    SOURCE_CHANGED,
    APPROVED,
    CHANGES_REQUESTED
}

/**
 * A key as a review file names it: its namespace always explicit (`translation:menu.home`), where
 * the tool window's spelling ([KeySpelling]) leaves the default namespace out. A change of the
 * default namespace would otherwise orphan every review of it.
 */
data class ReviewKey(val namespace: String, val path: String) {

    /** `namespace:path`, as the review file writes it. */
    val stored: String get() = namespace + KeySpelling.NAMESPACE_SEPARATOR + path

    /** This key in [KeySpelling]: without its namespace when it is one of [defaultNamespaces]. */
    fun spelling(defaultNamespaces: Collection<String>): String = if (namespace in defaultNamespaces) path else stored

    companion object {
        /** Orders keys as the review file lists them. */
        val ORDER: Comparator<ReviewKey> = compareBy { it.stored }

        /**
         * The key the tool window spells [key]: a key without namespace lives in [defaultNamespace]
         * (`Config.defaultNamespaces().first()`, the namespace the loader reads it from). A package
         * namespace (`courier::messages:welcome`) is read whole, as [KeySpelling.namespaceOf] does.
         */
        fun fromSpelling(key: String, defaultNamespace: String): ReviewKey =
            KeySpelling.namespaceOf(key)?.let { ReviewKey(it, KeySpelling.pathOf(key)) } ?: ReviewKey(defaultNamespace, key)

        /** The key a review file stores as [stored], or null when it names no namespace. */
        fun parse(stored: String): ReviewKey? =
            KeySpelling.namespaceOf(stored)?.let { ReviewKey(it, KeySpelling.pathOf(stored)) }
    }
}

/** What an entry of a thread records. */
enum class ReviewEventKind(val id: String) {
    COMMENT("comment"),
    TO_REVIEW("to_review"),
    APPROVE("approve"),
    REQUEST_CHANGE("request_change");

    companion object {
        /** An unknown kind — written by a newer version — reads as a plain comment. */
        fun of(id: String?): ReviewEventKind = entries.firstOrNull { it.id == id } ?: COMMENT

        fun of(state: ReviewState): ReviewEventKind = when (state) {
            ReviewState.TO_REVIEW -> TO_REVIEW
            ReviewState.APPROVED -> APPROVE
            ReviewState.CHANGES_REQUESTED -> REQUEST_CHANGE
        }
    }
}

/** One entry of a thread: flat, no replies, nothing to resolve — a later decision supersedes it. */
data class ReviewEvent(
    val id: String,
    val kind: ReviewEventKind,
    val by: String,
    val at: String,
    val text: String = ""
)

/**
 * The source text a value was decided on: the same key in the module's reference [locale], [hash]
 * its [ReviewHash]. Only the hash, as for the value: the review file is no second copy of the translations.
 */
data class ReviewSource(val locale: String, val hash: String) {
    companion object {
        fun of(locale: String, text: String): ReviewSource = ReviewSource(locale, ReviewHash.of(text))
    }
}

/**
 * The review of one key in one locale. [state], [hash], [by] and [at] are the last decision, [hash]
 * the value it was made on ([ReviewHash]), [source] the source text it was made against — null when
 * unknown: a review older than the field, the reference locale itself, a plural form without source.
 * [origin] says where the value came from: `machine:<engine>`, `i18nize` or `manual`.
 *
 * [extra] holds the properties of the entry this version does not know — a later one wrote them —
 * as raw JSON by name: [ReviewCodec] writes them back as they were read, so that a field added by a
 * later version is not lost when this one rewrites the file.
 */
data class Review(
    val state: ReviewState,
    val hash: String,
    val by: String,
    val at: String,
    val origin: String = ORIGIN_MANUAL,
    val thread: List<ReviewEvent> = emptyList(),
    val source: ReviewSource? = null,
    val extra: Map<String, String> = emptyMap()
) {
    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_I18NIZE = "i18nize"

        /** The origin of a value written by the machine translation engine [engine]. */
        fun machineOrigin(engine: String): String = "machine:$engine"
    }
}

/**
 * What [review] means for the value the key holds now, [currentValue] — null when the key is gone —
 * given the source text the key holds now in the module's reference locale, [currentSource] — null
 * when it has none.
 *
 * | Stored              | Same value, same or unknown source | Same value, other source | Other value            |
 * |---------------------|------------------------------------|--------------------------|------------------------|
 * | `approved`          | APPROVED                           | SOURCE_CHANGED           | CHANGED_SINCE_APPROVAL |
 * | `changes_requested` | CHANGES_REQUESTED                  | SOURCE_CHANGED           | FIXED_SINCE_REQUEST    |
 * | `to_review`         | TO_REVIEW                          | SOURCE_CHANGED           | TO_REVIEW              |
 * | none, or no value   | NONE                               | NONE                     | NONE                   |
 *
 * The source is unknown — never [ShownReview.SOURCE_CHANGED] — when the review holds none, when the
 * key has none now, or when the review's was taken in another locale: the module's reference changed
 * since, and every review would otherwise turn stale at once. A value edited since the decision is to
 * review anyway, and its next decision records the source of the day.
 */
fun shown(review: Review?, currentValue: String?, currentSource: ReviewSource? = null): ShownReview {
    if (review == null || currentValue == null) return ShownReview.NONE
    val same = review.hash == ReviewHash.of(currentValue)
    if (same && isStale(review.source, currentSource)) return ShownReview.SOURCE_CHANGED
    return when (review.state) {
        ReviewState.TO_REVIEW -> ShownReview.TO_REVIEW
        ReviewState.APPROVED -> if (same) ShownReview.APPROVED else ShownReview.CHANGED_SINCE_APPROVAL
        ReviewState.CHANGES_REQUESTED -> if (same) ShownReview.CHANGES_REQUESTED else ShownReview.FIXED_SINCE_REQUEST
    }
}

/** True when both sources are known, taken in the same locale, and the text changed in between. */
private fun isStale(decidedOn: ReviewSource?, current: ReviewSource?): Boolean =
    decidedOn != null && current != null && decidedOn.locale == current.locale && decidedOn.hash != current.hash

/**
 * The edits a review file goes through, on the reviews of one locale: pure, the author [by] and the
 * time [at] (ISO-8601) given by the caller. Each one adds an entry to the thread of the key it touches.
 *
 * A decision records the source it was made against; without one — null, or a key missing from
 * `sources` — the key keeps the source it had. The [Review.extra] properties of an entry are kept.
 */
object ReviewEdits {

    /**
     * [values] (key → value written) marked to review, with [origin] — what machine translation does —
     * and, in [sources], the source each value was translated from.
     */
    fun markToReview(
        reviews: Map<ReviewKey, Review>, values: Map<ReviewKey, String>, origin: String, by: String, at: String,
        sources: Map<ReviewKey, ReviewSource> = emptyMap()
    ): Map<ReviewKey, Review> = reviews + values.mapValues { (key, value) ->
        val previous = reviews[key]
        Review(
            ReviewState.TO_REVIEW, ReviewHash.of(value), by, at, origin,
            previous?.thread.orEmpty().appended(ReviewEventKind.TO_REVIEW, by, at, ""),
            sources[key] ?: previous?.source, previous?.extra.orEmpty()
        )
    }

    /**
     * [key]'s decision set to [state] on [value] and [source], [text] added to the thread. Requesting
     * changes without saying which is refused: the translator would have nothing to act on.
     */
    fun decide(
        reviews: Map<ReviewKey, Review>, key: ReviewKey, state: ReviewState, value: String,
        by: String, at: String, text: String = "", source: ReviewSource? = null
    ): Map<ReviewKey, Review> {
        require(state != ReviewState.CHANGES_REQUESTED || text.isNotBlank()) { "a change request needs a comment" }
        val previous = reviews[key]
        return reviews + (key to Review(
            state, ReviewHash.of(value), by, at, previous?.origin ?: Review.ORIGIN_MANUAL,
            previous?.thread.orEmpty().appended(ReviewEventKind.of(state), by, at, text),
            source ?: previous?.source, previous?.extra.orEmpty()
        ))
    }

    /**
     * [text] added to [key]'s thread, its decision unchanged — and so the source it was made against:
     * recording [source] on it would hide a source changed since. A key never reviewed is marked to
     * review on [value] and [source]: a comment is a call to look at it.
     */
    fun comment(
        reviews: Map<ReviewKey, Review>, key: ReviewKey, value: String, by: String, at: String, text: String,
        source: ReviewSource? = null
    ): Map<ReviewKey, Review> {
        require(text.isNotBlank()) { "a comment needs a text" }
        val previous = reviews[key]
            ?: Review(ReviewState.TO_REVIEW, ReviewHash.of(value), by, at, Review.ORIGIN_MANUAL, source = source)
        return reviews + (key to previous.copy(thread = previous.thread.appended(ReviewEventKind.COMMENT, by, at, text)))
    }

    /** The thread with one more entry, its id one above the highest `r<n>` of the thread. */
    private fun List<ReviewEvent>.appended(kind: ReviewEventKind, by: String, at: String, text: String): List<ReviewEvent> {
        val next = (mapNotNull { it.id.removePrefix(ID_PREFIX).toIntOrNull() }.maxOrNull() ?: 0) + 1
        return this + ReviewEvent(ID_PREFIX + next, kind, by, at, text)
    }

    private const val ID_PREFIX = "r"
}
