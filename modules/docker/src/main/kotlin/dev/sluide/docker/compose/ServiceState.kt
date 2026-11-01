package dev.sluide.docker.compose

import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState

/**
 * A service of the compose pane with its containers.
 *
 * @property service the service as the file defines it; `null` for an orphan (containers of the project whose service
 *   is no longer in the file).
 * @property containers its containers of the project, sorted by name (replicas: several).
 * @property summary a short state for the list: `not created`, the state of a single container (`running`, `exited`),
 *   or `2/3 running` for replicas.
 */
data class ServiceState(
    val name: String,
    val service: ComposeService?,
    val containers: List<Container>,
    val summary: String,
) {
    val orphan: Boolean get() = service == null

    /** Whether at least one container runs. */
    val running: Boolean get() = containers.any { it.state == ContainerState.RUNNING }
}

/**
 * The services of [config] with the [containers] of the session that belong to them (same `composeProject` as
 * [ComposeConfig.name] and `composeService` as the service). One-off containers (`compose run`) are left out. The
 * order is that of [order] (the service names in file order, e.g. the keys of [serviceLines]); services it does not
 * name follow in the order of [config]. Orphans, the project's containers of services the config does not know,
 * come last, by name, marked by [ServiceState.orphan].
 */
fun serviceStates(
    config: ComposeConfig,
    containers: List<Container>,
    order: List<String> = emptyList(),
): List<ServiceState> {
    val own = containers.filter { it.composeProject == config.name && it.composeService != null && !it.composeOneOff }
    val byService = own.groupBy { it.composeService.orEmpty() }
    val known = config.services.associateBy { it.name }
    val names = order.filter { it in known }.distinct() + config.services.map { it.name }.filter { it !in order }
    val states =
        names.map { name ->
            val mine = byService[name].orEmpty().sortedBy { it.name }
            ServiceState(name, known.getValue(name), mine, summaryOf(mine))
        }
    val orphans =
        byService.keys.filter { it !in known }.sorted().map { name ->
            val mine = byService.getValue(name).sortedBy { it.name }
            ServiceState(name, null, mine, summaryOf(mine))
        }
    return states + orphans
}

private fun summaryOf(containers: List<Container>): String =
    when (containers.size) {
        0 -> {
            "not created"
        }

        1 -> {
            containers
                .single()
                .state.name
                .lowercase()
        }

        else -> {
            "${containers.count { it.state == ContainerState.RUNNING }}/${containers.size} running"
        }
    }
