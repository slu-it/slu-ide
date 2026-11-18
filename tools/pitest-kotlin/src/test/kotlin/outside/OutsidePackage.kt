package outside

/** A class of ours outside `dev.sluide`, as `javafx.scene.input.TestDragboards` in the barcodes tests. */
class OutsidePackage {
    fun twice(value: Int): Int = if (value > 0) value * 2 else 0
}
