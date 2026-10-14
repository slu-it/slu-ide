@file:JvmName("SluIdeLauncher")

package dev.sluide.ide

import javafx.application.Application

/**
 * Entry point of the fat jar. It must not be the [Application] subclass itself, otherwise the JVM
 * insists on JavaFX being on the module path.
 */
fun main(args: Array<String>) {
    // The JDK HTTP client reads this once, when its classes load: requests of .http files may set `Host` (ADR 0027).
    System.setProperty(ALLOW_RESTRICTED_HEADERS, withHost(System.getProperty(ALLOW_RESTRICTED_HEADERS)))
    Application.launch(SluIdeApplication::class.java, *args)
}

private const val ALLOW_RESTRICTED_HEADERS = "jdk.httpclient.allowRestrictedHeaders"

/** The comma-separated header list [allowed] with `host` added, unless it is listed already. */
internal fun withHost(allowed: String?): String =
    when {
        allowed.isNullOrBlank() -> "host"
        allowed.split(',').any { it.trim().equals("host", ignoreCase = true) } -> allowed
        else -> "$allowed,host"
    }
