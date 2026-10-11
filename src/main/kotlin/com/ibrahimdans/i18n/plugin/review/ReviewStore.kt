package com.ibrahimdans.i18n.plugin.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * The review files of the project, `<root>/.i18n-reviews/<locale>.json` — see [ReviewLocation] for
 * where `root` is, [ReviewCodec] for the format.
 *
 * Reads are cached per file and served again while its modification stamp — the document's when the
 * file is open or was just written, the file's otherwise — has not moved. A change on disk (`git pull`,
 * a merge) drops the cache and publishes [CHANGED], as does every write made here.
 *
 * Writes go through the file's `Document`, never `java.io`: made inside the caller's
 * `WriteCommandAction`, they belong to its command, so that one Ctrl+Z undoes the values a machine
 * translation wrote *and* the marks it left. A command touching translation files too should use
 * `withGlobalUndo()` and `UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION`, as `I18nizeApply` does.
 * The document is left for the IDE to save, like every other edit.
 *
 * Nothing is ever purged here: a key whose translation is gone keeps its review (an orphan, which
 * [shown] hides) — a translation load coming back empty must not wipe every review.
 */
@Service(Service.Level.PROJECT)
class ReviewStore(private val project: Project) : Disposable {

    /** Told when a review file changed, by a write of the store or on disk. */
    fun interface Listener {
        fun reviewsChanged()
    }

    private data class Cached(val stamp: Long, val fromDocument: Boolean, val decoded: ReviewCodec.Decoded)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** The clock reviews are dated with; replaced by tests. */
    internal var now: () -> Instant = { Instant.now() }

    /** The name reviews are signed with for a root; replaced by tests. */
    internal var reviewer: (VirtualFile) -> String = { root -> ReviewerIdentity.current(project, root) }

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val touched = events.filter { ReviewLocation.isReviewPath(it.path) }
                if (touched.isEmpty()) return
                touched.forEach { event -> cache.keys.removeIf { it.startsWith(event.path) } }
                publish()
            }
        })
    }

    /** True when reviews are on under [root]: its `.i18n-reviews` folder exists. */
    fun isEnabled(root: VirtualFile): Boolean = ReviewLocation.directoryOf(root) != null

    /**
     * The reviews of [locale] under [root], by key. Empty — never an exception — when reviews are off,
     * the file is absent, empty or unreadable.
     */
    fun reviews(root: VirtualFile, locale: String): Map<ReviewKey, Review> = decoded(root, locale).reviews

    /**
     * Turns reviews on under [root] by creating its `.i18n-reviews` folder, and returns it. Only on a
     * person's request: the folder is versioned and switches reviews on for the whole team.
     * In a write action.
     */
    fun enable(root: VirtualFile): VirtualFile {
        ApplicationManager.getApplication().assertWriteAccessAllowed()
        return ReviewLocation.directoryOf(root) ?: root.createChildDirectory(this, ReviewLocation.DIRECTORY)
    }

    /**
     * Rewrites [locale]'s reviews under [root] with [edit], creating the locale's file if needed.
     *
     * In a write action, inside the caller's command for its undo. Writes nothing and returns false
     * when reviews are off under [root] (no folder: see [enable]) or when the file is there but cannot
     * be read — a merge conflict left in it — rather than overwrite every review it holds.
     */
    fun update(root: VirtualFile, locale: String, edit: (Map<ReviewKey, Review>) -> Map<ReviewKey, Review>): Boolean {
        ApplicationManager.getApplication().assertWriteAccessAllowed()
        val directory = ReviewLocation.directoryOf(root) ?: return false
        val current = decoded(root, locale)
        if (!current.readable) return false
        val updated = edit(current.reviews)
        if (updated == current.reviews) return true
        try {
            val file = directory.findChild(ReviewLocation.fileName(locale))
                ?: directory.createChildData(this, ReviewLocation.fileName(locale))
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
            val text = ReviewCodec.write(updated)
            if (document.text != text) document.setText(text)
            cache.remove(file.path)
        } catch (_: IOException) {
            return false
        }
        publish()
        return true
    }

    /** [values] (key → value just written) marked to review, from [origin]. See [ReviewEdits.markToReview]. */
    fun mark(root: VirtualFile, locale: String, values: Map<ReviewKey, String>, origin: String): Boolean {
        if (values.isEmpty()) return true
        return update(root, locale) { ReviewEdits.markToReview(it, values, origin, reviewer(root), timestamp()) }
    }

    /** [key]'s decision set to [state] on [value]; [text] is required for a change request. See [ReviewEdits.decide]. */
    fun decide(root: VirtualFile, locale: String, key: ReviewKey, state: ReviewState, value: String, text: String = ""): Boolean =
        update(root, locale) { ReviewEdits.decide(it, key, state, value, reviewer(root), timestamp(), text) }

    /** [text] added to [key]'s thread. See [ReviewEdits.comment]. */
    fun comment(root: VirtualFile, locale: String, key: ReviewKey, value: String, text: String): Boolean =
        update(root, locale) { ReviewEdits.comment(it, key, value, reviewer(root), timestamp(), text) }

    override fun dispose() {
        cache.clear()
    }

    private fun timestamp(): String = now().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(CHANGED).reviewsChanged()
    }

    private fun decoded(root: VirtualFile, locale: String): ReviewCodec.Decoded = ReadAction.compute<ReviewCodec.Decoded, RuntimeException> {
        val file = ReviewLocation.directoryOf(root)?.findChild(ReviewLocation.fileName(locale))
        if (file == null || !file.isValid || file.isDirectory) return@compute EMPTY
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        cache[file.path]?.takeIf { it.stamp == stamp && it.fromDocument == (document != null) }?.let { return@compute it.decoded }
        val text = try {
            document?.text ?: VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            return@compute UNREADABLE
        }
        ReviewCodec.decode(text).also { cache[file.path] = Cached(stamp, document != null, it) }
    }

    companion object {
        @JvmField
        val CHANGED: Topic<Listener> = Topic.create("i18n reviews changed", Listener::class.java)

        private val EMPTY = ReviewCodec.Decoded(emptyMap(), readable = true)
        private val UNREADABLE = ReviewCodec.Decoded(emptyMap(), readable = false)

        fun getInstance(project: Project): ReviewStore = project.getService(ReviewStore::class.java)
    }
}
