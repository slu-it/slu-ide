package dev.sluide.java

import dev.sluide.projectdata.ProjectDataStore
import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a project stores for Java (ADR 0179, ADR 0090): `{"jdk": "<path>", "outputIgnore": {"declined": true}}` in the
 * top-level field `java` of the project's data. Application-wide: listeners hear of changes of the JDK in every window.
 */
@Component
class JavaProjectSettings internal constructor(
    private val store: ProjectDataStore,
    private val dispatch: (Runnable) -> Unit,
) {
    @Autowired
    constructor(store: ProjectDataStore) : this(store, Platform::runLater)

    /** The JDK the project chose, null if none; any thread. */
    fun jdk(folder: Path): Path? {
        val text =
            store
                .read(folder)
                ?.get(FIELD)
                ?.get(JDK)
                ?.takeIf { it.isString }
                ?.asString()
        return text?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
    }

    /**
     * Stores [jdk] as the project's JDK (null removes it) and then tells the listeners, on the FX thread. Writes the
     * project's data file under the store's lock: call it off the FX thread unless the write is small enough to wait for.
     *
     * @throws IOException if the store did not save it, or the field `java` is not an object (it is not ours).
     */
    @Throws(IOException::class)
    fun setJdk(
        folder: Path,
        jdk: Path?,
    ) {
        save(folder) { java ->
            if (jdk == null) java.remove(JDK) else java.put(JDK, jdk.toString())
            null
        }
        val changed = folder.toAbsolutePath().normalize()
        dispatch(Runnable { listeners.forEach { it(changed) } })
    }

    /** Whether the project said "Don't Ask Again" to the offer to ignore jdtls's compiled output; any thread. */
    fun outputIgnoreDeclined(folder: Path): Boolean =
        store
            .read(folder)
            ?.get(FIELD)
            ?.get(OUTPUT_IGNORE)
            ?.get(DECLINED)
            ?.takeIf { it.isBoolean }
            ?.asBoolean() == true

    /**
     * Stores that the project declined the offer ([outputIgnoreDeclined]), as `java.outputIgnore.declined`; no listener
     * hears of it, since the JDK did not change. Writes the project's data file: off the FX thread.
     *
     * @throws IOException as [setJdk] does, also if `java.outputIgnore` is not an object.
     */
    @Throws(IOException::class)
    fun declineOutputIgnore(folder: Path) {
        save(folder) { java ->
            val existing = java.get(OUTPUT_IGNORE)
            val decision =
                existing as? ObjectNode ?: if (existing == null || existing.isNull) java.objectNode() else null
            if (decision == null) {
                "$FIELD.$OUTPUT_IGNORE"
            } else {
                java.set(OUTPUT_IGNORE, decision.put(DECLINED, true))
                null
            }
        }
    }

    /**
     * Changes the field `java` of the project's data by [change], creating it and removing it again if empty; [change]
     * returns the name of a field of it that is not an object, which leaves the data as it was.
     */
    private fun save(
        folder: Path,
        change: (ObjectNode) -> String?,
    ) {
        var wrongType: String? = null
        val saved =
            store.update(folder) { root ->
                val existing = root.get(FIELD)
                val java =
                    existing as? ObjectNode ?: if (existing == null || existing.isNull) root.objectNode() else null
                wrongType = if (java == null) FIELD else change(java)
                if (java == null) return@update
                if (java.size() == 0) root.remove(FIELD) else root.set(FIELD, java)
            }
        wrongType?.let { throw IOException("The field '$it' of the project data of $folder is not an object") }
        if (!saved) throw IOException("The project data of $folder could not be saved")
    }

    /** Calls [listener] with the folder (`toAbsolutePath().normalize()`) after every successful [setJdk], on the FX thread. */
    fun onChange(listener: (folder: Path) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private val listeners = CopyOnWriteArrayList<(Path) -> Unit>()

    private companion object {
        const val FIELD = "java"
        const val JDK = "jdk"
        const val OUTPUT_IGNORE = "outputIgnore"
        const val DECLINED = "declined"
    }
}
