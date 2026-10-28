package dev.sluide.todos.view

import dev.sluide.todos.TodoPlace

/** The part of a TODO list a [DropRow] stands for. */
internal enum class RowKind {
    /** A TODO card at the root. */
    ROOT_CARD,

    /** The header of a folder (all of a collapsed folder). */
    FOLDER_HEADER,

    /** A TODO card in an expanded folder. */
    FOLDER_CARD,

    /** The "Drag TODOs here" hint of an expanded, empty folder. */
    FOLDER_EMPTY,
}

/**
 * One row of the list as laid out when the drag starts, in the coordinates of the list's column: [id] is the TODO's
 * for cards and the folder's for headers and hints; [folderId] the folder the row belongs to, null at the root.
 */
internal data class DropRow(
    val kind: RowKind,
    val id: String,
    val folderId: String?,
    val top: Double,
    val bottom: Double,
    val collapsed: Boolean = false,
)

/** The whole of the folder [id] (header and body), in the same coordinates as the [DropRow]s. */
internal data class FolderBounds(
    val id: String,
    val top: Double,
    val bottom: Double,
)

/** How the list shows where the dragged item lands. */
internal sealed interface DropIndicator {
    /** An insertion line at [y], in the folder [folderId] (indented like its cards) or at the root (null). */
    data class Line(
        val y: Double,
        val folderId: String?,
    ) : DropIndicator

    /** The folder [folderId] is highlighted: its empty [hint], else its header (the top of a collapsed folder). */
    data class Highlight(
        val folderId: String,
        val hint: Boolean,
    ) : DropIndicator
}

/** Where the dragged item lands ([place], in the store's post-removal terms) and how that is shown. */
internal data class DropResult(
    val place: TodoPlace,
    val indicator: DropIndicator,
)

/** The part of a header (from its top and, for a collapsed folder, from its bottom) that drops next to the folder. */
private const val HEADER_EDGE = 0.25

/**
 * Where the item [dragged] (a folder if [draggedIsFolder], else a TODO) lands when the pointer is at [y], with the
 * list laid out as [rows] and [folders]; null if that is where it is now (no move).
 *
 * A TODO goes before a card if the pointer is on the card's upper half, after it on the lower half; before a folder on
 * the top quarter of its header, after a collapsed folder on the bottom quarter, else to the top of the folder; into an
 * empty folder on its hint; to the end of a folder below its last card; into the gap between root items it is in, or
 * before the first and after the last. A folder only lands between root items, by their centres.
 */
internal fun dropTarget(
    rows: List<DropRow>,
    folders: List<FolderBounds>,
    dragged: String,
    draggedIsFolder: Boolean,
    y: Double,
): DropResult? {
    val layout = DropLayout(rows, folders)
    val candidate = if (draggedIsFolder) layout.folderTarget(dragged, y) else layout.todoTarget(y)
    return layout.resolve(candidate, dragged, draggedIsFolder)
}

/** A drop place before removal: [position] counts all listed items of the container, the dragged one too. */
private data class Candidate(
    val folderId: String?,
    val position: Int,
    val indicator: DropIndicator? = null,
)

private data class Span(
    val id: String,
    val top: Double,
    val bottom: Double,
) {
    val center get() = (top + bottom) / 2
}

private class DropLayout(
    private val rows: List<DropRow>,
    private val folders: List<FolderBounds>,
) {
    /** The root cards and folders in display order. */
    val root: List<Span> =
        (
            rows.filter { it.kind == RowKind.ROOT_CARD }.map { Span(it.id, it.top, it.bottom) } +
                folders.map { Span(it.id, it.top, it.bottom) }
        ).sortedBy { it.top }

    fun cardsOf(folderId: String): List<Span> =
        rows
            .filter { it.kind == RowKind.FOLDER_CARD && it.folderId == folderId }
            .map { Span(it.id, it.top, it.bottom) }
            .sortedBy { it.top }

    private fun rootIndex(id: String) = root.indexOfFirst { it.id == id }

    private fun row(
        kind: RowKind,
        folderId: String,
    ) = rows.firstOrNull { it.kind == kind && it.folderId == folderId }

    fun folderTarget(
        dragged: String,
        y: Double,
    ): Candidate {
        val from = rootIndex(dragged)
        // By the pointer, not the dragged folder's centre: a tall expanded folder would otherwise have to travel far.
        val others = root.withIndex().count { (index, item) -> index != from && item.center < y }
        return Candidate(null, if (others < from) others else others + 1)
    }

    fun todoTarget(y: Double): Candidate {
        val row = rows.firstOrNull { y >= it.top && y < it.bottom }
        val upper = row != null && y < (row.top + row.bottom) / 2
        return when (row?.kind) {
            RowKind.ROOT_CARD -> {
                val index = rootIndex(row.id)
                Candidate(null, if (upper) index else index + 1)
            }

            RowKind.FOLDER_CARD -> {
                val folderId = requireNotNull(row.folderId)
                val index = cardsOf(folderId).indexOfFirst { it.id == row.id }
                Candidate(folderId, if (upper) index else index + 1)
            }

            RowKind.FOLDER_HEADER -> {
                header(row, y)
            }

            RowKind.FOLDER_EMPTY -> {
                Candidate(row.id, 0, DropIndicator.Highlight(row.id, true))
            }

            null -> {
                gap(y)
            }
        }
    }

    private fun header(
        row: DropRow,
        y: Double,
    ): Candidate {
        val share = (y - row.top) / (row.bottom - row.top)
        val index = rootIndex(row.id)
        return when {
            share < HEADER_EDGE -> Candidate(null, index)
            row.collapsed && share >= 1 - HEADER_EDGE -> Candidate(null, index + 1)
            row.collapsed -> Candidate(row.id, 0, DropIndicator.Highlight(row.id, false))
            else -> intoFolder(row.id, 0)
        }
    }

    /** Off the rows: in a folder's bounds the gap between its cards (or its end), else the gap between root items. */
    private fun gap(y: Double): Candidate {
        val folder = folders.firstOrNull { y >= it.top && y < it.bottom }
        if (folder != null) return intoFolder(folder.id, cardsOf(folder.id).count { it.bottom <= y })
        return Candidate(null, root.count { it.bottom <= y })
    }

    private fun intoFolder(
        folderId: String,
        position: Int,
    ): Candidate =
        if (row(RowKind.FOLDER_EMPTY, folderId) != null) {
            Candidate(folderId, 0, DropIndicator.Highlight(folderId, true))
        } else {
            Candidate(folderId, position)
        }

    /** Turns [candidate] into the store's post-removal place; null if the dragged item would stay where it is. */
    fun resolve(
        candidate: Candidate,
        dragged: String,
        draggedIsFolder: Boolean,
    ): DropResult? {
        val draggedRow = rows.firstOrNull { it.id == dragged && it.kind != RowKind.FOLDER_HEADER }
        val (from, index) =
            when {
                draggedIsFolder || draggedRow?.kind == RowKind.ROOT_CARD -> {
                    null to rootIndex(dragged)
                }

                draggedRow?.kind == RowKind.FOLDER_CARD -> {
                    draggedRow.folderId to
                        cardsOf(requireNotNull(draggedRow.folderId)).indexOfFirst { it.id == dragged }
                }

                else -> {
                    null to -1
                }
            }
        val same = index >= 0 && from == candidate.folderId
        val position = if (same && index < candidate.position) candidate.position - 1 else candidate.position
        if (same && position == index) return null
        val indicator = candidate.indicator ?: DropIndicator.Line(lineY(candidate), candidate.folderId)
        return DropResult(TodoPlace(candidate.folderId, position), indicator)
    }

    /** The y of the line before the item at [Candidate.position] of its container (before removal). */
    private fun lineY(candidate: Candidate): Double {
        val folderId = candidate.folderId
        val items = if (folderId == null) root else cardsOf(folderId)
        val position = candidate.position
        val above =
            when {
                position > 0 -> items[position - 1].bottom
                folderId != null -> row(RowKind.FOLDER_HEADER, folderId)?.bottom
                else -> null
            }
        val below = items.getOrNull(position)?.top
        return when {
            above != null && below != null -> (above + below) / 2
            else -> below ?: above ?: 0.0
        }
    }
}
