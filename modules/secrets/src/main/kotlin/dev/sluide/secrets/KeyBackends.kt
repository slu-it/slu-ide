package dev.sluide.secrets

/** The secret store of the running system (ADR 0122). */
internal object KeyBackends {
    fun forPlatform(osName: String = System.getProperty("os.name")): KeyBackend =
        when {
            osName.startsWith("Mac") -> MacKeychain()
            osName.startsWith("Linux") -> SecretToolBackend()
            else -> NoBackend
        }
}
