package dev.sluide.languageservers

/**
 * Asks the project trust whether a server that runs project code may start (ADR 0053). `ide` passes the window's
 * `ProjectTrust::awaitAllows`; tests pass a lambda.
 */
fun interface TrustGate {
    /** Blocks until the trust checks have run; `false` if [checkId] is not allowed or once [cancelled] says so. */
    fun awaitAllows(
        checkId: String,
        cancelled: () -> Boolean,
    ): Boolean
}
