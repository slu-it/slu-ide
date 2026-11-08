package dev.sluide.codeintelligence

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.Executor

private val log = System.getLogger(InstallPrompts::class.java.name)

/**
 * The languages whose install banner the user answered with Don't Ask Again (ADR 0145): user-wide, in every window,
 * kept in the shared data file (ADR 0101) as `{"codeIntelligence": {"installDeclined": ["python"]}}` by spec id.
 * Settings still installs. Read on first use of [declined]; written off the FX thread.
 */
@Component
class InstallPrompts internal constructor(
    private val store: ProjectDataStore,
    private val writer: Executor,
) {
    @Autowired
    constructor(store: ProjectDataStore) : this(store, NamedThreads.single("sluide-install-prompts"))

    /** The spec ids declined; FX thread. Every window's banners follow it. */
    val declined: ObservableSet<String> by lazy(LazyThreadSafetyMode.NONE) {
        FXCollections.observableSet(read().toMutableSet())
    }

    /** Never asks again for [specId] in any window, and saves that. FX thread. */
    fun decline(specId: String) {
        if (!declined.add(specId)) return
        writer.execute { write(specId) }
    }

    private fun read(): Set<String> {
        val field = store.readShared()?.get(FIELD) as? ObjectNode ?: return emptySet()
        val ids = field.get(DECLINED) as? ArrayNode ?: return emptySet()
        return ids.filter { it.isString }.map { it.asString() }.toSet()
    }

    private fun write(specId: String) {
        val written =
            store.updateShared { root ->
                val field = root.get(FIELD) as? ObjectNode ?: root.putObject(FIELD)
                val ids = field.get(DECLINED) as? ArrayNode ?: field.putArray(DECLINED)
                if (ids.none { it.isString && it.asString() == specId }) ids.add(specId)
            }
        if (!written) {
            log.log(System.Logger.Level.WARNING, "Don't Ask Again not saved: the shared data file cannot be written")
        }
    }

    private companion object {
        const val FIELD = "codeIntelligence"
        const val DECLINED = "installDeclined"
    }
}
