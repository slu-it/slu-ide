package dev.sluide.docker.cli

import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.StatsSample
import dev.sluide.docker.model.parsePercent
import dev.sluide.docker.model.parseSizePair
import dev.sluide.docker.model.parseTime
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * One event of `events --format json`. Docker: `{"Type", "Action", "Actor": {"ID", "Attributes": {"name", …}},
 * "timeNano"}`; Podman: `{"Type", "Status", "ID", "Name", "Attributes", "Time"}`. `null` without type or action.
 */
internal fun parseEvent(node: JsonNode): DockerEvent? {
    val type = node.text("Type") ?: return null
    val action = node.text("Action", "Status") ?: return null
    val actor = node.field("Actor")
    val attributes = (actor ?: node).labels("Attributes")
    return DockerEvent(
        type = type,
        action = action,
        id = actor?.text("ID") ?: node.text("ID", "id") ?: attributes["name"] ?: node.text("Name") ?: "",
        name = attributes["name"] ?: node.text("Name"),
        attributes = attributes,
        time = eventTime(node),
    )
}

private fun eventTime(node: JsonNode): Instant? {
    node.long("timeNano")?.let { return Instant.ofEpochSecond(it / 1_000_000_000, it % 1_000_000_000) }
    val time = node.field("time", "Time") ?: return null
    return if (time.isNumber) Instant.ofEpochSecond(time.asLong()) else parseTime(time.scalarText())
}

/**
 * One sample of `stats --format json`: Docker's `CPUPerc`, `MemUsage` ("10.84MiB / 11.84GiB"), `NetIO`, `BlockIO`,
 * `PIDs`, or Podman's `cpu_percent`, `mem_usage`, `net_io`, `block_io`, `pids`. `null` without a CPU value.
 */
internal fun parseStats(node: JsonNode): StatsSample? {
    val cpu = parsePercent(node.text("CPUPerc", "cpu_percent")) ?: return null
    val (memoryUsed, memoryLimit) = parseSizePair(node.text("MemUsage", "mem_usage"))
    val (received, sent) = parseSizePair(node.text("NetIO", "net_io"))
    val (read, written) = parseSizePair(node.text("BlockIO", "block_io"))
    return StatsSample(
        cpuPercent = cpu,
        memoryUsed = memoryUsed,
        memoryLimit = memoryLimit,
        networkReceived = received,
        networkSent = sent,
        blockRead = read,
        blockWritten = written,
        pids = node.long("PIDs", "pids")?.toInt(),
    )
}
