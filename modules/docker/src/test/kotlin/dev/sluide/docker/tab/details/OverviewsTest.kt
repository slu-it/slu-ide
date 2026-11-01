package dev.sluide.docker.tab.details

import dev.sluide.docker.model.COMPOSE_PROJECT_LABEL
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.EnvVariable
import dev.sluide.docker.model.Mount
import dev.sluide.docker.model.NetworkAttachment
import dev.sluide.docker.model.PortMapping
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.image
import dev.sluide.docker.tab.network
import dev.sluide.docker.tab.volume
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewsTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

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
}
