package dev.sluide.docker.compose

import dev.sluide.docker.cli.ComposeConfigOutput

/*
 * The fixtures of the Compose view's tests: a compose file and what `docker compose config` prints for it.
 */

/** A compose file of the project `shop`: web (a port, a variable), worker and debug (profile `debug`), in that order. */
internal const val SHOP_COMPOSE =
    "services:\n" +
        "  web:\n" +
        "    image: bash:5.2\n" +
        "    ports: [\"8080:80\"]\n" +
        "    environment: [\"GREETING=\${GREETING}\"]\n" +
        "  worker:\n" +
        "    image: bash:5.2\n" +
        "  debug:\n" +
        "    image: bash:5.2\n" +
        "    profiles: [debug]\n"

/** The `config` JSON of [SHOP_COMPOSE] with every profile, alphabetical as compose prints it. */
internal const val SHOP_CONFIG_JSON =
    """{"name":"shop","services":{
        "debug":{"image":"bash:5.2","profiles":["debug"]},
        "web":{"image":"bash:5.2","ports":[{"target":80,"published":"8080","protocol":"tcp"}]},
        "worker":{"image":"bash:5.2"}}}"""

/** The warning compose prints while `GREETING` is not set. */
internal const val GREETING_WARNING =
    "time=\"2026-11-01T10:00:00Z\" level=warning msg=\"The \\\"GREETING\\\" variable is not set. " +
        "Defaulting to a blank string.\""

/** The output of `config` for [SHOP_COMPOSE], with the warning about `GREETING` if [unresolved]. */
internal fun shopConfig(unresolved: Boolean = false) =
    ComposeConfigOutput(SHOP_CONFIG_JSON, if (unresolved) GREETING_WARNING else "")
