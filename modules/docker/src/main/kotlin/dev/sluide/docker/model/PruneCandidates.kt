package dev.sluide.docker.model

/**
 * What the prune actions would remove, for their confirmations: the engine decides in the end, so this is a preview.
 *
 * @property images dangling images no container was created from, once per image.
 * @property volumes volumes no container mounts, named ones included.
 * @property networks networks no container is connected to, except the engine's own.
 */
data class PruneCandidates(
    val images: List<Image>,
    val volumes: List<Volume>,
    val networks: List<Network>,
)

/** The [PruneCandidates] among [images], [volumes] and [networks], given the [containers] that use them. */
fun pruneCandidates(
    images: List<Image>,
    volumes: List<Volume>,
    networks: List<Network>,
    containers: List<Container>,
): PruneCandidates {
    // A container of a dangling image names it by its id (its tag is gone); references such as nginx:1 are no ids.
    val usedImages = containers.flatMap { listOfNotNull(it.imageId, it.image) }.mapNotNull(::hexId)
    val mounted = containers.flatMap { it.volumes }.toSet()
    val connected = containers.flatMap { it.networks }.toSet()
    return PruneCandidates(
        images =
            images
                .filter { it.dangling }
                .distinctBy { it.id }
                .filter { image -> usedImages.none { used -> hexId(image.id)?.startsWith(used) == true } },
        volumes = volumes.filter { volume -> volume.inUse?.not() ?: (volume.name !in mounted) },
        networks = networks.filter { !it.builtin && it.name !in connected && it.id !in connected },
    )
}

private val hexIdPattern = Regex("[0-9a-f]{12,64}")

/** An image id without its `sha256:` prefix, so that short and full ids compare; `null` if [text] is no id. */
private fun hexId(text: String): String? = text.removePrefix("sha256:").takeIf { hexIdPattern.matches(it) }
