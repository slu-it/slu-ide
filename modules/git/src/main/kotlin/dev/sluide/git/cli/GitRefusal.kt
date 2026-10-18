package dev.sluide.git.cli

import java.nio.charset.Charset

/**
 * Why [name] cannot be given to git as an argument, or `null` if it can: empty, starting with `-` (an option to git)
 * or with characters [charset], the JVM's for arguments, cannot encode.
 */
internal fun refusal(
    name: String,
    charset: Charset = argumentCharset,
): String? =
    when {
        name.isEmpty() -> "An empty name cannot be passed to git."
        name.startsWith('-') -> "'$name' cannot be passed to git: it starts with '-'."
        !canPassArgument(name, charset) -> "'$name' cannot be passed to git in this locale."
        else -> null
    }
