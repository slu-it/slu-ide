package dev.sluide.gitlog

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.Label

/**
 * The message in the area's header ([label]): the log's failure while rows are shown and the Log tab is ([logShown]),
 * else the last message [told][tell], e.g. why a diff cannot be shown, until the next selection, load or tab (ADR 0046),
 * else what the operation that changes the repository does or did ([operation]). It counts the changes of the
 * selected commits and files ([selection]), so that a message about an earlier one is not shown.
 */
internal class HeaderStatus(
    private val pages: GitLogPages,
) {
    /** A message, e.g. that a diff cannot be shown, until the next selection or load. */
    private val message = SimpleStringProperty("")

    /**
     * What the operation that changes the repository is doing ("Checking out main…") or did ("Checked out main."),
     * shown while there is no other [message]; until the next operation or another folder.
     */
    private val operationMessage = SimpleStringProperty("")

    /**
     * What the header says once the log loaded again, e.g. why a comparison ended: replacing the rows changes the
     * selection, which clears the header.
     */
    private var afterLoad: String? = null

    /** Counts the changes of the selected commits and files. */
    private var selections = 0L

    /** Whether the Log tab is selected (or there are no tabs), so that the header tells the log's failures. */
    private val logShown = SimpleBooleanProperty(true)

    /** The header's message. */
    internal val label =
        Label().apply {
            styleClass += "git-log-status"
            // A failure while rows are shown (with no rows the table's placeholder says it), else the message, else
            // what an operation does or did.
            textProperty().bind(
                Bindings.createStringBinding(
                    {
                        val state = pages.state.get()
                        val logFailed = state == GitLogState.FAILED && pages.rows.isNotEmpty()
                        when {
                            logShown.get() && logFailed -> state.message
                            message.get().isNotEmpty() -> message.get()
                            else -> operationMessage.get()
                        }
                    },
                    pages.state,
                    pages.rows,
                    message,
                    operationMessage,
                    logShown,
                ),
            )
            minWidth = 0.0
        }

    init {
        // The refs are read with every first page, before its state is set.
        pages.state.subscribe { state ->
            when (state) {
                GitLogState.IDLE -> {
                    afterLoad = null
                }

                GitLogState.LOADING -> {
                    clear()
                }

                else -> {
                    afterLoad?.let(::tell)
                    afterLoad = null
                }
            }
        }
    }

    /** Shows [text] until the next selection, load or tab; `null` clears it. */
    fun tell(text: String?) = message.set(text.orEmpty())

    /** Clears the message. */
    fun clear() = tell(null)

    /** Shows [text] once the log loaded again, e.g. why a comparison ended. */
    fun tellAfterLoad(text: String?) {
        afterLoad = text
    }

    /** Shows what the operation that changes the repository does or did; empty for nothing. */
    fun operation(text: String) = operationMessage.set(text)

    /** How many times the selected commits or files changed; a message about an earlier one is not shown. */
    fun selection() = selections

    /** The selected commits or files changed: counted, and the message cleared unless [keepMessage]. */
    fun selectionChanged(keepMessage: Boolean = false) {
        selections++
        if (!keepMessage) clear()
    }

    /** Whether the Log tab is shown, so that the header tells the log's failures. */
    fun logShown(shown: Boolean) = logShown.set(shown)
}
