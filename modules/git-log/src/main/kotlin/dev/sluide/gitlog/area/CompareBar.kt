package dev.sluide.gitlog.area

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.gitlog.data.ComparisonSide
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.LogComparison
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableStringValue
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/**
 * The bar of a comparison of a ref with `HEAD` (ADR 0044), below the filter bar: what is compared, the two sides to
 * list with their counts, and a button that stops comparing; hidden while not comparing. It starts and ends the
 * comparison of the [pages] ([compareWith], [stopComparing]); [comparisonChanged] brings the bar up to date after
 * anything else did. Starting a comparison clears the header through [clearStatus].
 */
internal class CompareBar(
    private val pages: GitLogPages,
    private val clearStatus: () -> Unit,
) : HBox() {
    /** Called with whether a comparison is shown now, as the branch filter is disabled while comparing. */
    var comparingShown: (Boolean) -> Unit = {}

    private val emptySideText = SimpleStringProperty("")

    /**
     * What the table says while comparing (ADR 0044) and its side has no commits, without other filters set; empty
     * otherwise.
     */
    val emptySide: ObservableStringValue = emptySideText

    /** Says what is compared with what (ADR 0044); shrinks first in a narrow log, its tooltip has the whole text. */
    internal val compareLabel =
        Label().apply {
            styleClass += "git-log-compare-text"
            minWidth = 0.0
            tooltip = Tooltip()
        }

    private val compareSides = ToggleGroup()

    /** Lists the commits of the compared ref that are not in `HEAD`. */
    internal val refOnlyToggle = compareSide(ComparisonSide.REF_ONLY)

    /** Lists the commits of `HEAD` that are not in the compared ref. */
    internal val headOnlyToggle = compareSide(ComparisonSide.HEAD_ONLY)

    internal val stopCompareButton =
        Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
            styleClass += "git-log-compare-close"
            tooltip = Tooltip("Stop Comparing")
            minWidth = USE_PREF_SIZE
            setOnAction { stopComparing() }
        }

    /** Set while the side toggles are set to the comparison's side, which is no choice of the user. */
    private var showingSide = false

    init {
        children.addAll(
            compareLabel,
            refOnlyToggle,
            headOnlyToggle,
            Region().apply { setHgrow(this, Priority.ALWAYS) },
            stopCompareButton,
        )
        styleClass += "git-log-compare"
        alignment = Pos.CENTER_LEFT
        minWidth = 0.0
        isVisible = false
        isManaged = false
        compareSides.selectedToggleProperty().subscribe { old, new ->
            when {
                showingSide -> {}

                // Exactly one side is listed: a click on the selected one keeps it.
                new == null -> {
                    old?.isSelected = true
                }

                else -> {
                    pages.comparison = pages.comparison?.copy(side = new.userData as ComparisonSide)
                    update()
                }
            }
        }
    }

    /**
     * Lists the commits of [ref] that are not in `HEAD` (ADR 0044), with the bar above the table; the filters stay, the
     * branch filter set aside until the comparison ends. Not without a `HEAD` commit.
     */
    fun compareWith(ref: GitRef) {
        if (pages.refs
                ?.refs
                .orEmpty()
                .none { it.kind == GitRefKind.HEAD }
        ) {
            return
        }
        clearStatus()
        pages.comparison = LogComparison(ref)
        comparisonChanged()
    }

    /** Ends the comparison: the log shows what the filters say again. */
    fun stopComparing() {
        if (pages.comparison == null) return
        pages.comparison = null
        comparisonChanged()
    }

    /** Shows the bar of the comparison, or hides it. */
    fun comparisonChanged() {
        val comparison = pages.comparison
        isVisible = comparison != null
        isManaged = comparison != null
        comparingShown(comparison != null)
        showingSide = true
        try {
            compareSides.selectToggle(
                when (comparison?.side) {
                    ComparisonSide.REF_ONLY -> refOnlyToggle
                    ComparisonSide.HEAD_ONLY -> headOnlyToggle
                    null -> null
                },
            )
        } finally {
            showingSide = false
        }
        update()
    }

    /** The texts of the comparison's bar and empty table, with `HEAD`'s branch and the counts read last. */
    fun update() {
        val comparison = pages.comparison
        if (comparison == null) {
            emptySideText.set("")
            return
        }
        val name = comparison.ref.shortName
        val current = pages.refs?.currentBranch?.removePrefix("refs/heads/") ?: "HEAD"
        val counts = pages.counts
        compareLabel.text = "Comparing $name with $current"
        compareLabel.tooltip.text = compareLabel.text
        refOnlyToggle.text = "In $name, not in $current" + counts?.let { " (${it.refOnly})" }.orEmpty()
        headOnlyToggle.text = "In $current, not in $name" + counts?.let { " (${it.headOnly})" }.orEmpty()
        // Other filters may be why nothing is listed.
        val unfiltered = pages.filter.copy(revisions = emptyList()).isDefault
        emptySideText.set(
            when {
                !unfiltered -> ""
                comparison.side == ComparisonSide.REF_ONLY -> "No commits in $name that are not in $current."
                else -> "No commits in $current that are not in $name."
            },
        )
    }

    private fun compareSide(side: ComparisonSide) =
        ToggleButton().apply {
            styleClass += "git-log-compare-side"
            toggleGroup = compareSides
            userData = side
            minWidth = USE_PREF_SIZE
        }
}
