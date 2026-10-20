package dev.sluide.fileviewers.preview

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

    /** What a newly opened view shows. */
    val defaultMode: PreviewMode get() = PreviewMode.SPLIT

    /**
     * Whether the page is a zoomable image or diagram (`sluide.setZoom`): the view then has zoom controls
     * ([ZoomControls]) while it shows the preview.
     */
    val zooms: Boolean get() = false

    /** Whether the preview has headings ([outline]), so that the view has a table of contents ([TableOfContents]). */
    val hasOutline: Boolean get() = false

    /**
     * The items of the preview's Export menu (empty: no menu); the Export button sits beside the table of contents'
     * button or the zoom controls, so only previews with an [outline][hasOutline] or that [zoom][zooms] show it.
     */
    val exports: List<FileExport> get() = emptyList()

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
