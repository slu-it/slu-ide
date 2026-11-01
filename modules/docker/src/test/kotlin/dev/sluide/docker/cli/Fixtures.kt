package dev.sluide.docker.cli

/** A captured (`docker/…`) or hand-written (`podman/…`) CLI output from the test resources. */
internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/dev/sluide/docker/cli/$name")) { "no fixture $name" }.readText()
