package dev.sluide.editorarea

/** What a dragged tab header can be dropped on besides its own row (the other side's row, the right half of the area). */
internal interface TabDropTargets {
    /**
     * Shows where [tab], dragged from [from], would go at the scene point; true if it is over a target (then the
     * source row does not reorder).
     */
    fun hover(
        tab: EditorTab,
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ): Boolean

    /** Moves [tab] there if the point is over a target; true if it moved. */
    fun drop(
        tab: EditorTab,
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ): Boolean

    /** Hides any marker or zone. */
    fun clear()
}

/** No targets: a header can only be dragged within its row. */
internal object NoDropTargets : TabDropTargets {
    override fun hover(
        tab: EditorTab,
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ) = false

    override fun drop(
        tab: EditorTab,
        from: Side,
        sceneX: Double,
        sceneY: Double,
    ) = false

    override fun clear() = Unit
}
