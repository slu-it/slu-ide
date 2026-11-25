package dev.sluide.fileviewers.zoom

private object ZoomResources

/**
 * The stylesheet of the zoom group (external form), shared by the previews and the PDF view. It styles the style
 * classes `zoom-controls` (the group), `zoom-button` and `zoom-level`, and goes onto the group node's own
 * `stylesheets`, as `ZoomControls` does, so that the toolbars of other modules need no stylesheet of theirs.
 */
val ZOOM_CONTROLS_STYLESHEET: String =
    checkNotNull(ZoomResources::class.java.getResource("zoom-controls.css")).toExternalForm()
