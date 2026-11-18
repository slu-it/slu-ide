package dev.sluide.docker.model

/** A container for tests; only what a test names differs from a running standalone one. */
internal fun container(
    name: String,
    id: String = "id-$name",
    project: String? = null,
    service: String? = null,
    workingDir: String? = null,
    oneOff: Boolean = false,
    image: String = "bash:5.2",
    imageId: String? = null,
    state: ContainerState = ContainerState.RUNNING,
    volumes: List<String> = emptyList(),
    networks: List<String> = listOf("bridge"),
): Container =
    Container(
        id = id,
        name = name,
        image = image,
        imageId = imageId,
        state = state,
        status = "Up",
        ports = emptyList(),
        labels =
            buildMap {
                project?.let { put(COMPOSE_PROJECT_LABEL, it) }
                service?.let { put(COMPOSE_SERVICE_LABEL, it) }
                workingDir?.let { put(COMPOSE_WORKING_DIR_LABEL, it) }
                if (oneOff) put(COMPOSE_ONEOFF_LABEL, "True")
            },
        createdAt = null,
        volumes = volumes,
        networks = networks,
    )

/**
 * Another string of the same length with the same `hashCode` that [caseOf] (`lowercaseChar`, `uppercaseChar`) and
 * `trim()` leave as it is: one letter lowered by d and its neighbour raised by 31 d, which `hashCode` (31 * a + b)
 * cannot tell. For the `when (text)` on strings, which jumps by hash code and then checks with `equals`.
 */
internal fun collidingWith(
    word: String,
    caseOf: (Char) -> Char = Char::lowercaseChar,
): String {
    val stable = { c: Char -> caseOf(c) == c && !c.isWhitespace() }
    for (index in 0 until word.length - 1) {
        for (d in 1..4) {
            val first = word[index] - d
            val second = word[index + 1] + 31 * d
            if (stable(first) && stable(second) && first in 'A'..'z' && first.isLetter() && second.code >= 0xA0) {
                return word.substring(0, index) + first + second + word.substring(index + 2)
            }
        }
    }
    error("no collision for $word")
}
