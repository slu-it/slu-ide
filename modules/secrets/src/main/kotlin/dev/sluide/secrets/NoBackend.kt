package dev.sluide.secrets

/** No secret store on this system (neither macOS nor Linux): nothing can be saved (ADR 0122). */
internal object NoBackend : KeyBackend {
    private const val REASON = "not supported on this system"

    override val storeName = ""

    override fun unavailableReason() = REASON

    override fun read(): KeyRead = KeyRead.Failed(REASON)

    override fun create(key: ByteArray): KeyCreate = KeyCreate.Failed(REASON)

    override fun delete() = true
}
