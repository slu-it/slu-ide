package dev.sluide.docker.tab.details

import dev.sluide.docker.model.COMPOSE_PROJECT_LABEL
import dev.sluide.docker.model.COMPOSE_SERVICE_LABEL
import dev.sluide.docker.model.COMPOSE_WORKING_DIR_LABEL
import dev.sluide.docker.model.ComposeProject
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.EnvVariable
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Mount
import dev.sluide.docker.model.NetworkAttachment
import dev.sluide.docker.model.PortMapping
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.absoluteTime
import dev.sluide.docker.tab.image
import dev.sluide.docker.tab.network
import dev.sluide.docker.tab.volume
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverviewsTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun details(
        state: ContainerState = ContainerState.RUNNING,
        createdAt: Instant? = null,
        startedAt: Instant? = null,
        exitCode: Int? = null,
        restartCount: Int = 0,
        command: List<String> = emptyList(),
        entrypoint: List<String> = emptyList(),
        labels: Map<String, String> = emptyMap(),
        mounts: List<Mount> = emptyList(),
        ports: List<PortMapping> = emptyList(),
    ) = ContainerDetails(
        id = "c1",
        name = "web",
        image = "nginx:1.27",
        imageId = null,
        state = state,
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = null,
        exitCode = exitCode,
        restartCount = restartCount,
        health = null,
        command = command,
        entrypoint = entrypoint,
        env = emptyList(),
        labels = labels,
        mounts = mounts,
        networks = emptyList(),
        ports = ports,
    )

    private fun texts(row: OverviewRow) =
        row.values.map {
            when (it) {
                is OverviewValue.Text -> it.text
                is OverviewValue.Link -> it.text
            }
        }

    /** The overview as label to texts. */
    private fun shown(rows: List<OverviewRow>) = rows.associate { it.label to texts(it) }

    @ParameterizedTest
    @CsvSource(
        "0.0.0.0, 8080, tcp, http://localhost:8080",
        "::, 8080, tcp, http://localhost:8080",
        "'', 3000, tcp, http://localhost:3000",
        "127.0.0.1, 5432, tcp, http://127.0.0.1:5432",
        "::1, 9000, tcp, http://[::1]:9000",
        "0.0.0.0, 53, udp, ''",
        "fe80::1%eth0, 8080, tcp, ''",
        "not a host, 8080, tcp, ''",
        "0.0.0.0, , tcp, ''",
    )
    fun `a published TCP port opens on localhost, or on the address it is published on`(
        hostIp: String,
        hostPort: Int?,
        protocol: String,
        uri: String,
    ) {
        assertEquals(uri.ifEmpty { null }?.let(::URI), portUri(PortMapping(hostIp, hostPort, 80, protocol)))
    }

    @Test
    fun `ports published on both address families are listed once, unpublished ones as text`() {
        val values =
            portValues(
                listOf(
                    PortMapping("0.0.0.0", 8080, 80),
                    PortMapping("::", 8080, 80),
                    PortMapping(null, null, 443),
                    PortMapping("0.0.0.0", 53, 53, "udp"),
                ),
            )

        assertEquals(
            listOf(
                OverviewValue.Link("localhost:8080 → 80/tcp", URI("http://localhost:8080")),
                OverviewValue.Text("443/tcp (not published)"),
                OverviewValue.Text("0.0.0.0:53 → 53/udp"),
            ),
            values,
        )
    }

    @Test
    fun `a container's overview lists what is known, compose labels by name, all labels folded`() {
        val details =
            ContainerDetails(
                id = "c1",
                name = "shop-web-1",
                image = "nginx:1.27",
                imageId = null,
                state = ContainerState.RUNNING,
                createdAt = now.minusSeconds(7200),
                startedAt = now.minusSeconds(300),
                finishedAt = null,
                exitCode = 0,
                restartCount = 2,
                health = "healthy",
                command = listOf("nginx", "-g", "daemon off;"),
                entrypoint = emptyList(),
                env = emptyList(),
                labels = mapOf(COMPOSE_PROJECT_LABEL to "shop", "b" to "2"),
                mounts =
                    listOf(
                        Mount("volume", "data", "/var/lib/docker/volumes/data/_data", "/data", readWrite = true),
                        Mount("bind", null, "/home/me/conf", "/etc/nginx", readWrite = false),
                    ),
                networks = listOf(NetworkAttachment("shop_default", "172.18.0.2")),
                ports = listOf(PortMapping("0.0.0.0", 8080, 80)),
            )

        val rows = containerOverview(details, container("shop-web-1").copy(status = "Up 5 minutes"), now)

        fun texts(row: OverviewRow) =
            row.values.map {
                when (it) {
                    is OverviewValue.Text -> it.text
                    is OverviewValue.Link -> it.text
                }
            }
        assertEquals(
            listOf(
                "Image" to listOf("nginx:1.27"),
                "State" to listOf("running · Up 5 minutes"),
                "Health" to listOf("healthy"),
                "Created" to listOf("2 hours ago"),
                "Started" to listOf("5 minutes ago"),
                "Ports" to listOf("localhost:8080 → 80/tcp"),
                "Mounts" to listOf("data → /data", "/home/me/conf → /etc/nginx (read-only)"),
                "Networks" to listOf("shop_default 172.18.0.2"),
                "Command" to listOf("nginx -g daemon off;"),
                "Restarts" to listOf("2"),
                "Compose project" to listOf("shop"),
                "Labels" to listOf("b=2", "$COMPOSE_PROJECT_LABEL=shop"),
            ),
            rows.map { it.label to texts(it) },
        )
        assertEquals(listOf("Labels"), rows.filter { it.collapsible }.map { it.label })
    }

    @Test
    fun `an image is used by containers naming it by reference, by repository for latest, or by id`() {
        val nginx = image("nginx", "latest", id = "sha256:" + "c".repeat(64))
        val users =
            imageUsers(
                nginx,
                listOf(
                    container("a", image = "nginx:latest"),
                    container("b", image = "nginx"),
                    container("c", image = "cccccccccccc"),
                    container("d", image = "nginx:1.27"),
                ),
            )

        assertEquals(listOf("a", "b", "c"), users.map { it.name })
    }

    @Test
    fun `volume and network overviews list the containers using them`() {
        val containers =
            listOf(
                container("web", volumes = listOf("data"), networks = listOf("shop_default")),
                container("db", networks = listOf("net-other")),
            )

        val volumeRows = volumeOverview(volume("data"), containers)
        assertEquals(listOf("Name", "Driver", "Mountpoint", "Used by"), volumeRows.map { it.label })
        assertEquals(listOf(OverviewValue.Text("web (running)", "Up")), volumeRows.last().values)
        assertEquals(
            listOf(OverviewValue.Text("no container")),
            volumeOverview(volume("cache", inUse = false), containers).last().values,
        )

        assertEquals(
            listOf("web (running)"),
            networkOverview(network("shop_default"), containers).single { it.label == "Containers" }.values.map {
                (it as OverviewValue.Text).text
            },
        )
        assertEquals(
            listOf("db (running)"),
            networkOverview(
                network("other", id = "net-other"),
                containers,
            ).single { it.label == "Containers" }.values.map {
                (it as OverviewValue.Text).text
            },
        )
    }

    @Test
    fun `environment values are masked until shown, Copy gives NAME=value`() {
        assertEquals("••••••", envValueText("secret", shown = false))
        assertEquals("secret", envValueText("secret", shown = true))
        assertEquals("TOKEN=a=b", envCopyText(EnvVariable("TOKEN", "a=b")))
    }

    @Test
    fun `a port published without an address, or on a trimmed one, opens on localhost or that address`() {
        assertEquals(URI("http://localhost:80"), portUri(PortMapping(null, 80, 80)))
        assertEquals(URI("http://127.0.0.1:80"), portUri(PortMapping(" 127.0.0.1 ", 80, 80)))
    }

    @Test
    fun `an address that only has the hash code of a local one is not taken for it`() {
        // `when (ip)` jumps by hash code: these collide with "0.0.0.0", "::" and "" and must still be checked.
        for ((local, other) in listOf("0.0.0.0" to "0-O.0.0", "::" to "9Y", "" to "\u0000")) {
            assertEquals(local.hashCode(), other.hashCode())
            assertEquals(other, other.trim(), "the address stays as it is when trimmed")
        }
        assertEquals(URI("http://0-O.0.0:8080"), portUri(PortMapping("0-O.0.0", 8080, 80)))
        assertEquals(URI("http://9Y:8080"), portUri(PortMapping("9Y", 8080, 80)))
        assertNull(portUri(PortMapping("\u0000", 8080, 80)), "a NUL is no host; it is not localhost")
    }

    @Test
    fun `a published port that is no link shows its address, or none`() {
        assertEquals(
            listOf(OverviewValue.Text(":53 → 53/udp"), OverviewValue.Text("10.0.0.1:53 → 53/udp")),
            portValues(listOf(PortMapping(null, 53, 53, "udp"), PortMapping("10.0.0.1", 53, 53, "udp"))),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "RUNNING, Up 3 minutes, running · Up 3 minutes",
        "EXITED, Exited (0) 2 hours ago, exited · Exited (0) 2 hours ago",
        "PAUSED, '', paused",
        "PAUSED, '   ', paused",
    )
    fun `the state text adds Docker's status unless it is blank`(
        state: ContainerState,
        status: String,
        expected: String,
    ) {
        assertEquals(expected, stateText(state, status))
    }

    @Test
    fun `the state text without a status is the state`() {
        assertEquals("running", stateText(ContainerState.RUNNING, null))
    }

    @Test
    fun `an overview of a container the list does not know shows the state alone`() {
        assertEquals(listOf("running"), shown(containerOverview(details(), null, now))["State"])
    }

    @Test
    fun `times show how long ago, with the exact time as tooltip, and no row for an unknown time`() {
        val created = now.minusSeconds(7200)

        val rows = containerOverview(details(createdAt = created), null, now)

        assertEquals(
            listOf(OverviewValue.Text("2 hours ago", absoluteTime(created))),
            rows.single { it.label == "Created" }.values,
        )
        assertEquals(emptyList(), rows.filter { it.label == "Started" })
    }

    @Test
    fun `a container without ports, mounts, restarts or labels has no such rows`() {
        val labels = shown(containerOverview(details(), null, now)).keys

        assertEquals(setOf("Image", "State"), labels)
    }

    @Test
    fun `a mount shows its name, else its source, else its type`() {
        val mounts =
            listOf(
                Mount("volume", "data", "/var/lib/data", "/a", readWrite = true),
                Mount("bind", null, "/home/me/conf", "/b", readWrite = true),
                Mount("tmpfs", null, null, "/c", readWrite = true),
            )

        assertEquals(
            listOf("data → /a", "/home/me/conf → /b", "tmpfs → /c"),
            shown(containerOverview(details(mounts = mounts), null, now))["Mounts"],
        )
    }

    @Test
    fun `command and entrypoint are joined with spaces, an empty one has no row`() {
        val rows =
            shown(
                containerOverview(
                    details(command = listOf("nginx", "-g"), entrypoint = listOf("/entry.sh", "--x")),
                    null,
                    now,
                ),
            )

        assertEquals(listOf("nginx -g"), rows["Command"])
        assertEquals(listOf("/entry.sh --x"), rows["Entrypoint"])
    }

    @ParameterizedTest
    @CsvSource("0,false", "1,true", "5,true")
    fun `restarts are shown from the first one`(
        count: Int,
        isShown: Boolean,
    ) {
        val rows = shown(containerOverview(details(restartCount = count), null, now))

        assertEquals(if (isShown) listOf(count.toString()) else null, rows["Restarts"])
    }

    @ParameterizedTest
    @EnumSource(ContainerState::class)
    fun `the exit code is shown for a container that is not running, and only if it has one`(state: ContainerState) {
        val rows = shown(containerOverview(details(state = state, exitCode = 137), null, now))
        val none = shown(containerOverview(details(state = state, exitCode = null), null, now))

        assertEquals(if (state == ContainerState.RUNNING) null else listOf("137"), rows["Exit code"])
        assertNull(none["Exit code"])
    }

    @Test
    fun `an exit code of 0 is shown for a stopped container`() {
        val rows = shown(containerOverview(details(state = ContainerState.EXITED, exitCode = 0), null, now))

        assertEquals(listOf("0"), rows["Exit code"])
    }

    @Test
    fun `compose labels get their own rows by name`() {
        val labels =
            mapOf(
                COMPOSE_PROJECT_LABEL to "shop",
                COMPOSE_SERVICE_LABEL to "web",
                COMPOSE_WORKING_DIR_LABEL to "/home/me/shop",
            )

        val rows = shown(containerOverview(details(labels = labels), null, now))

        assertEquals(listOf("shop"), rows["Compose project"])
        assertEquals(listOf("web"), rows["Compose service"])
        assertEquals(listOf("/home/me/shop"), rows["Working dir"])
    }

    @Test
    fun `an image is not used by a container naming only its repository unless it is latest`() {
        val tagged = image("nginx", "1.27", id = "sha256:" + "c".repeat(64))

        assertEquals(emptyList(), imageUsers(tagged, listOf(container("a", image = "nginx"))))
    }

    @Test
    fun `an image is used by a container that names it by id or its imageId, not by a short prefix`() {
        val nginx = image("nginx", "1.27", id = "sha256:" + "c".repeat(64))
        val users =
            imageUsers(
                nginx,
                listOf(
                    container("by-image-id", image = "x", imageId = "sha256:" + "c".repeat(64)),
                    container("by-short-id", image = "x", imageId = "c".repeat(12)),
                    container("too-short", image = "ccc"),
                    container("short-image-id", image = "x", imageId = "ccc"),
                    container("other", image = "x", imageId = "sha256:" + "e".repeat(64)),
                    container("other-by-image", image = "e".repeat(40)),
                ),
            )

        assertEquals(listOf("by-image-id", "by-short-id"), users.map { it.name })
    }

    @Test
    fun `an image overview lists reference, short id, digest, size, age and the containers`() {
        val created = now.minusSeconds(86_400 * 3)
        val nginx =
            Image("sha256:" + "ab".repeat(32), "nginx", "1.27", "sha256:feed", 25_400_000, created, dangling = false)

        val rows = imageOverview(nginx, listOf(container("web", image = "nginx:1.27")), now)

        assertEquals(
            listOf("Reference", "Id", "Digest", "Size", "Created", "Containers"),
            rows.map { it.label },
        )
        assertEquals(
            listOf(
                listOf("nginx:1.27"),
                listOf("abababababab"),
                listOf("sha256:feed"),
                listOf("25.4 MB"),
                listOf("3 days ago"),
                listOf("web (running)"),
            ),
            rows.map(::texts),
        )
        assertEquals(OverviewValue.Text("3 days ago", absoluteTime(created)), rows[4].values.single())
        assertEquals(OverviewValue.Text("web (running)", "Up"), rows.last().values.single())
    }

    @Test
    fun `a dangling image shows no reference of its own, and no rows for what it lacks`() {
        val dangling = image(null, null, id = "sha256:" + "d".repeat(64))

        val rows = imageOverview(dangling, emptyList(), now)

        assertEquals(listOf("Reference", "Id", "Size"), rows.map { it.label })
        assertEquals(listOf("<none>"), texts(rows.first()))
    }

    @Test
    fun `a volume overview says whether a container uses it, and lists its labels`() {
        val labelled = volume("data", inUse = true).copy(labels = mapOf("b" to "2", "a" to "1"))

        val used = volumeOverview(labelled, emptyList())

        assertEquals(listOf("a container"), texts(used.single { it.label == "Used by" }))
        assertEquals(listOf("a=1", "b=2"), texts(used.single { it.label == "Labels" }))
        assertEquals(
            listOf("Name", "Driver", "Mountpoint", "Used by", "Labels"),
            used.map { it.label },
        )
        val unknown = volumeOverview(volume("x", inUse = null), emptyList())
        assertEquals(emptyList(), unknown.filter { it.label == "Used by" })
    }

    @Test
    fun `a network overview lists its id, driver, scope and labels`() {
        val net = network("shop_default", id = "n".repeat(64)).copy(labels = mapOf("k" to "v"))

        val rows = networkOverview(net, emptyList())

        assertEquals(
            listOf(
                "Name" to listOf("shop_default"),
                "Id" to listOf("nnnnnnnnnnnn"),
                "Driver" to listOf("bridge"),
                "Scope" to listOf("local"),
                "Labels" to listOf("k=v"),
            ),
            rows.map { it.label to texts(it) },
        )
    }

    @Test
    fun `a project overview lists its containers with their service`() {
        val project =
            ComposeProject(
                "shop",
                "/home/me/shop",
                true,
                listOf(
                    container("shop-web-1", service = "web", state = ContainerState.EXITED),
                    container("shop-run-1"),
                ),
            )

        val rows = projectOverview(project)

        assertEquals(
            listOf(
                "Project" to listOf("shop"),
                "Working dir" to listOf("/home/me/shop"),
                "Containers" to listOf("web: shop-web-1 (exited)", "shop-run-1 (running)"),
            ),
            rows.map { it.label to texts(it) },
        )
        assertEquals(OverviewValue.Text("web: shop-web-1 (exited)", "Up"), rows.last().values.first())
    }
}
