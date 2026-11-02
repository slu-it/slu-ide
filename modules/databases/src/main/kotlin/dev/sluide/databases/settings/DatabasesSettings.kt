package dev.sluide.databases.settings

import dev.sluide.settings.SettingsStore
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import org.springframework.stereotype.Component

private const val PAGE_SIZE_KEY = "databases.pageSize"
private const val FETCH_ALL_CAP_KEY = "databases.fetchAllCap"
private const val TIMEOUT_KEY = "databases.statementTimeoutSeconds"

internal val PAGE_SIZE_RANGE = 10..10_000
internal val FETCH_ALL_CAP_RANGE = 1_000..10_000_000
internal val TIMEOUT_RANGE = 0..86_400

internal const val DEFAULT_PAGE_SIZE = 500
internal const val DEFAULT_FETCH_ALL_CAP = 100_000
internal const val DEFAULT_TIMEOUT_SECONDS = 0

/**
 * The Databases settings (ADR 0121): [pageSize] rows per result page, [fetchAllCap] most rows Last and Export fetch,
 * [statementTimeoutSeconds] (0 = no timeout). Stored in the [SettingsStore] under `databases.*`; a missing, unparsable
 * or out-of-range stored value gives the default. The settings dialog changes them with [apply], which sets the
 * properties live.
 */
@Component
class DatabasesSettings(
    private val store: SettingsStore,
) {
    private val pageSizeWrapper = ReadOnlyIntegerWrapper()
    private val fetchAllCapWrapper = ReadOnlyIntegerWrapper()
    private val timeoutWrapper = ReadOnlyIntegerWrapper()

    init {
        val stored = store.load()
        pageSizeWrapper.set(read(stored[PAGE_SIZE_KEY], PAGE_SIZE_RANGE, DEFAULT_PAGE_SIZE))
        fetchAllCapWrapper.set(read(stored[FETCH_ALL_CAP_KEY], FETCH_ALL_CAP_RANGE, DEFAULT_FETCH_ALL_CAP))
        timeoutWrapper.set(read(stored[TIMEOUT_KEY], TIMEOUT_RANGE, DEFAULT_TIMEOUT_SECONDS))
    }

    /** Rows per result page, 10..10000. */
    val pageSize: ReadOnlyIntegerProperty = pageSizeWrapper.readOnlyProperty

    /** The most rows Last and Export fetch, 1000..10000000. */
    val fetchAllCap: ReadOnlyIntegerProperty = fetchAllCapWrapper.readOnlyProperty

    /** The statement timeout in seconds, 0 for none, 0..86400. */
    val statementTimeoutSeconds: ReadOnlyIntegerProperty = timeoutWrapper.readOnlyProperty

    /**
     * Stores the three values (each clamped into its range) and then sets them. Must be called on the FX thread, which
     * waits for the (small) settings file to be written.
     *
     * @throws java.io.IOException if they cannot be stored; they are then not changed either.
     */
    fun apply(
        pageSize: Int,
        fetchAllCap: Int,
        statementTimeoutSeconds: Int,
    ) {
        val page = pageSize.coerceIn(PAGE_SIZE_RANGE)
        val cap = fetchAllCap.coerceIn(FETCH_ALL_CAP_RANGE)
        val timeout = statementTimeoutSeconds.coerceIn(TIMEOUT_RANGE)
        if (page == pageSizeWrapper.get() && cap == fetchAllCapWrapper.get() && timeout == timeoutWrapper.get()) return
        store.update(
            mapOf(
                PAGE_SIZE_KEY to page.toString(),
                FETCH_ALL_CAP_KEY to cap.toString(),
                TIMEOUT_KEY to timeout.toString(),
            ),
        )
        pageSizeWrapper.set(page)
        fetchAllCapWrapper.set(cap)
        timeoutWrapper.set(timeout)
    }

    internal companion object {
        fun read(
            value: String?,
            range: IntRange,
            default: Int,
        ): Int = value?.trim()?.toIntOrNull()?.takeIf { it in range } ?: default
    }
}
