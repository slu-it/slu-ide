package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.ui.dialog.ConnectionEdit
import dev.sluide.databases.ui.dialog.ConnectionTester

/** How a pending transaction ends before a disconnect or a switch to Auto mode. */
enum class TransactionEnd { COMMIT, ROLLBACK }

/**
 * The answer of [DatabaseDialogs.askPassword]: the [password] (an empty array = connect without a password) and whether
 * to [save] it once the login succeeds (unchecked removes a saved one). When the prompt was shown with a `saveReason`
 * (the box disabled), [save] is ignored: the saved value is neither saved nor removed. `equals` compares the array by
 * identity.
 */
data class PasswordAnswer(
    val password: CharArray,
    val save: Boolean,
)

/** The questions of the Databases tab (ADR 0121), FX-free so that the model can be tested with a fake. FX thread. */
interface DatabaseDialogs {
    /**
     * The Add/Edit connection dialog; `null` when cancelled. [saveReason] non-null: Save password is disabled with that
     * reason ("needs secret-tool").
     */
    fun editConnection(
        initial: ConnectionConfig,
        isNew: Boolean,
        existingNames: Set<String>,
        tester: ConnectionTester,
        saveReason: String?,
    ): ConnectionEdit?

    /**
     * The "Connect to X" prompt; `null` = cancelled. Its Save password box starts as [saveChecked], or is disabled with
     * [saveReason] when that is non-null.
     */
    fun askPassword(
        config: ConnectionConfig,
        saveReason: String?,
        saveChecked: Boolean,
    ): PasswordAnswer?

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
