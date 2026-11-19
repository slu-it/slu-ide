package dev.sluide.fileexplorer.actions

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

/** The name offered for pasted clipboard content, without extension: `pasted-` and the local time of [clock]. */
internal fun pastedBaseName(clock: Clock): String = "pasted-${STAMP.format(LocalDateTime.now(clock))}"
