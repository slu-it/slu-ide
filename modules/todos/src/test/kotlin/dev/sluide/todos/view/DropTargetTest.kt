package dev.sluide.todos.view

import dev.sluide.todos.TodoPlace
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The list: root card a (0..20), expanded folder F (26..100: header 26..46, cards f1 50..70 and f2 74..94, padding
 * below), root card b (106..126), collapsed folder C (132..152, header only, holding c1), expanded empty folder E
 * (158..190: header 158..178, hint 182..190). The root items are a, F, b, C, E.
 */
class DropTargetTest {
    private val rows =
        listOf(
            DropRow(RowKind.ROOT_CARD, "a", null, 0.0, 20.0),
            DropRow(RowKind.FOLDER_HEADER, "F", "F", 26.0, 46.0),
            DropRow(RowKind.FOLDER_CARD, "f1", "F", 50.0, 70.0),
            DropRow(RowKind.FOLDER_CARD, "f2", "F", 74.0, 94.0),
            DropRow(RowKind.ROOT_CARD, "b", null, 106.0, 126.0),
            DropRow(RowKind.FOLDER_HEADER, "C", "C", 132.0, 152.0, collapsed = true),
            DropRow(RowKind.FOLDER_HEADER, "E", "E", 158.0, 178.0),
            DropRow(RowKind.FOLDER_EMPTY, "E", "E", 182.0, 190.0),
        )

    private val folders =
        listOf(FolderBounds("F", 26.0, 100.0), FolderBounds("C", 132.0, 152.0), FolderBounds("E", 158.0, 190.0))

    private fun target(
        dragged: String,
        isFolder: Boolean,
        y: Double,
    ) = dropTarget(rows, folders, dragged, isFolder, y)

    private fun describe(indicator: DropIndicator) =
        when (indicator) {
            is DropIndicator.Line -> "line ${indicator.y} ${indicator.folderId ?: "root"}"
            is DropIndicator.Highlight -> "highlight ${indicator.folderId} ${if (indicator.hint) "hint" else "header"}"
        }

    private fun assertDrop(
        result: DropResult?,
        folder: String?,
        index: Int,
        indicator: String,
    ) {
        assertEquals(TodoPlace(folder, index), result?.place)
        assertEquals(indicator, result?.indicator?.let(::describe))
    }

    @ParameterizedTest(name = "{0} at y {1}: {5}")
    @CsvSource(
        "f2, 5, , 0, line 0.0 root, root card upper half: before it",
        "f2, 15, , 1, line 23.0 root, root card lower half: after it",
        "f2, 28, , 1, line 23.0 root, expanded header at 10 %: before the folder",
        "b, 36, F, 0, line 48.0 F, expanded header at 50 %: the folder's top",
        "f2, 134, , 3, line 129.0 root, collapsed header at 10 %: before the folder",
        "f2, 142, C, 0, highlight C header, collapsed header at 50 %: the folder's top",
        "f2, 150, , 4, line 155.0 root, collapsed header at 90 %: after the folder",
        "b, 77, F, 1, line 72.0 F, card in a folder upper half: before it",
        "b, 90, F, 2, line 94.0 F, card in a folder lower half: after it",
        "b, 48, F, 0, line 48.0 F, folder gap between header and first card: the folder's top",
        "b, 97, F, 2, line 94.0 F, folder padding below the last card: the folder's end",
        "b, 185, E, 0, highlight E hint, the empty hint: into the folder",
        "b, 168, E, 0, highlight E hint, empty folder header at 50 %: into the folder",
        "b, 180, E, 0, highlight E hint, empty folder gap above the hint: into the folder",
        "f2, 23, , 1, line 23.0 root, gap after the first root item",
        "f2, 103, , 2, line 103.0 root, gap between a folder and a card",
        "f2, -5, , 0, line 0.0 root, above the first item",
        "f2, 300, , 5, line 190.0 root, below the last item",
        "a, 120, , 2, line 129.0 root, down within the root: index after removal",
        "a, 300, , 4, line 190.0 root, down to the root end: index after removal",
        "f1, 90, F, 1, line 94.0 F, down within a folder: index after removal",
        "f1, 15, , 1, line 23.0 root, out of a folder to the root",
    )
    fun `a dragged TODO lands by the row under the pointer`(
        dragged: String,
        y: Double,
        folder: String?,
        index: Int,
        indicator: String,
    ) = assertDrop(target(dragged, false, y), folder, index, indicator)

    @ParameterizedTest(name = "{0} at y {1}: {2}")
    @CsvSource(
        "b, 110, upper half of itself",
        "b, 120, lower half of itself",
        "b, 103, gap before itself",
        "b, 129, gap after itself",
        "f1, 55, upper half of itself in its folder",
        "f1, 36, its folder's top, where it is",
        "f2, 97, its folder's end, where it is",
    )
    fun `a TODO dropped on its own place is no move`(
        dragged: String,
        y: Double,
    ) = assertNull(target(dragged, false, y))

    @ParameterizedTest(name = "{0} at y {1}: {5}")
    @CsvSource(
        "F, 5, 0, line 0.0 root, above the centre of the first item",
        "C, 55, 1, line 23.0 root, over the upper cards of another folder: before it",
        "C, 80, 2, line 103.0 root, over the lower cards of another folder: after it",
        "F, 168, 3, line 155.0 root, over an empty folder's header: never into it",
        "C, 185, 4, line 190.0 root, over an empty folder's hint: never into it",
        "E, 140, 3, line 129.0 root, over a collapsed folder's header: never into it",
        "F, 300, 4, line 190.0 root, below the last item: index after removal",
    )
    fun `a dragged folder lands between root items by their centres`(
        dragged: String,
        y: Double,
        index: Int,
        indicator: String,
    ) = assertDrop(target(dragged, true, y), null, index, indicator)

    @ParameterizedTest(name = "{0} at y {1}")
    @CsvSource("F, 30", "F, 80", "C, 130", "C, 145")
    fun `a folder dropped on its own place is no move`(
        dragged: String,
        y: Double,
    ) = assertNull(target(dragged, true, y))
}
