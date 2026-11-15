package dev.sluide.java

import dev.sluide.servertools.ServerPathSetting
import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import org.springframework.stereotype.Component
import java.io.IOException

internal const val SERVER_PATH_KEY = "java.jdtls.path"
internal const val MAX_MEMORY_KEY = "java.maxMemory"

/** The memory sizes the settings page offers, in MB: 1, 2, 3, 4, 6 and 8 GB. */
internal val MEMORY_CHOICES_MB = listOf(1024, 2048, 3072, 4096, 6144, 8192)

/** Stored sizes in this range (MB) are kept even when the page does not offer them. */
internal val USABLE_MEMORY_MB = 512..65536

/**
 * The Java settings: the custom jdtls (`java.jdtls.path`, the `bin/jdtls` file of an unpacked jdtls; empty: Thoreau's
 * download, see [ServerPathSetting]) and the maximum memory of the jdtls process (`java.maxMemory`, in MB).
 */
@Component
class JavaSettings(
    private val store: SettingsStore,
) : ServerPathSetting(store, SERVER_PATH_KEY) {
    private val memoryWrapper = ReadOnlyIntegerWrapper(parseMaxMemory(store.load()[MAX_MEMORY_KEY]))

    @Volatile private var memoryInUse = memoryWrapper.get()

    /** The maximum memory of jdtls in MB; FX thread. */
    val maxMemoryMb: ReadOnlyIntegerProperty = memoryWrapper.readOnlyProperty

    /** The maximum memory in MB, from any thread. */
    fun currentMaxMemoryMb(): Int = memoryInUse

    /**
     * Stores [megabytes] (512 to 65536) and then sets it; FX thread.
     *
     * @throws IllegalArgumentException if it is outside that range.
     * @throws java.io.IOException if it cannot be stored; it is then not changed either.
     */
    @Throws(IOException::class)
    fun applyMaxMemory(megabytes: Int) {
        require(megabytes in USABLE_MEMORY_MB) { "$megabytes MB is not a usable maximum memory" }
        if (megabytes == memoryInUse) return
        store.update(mapOf(MAX_MEMORY_KEY to megabytes.toString()))
        memoryInUse = megabytes
        memoryWrapper.set(megabytes)
    }

    internal companion object {
        /** The stored [text] as megabytes if it is a whole number in [USABLE_MEMORY_MB], else the default. */
        fun parseMaxMemory(text: String?): Int =
            text?.trim()?.toIntOrNull()?.takeIf { it in USABLE_MEMORY_MB } ?: DEFAULT_MAX_MEMORY_MB
    }
}
