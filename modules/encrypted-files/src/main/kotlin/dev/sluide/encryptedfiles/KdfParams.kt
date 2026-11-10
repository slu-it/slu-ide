package dev.sluide.encryptedfiles

/**
 * Argon2id parameters of a password slot, stored in the file (ADR 0162). Readers refuse values above the caps before
 * running Argon2, so a crafted file cannot make Thoreau allocate gigabytes or spin for minutes.
 */
data class KdfParams(
    val memoryKiB: Int,
    val passes: Int,
    val parallelism: Int,
) {
    companion object {
        /** RFC 9106's second recommendation: 64 MiB, 3 passes, 4 lanes. */
        val DEFAULT = KdfParams(65_536, 3, 4)

        internal const val MAX_MEMORY_KIB = 1_048_576L
        internal const val MAX_PASSES = 20L
        internal const val MAX_PARALLELISM = 16

        /** Whether readers accept these values; memory and passes are unsigned 32-bit numbers in the file. */
        internal fun supported(
            memoryKiB: Long,
            passes: Long,
            parallelism: Int,
        ): Boolean =
            parallelism in 1..MAX_PARALLELISM &&
                passes in 1..MAX_PASSES &&
                memoryKiB in 8L * parallelism..MAX_MEMORY_KIB
    }
}
