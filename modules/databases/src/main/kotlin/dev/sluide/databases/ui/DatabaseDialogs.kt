package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.ui.dialog.ConnectionEdit
import dev.sluide.databases.ui.dialog.ConnectionTester

/** How a pending transaction ends before a disconnect or a switch to Auto mode. */
enum class TransactionEnd { COMMIT, ROLLBACK }

/** The questions of the Databases tab (ADR 0121), FX-free so that the model can be tested with a fake. FX thread. */
interface DatabaseDialogs {
    /** The Add/Edit connection dialog; `null` when cancelled. */
    fun editConnection(
        initial: ConnectionConfig,
        isNew: Boolean,
        existingNames: Set<String>,
        tester: ConnectionTester,
    ): ConnectionEdit?

    /** null = cancelled; an empty array = connect without a password. */
    fun askPassword(config: ConnectionConfig): CharArray?

    /** Whether to delete the connection [name]; Cancel is the default. */
    fun confirmDelete(name: String): Boolean

    /** Commit, Roll Back, or `null` for Cancel (the default). */
    fun askTransactionEnd(name: String): TransactionEnd?

    /** Whether to disconnect [name] anyway, for the [reasons] ("A statement is running."); Cancel is the default. */
    fun confirmDisconnect(
        name: String,
        reasons: List<String>,
    ): Boolean

    fun showError(
        title: String,
        message: String,
    )
}
