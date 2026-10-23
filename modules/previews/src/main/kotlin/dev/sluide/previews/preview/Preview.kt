package dev.sluide.previews.preview

import dev.sluide.fileviewers.FileViewHost
import javafx.scene.web.WebEngine
import java.io.IOException
import java.nio.file.Path

/** How a [PreviewFileView] renders a file. */
internal interface Preview {
    /**
     * Shows [text], the content of [file], in [engine]; the page is to use dark colours if [dark] and may use the files
     * in [root] (see [root]).
     */
    @Throws(IOException::class)
    fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    )

    /**
     * Called once by the view showing this preview, on the FX thread, before the first [load]: [rerender] makes the
     * view render again (it calls [load] again with the current saved text, even if unchanged), [host] gives the
     * view's current host (null until set, e.g. in tests). A preview that computes its page in the background keeps
     * them and calls [rerender] on the FX thread when done. Only previews created per view
     * ([PreviewFileViewer.newPreview]) may keep them.
     *
     * [rerender] does nothing while the view is stopped (it calls [load] again when it resumes) and after [detach], so
     * a background preview caches its result for the next [load].
     */
    fun attach(
        rerender: () -> Unit,
        host: () -> FileViewHost?,
    ) {}

    /**
     * Called once when the view is disposed, on the FX thread; the preview drops what [attach] gave it and stops its
     * work.
     */
    fun detach() {}

    /** Whether the page has `sluide.scrollToLine` and blocks with `data-line`, so it can scroll with the source. */
    val syncsScroll: Boolean get() = false

    /**
     * Whether the page brings our script (`preview.js`): it follows the colour scheme (`sluide.setDark`) and hands
     * link clicks to the view.
     */
    val hasOwnScript: Boolean get() = true

    /** Whether [load] uses its `root`, so that the page has to be loaded again when the root changes. */
    val usesRoot: Boolean get() = false

    /** The folder whose files the page for [file] may use, given the [projectFolder] the view belongs to. */
    fun root(
        file: Path,
        projectFolder: Path?,
    ): Path = projectFolder?.takeIf { file.startsWith(it) } ?: file.parent

    /** Whether the page's own scripts may run when the user allows it (a toolbar toggle, off by default). */
    val offersScripts: Boolean get() = false

    /**
     * Whether the preview at [location] still shows the page for [file]; anything else is a navigation away, which
     * the view turns back. Pages loaded from a string have no location.
     */
    fun isOwnPage(
        location: String?,
        file: Path,
    ): Boolean = isPageFromString(location)

    /** Where [href], a link clicked or followed in the page for [file], leads. */
    fun linkTarget(
        href: String,
        file: Path,
    ): LinkTarget? = resolveLink(href, file.parent)

    /**
     * Called on the FX thread when a linked node [id] of the page's diagram is clicked (the ids the page lists in
     * `data-node-links` on its `pre.mermaid`, `preview.js`); the preview opens what the node stands for through [host].
     * By default nothing.
     */
    fun openNode(
        id: String,
        host: FileViewHost,
    ) {}

    /** What a newly opened view shows. */
    val defaultMode: PreviewMode get() = PreviewMode.SPLIT

    /**
     * Whether the page is a zoomable image or diagram (`sluide.setZoom`): the view then has zoom controls
     * ([ZoomControls]) while it shows the preview.
     */
    val zooms: Boolean get() = false

    /**
     * Whether the page has foldable `<details>` elements and `sluide.setAllOpen`: the view then has Collapse All and
     * Expand All buttons ([FoldControls]) while it shows the preview.
     */
    val folds: Boolean get() = false

    /**
     * Whether the preview can put its text formatted on the clipboard ([formatted]): the view then has a Copy Formatted
     * button ([CopyFormattedControls]) after the fold buttons while it shows the preview.
     */
    val copiesFormatted: Boolean get() = false

    /** The formatted plain text of [text] to copy, or why there is none; only called if [copiesFormatted]. */
    fun formatted(text: String): Formatted = error("This preview does not copy its text formatted")

    /** Whether the preview has headings ([outline]), so that the view has a table of contents ([TableOfContents]). */
    val hasOutline: Boolean get() = false

    /**
     * The items of the preview's Export menu (empty: no menu); the Export button sits beside the table of contents'
     * button, the zoom controls or the fold controls, so only previews with an [outline][hasOutline] or that
     * [zoom][zooms] or [fold][folds] show it.
     */
    val exports: List<FileExport> get() = emptyList()

    /**
     * Buttons of the preview's own ([PreviewAction]), on the right of the toolbar before the mode toggles, while the
     * preview shows; asked once, when the view is created.
     */
    val actions: List<PreviewAction> get() = emptyList()

    /**
     * The headings of [text] for the table of contents, or `null` if the preview has none ([hasOutline]).
     */
    fun outline(text: String): List<Heading>? = null
}

/** Whether a `WebView` at [location] shows a page loaded from a string (`loadContent`), which has no location. */
internal fun isPageFromString(location: String?): Boolean = location.isNullOrEmpty() || location == "about:blank"

/** What a [PreviewFileView] shows. */
internal enum class PreviewMode(
    val label: String,
) {
    SOURCE("Source"),
    SPLIT("Split"),
    PREVIEW("Preview"),
}
