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
