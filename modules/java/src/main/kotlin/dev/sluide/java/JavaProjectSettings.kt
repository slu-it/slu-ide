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
 * What a project stores for Java (ADR 0179, ADR 0090): `{"jdk": "<path>"}` in the top-level field `java` of the
 * project's data. Application-wide: listeners hear of changes in every window.
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
        var wrongType = false
        val saved =
            store.update(folder) { root ->
                val existing = root.get(FIELD)
                val java =
                    existing as? ObjectNode ?: if (existing == null || existing.isNull) root.objectNode() else null
                if (java == null) {
                    wrongType = true
                    return@update
                }
                if (jdk == null) java.remove(JDK) else java.put(JDK, jdk.toString())
                if (java.size() == 0) root.remove(FIELD) else root.set(FIELD, java)
            }
        if (wrongType) throw IOException("The field '$FIELD' of the project data of $folder is not an object")
        if (!saved) throw IOException("The project data of $folder could not be saved")
        val changed = folder.toAbsolutePath().normalize()
        dispatch(Runnable { listeners.forEach { it(changed) } })
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
    }
}
