package dev.sluide.docker.compose

/** One variable of the Environment table: put into the process environment of every compose command. */
data class ComposeVariable(
    val name: String,
    val value: String,
)

/**
 * What the compose view remembers for one compose file (stored per file, see [ComposeStore]).
 *
 * @property detached whether Up runs with `-d`.
 * @property profiles the profiles to enable (`--profile`).
 * @property build Up flag `--build`.
 * @property forceRecreate Up flag `--force-recreate`.
 * @property removeOrphans Up flag `--remove-orphans`.
 * @property projectName override of the project name (`-p`); `null` = compose's own choice.
 * @property envFiles the `--env-file`s, as paths relative to the compose file's folder (or absolute); `null` = the
 *   default: the `.env` beside the file, which compose finds by itself.
 * @property variables the environment variables, in table order; they beat every env file and the `.env`.
 */
data class ComposeOptions(
    val detached: Boolean = true,
    val profiles: Set<String> = emptySet(),
    val build: Boolean = false,
    val forceRecreate: Boolean = false,
    val removeOrphans: Boolean = false,
    val projectName: String? = null,
    val envFiles: List<String>? = null,
    val variables: List<ComposeVariable> = emptyList(),
)

private val VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val PROJECT_NAME = Regex("[a-z0-9][a-z0-9_-]*")

/** Whether [name] is a POSIX environment variable name: `[A-Za-z_][A-Za-z0-9_]*`. */
fun isValidVariableName(name: String): Boolean = VARIABLE_NAME.matches(name)

/** Whether [name] is a compose project name: lowercase letters, digits, `-` and `_`, starting with a letter or digit. */
fun isValidProjectName(name: String): Boolean = PROJECT_NAME.matches(name)

/**
 * Whether [name] is usable as a profile name: letters, digits, `_`, `.` and `-`, starting with a letter or digit
 * (compose's rule). Checked because a profile goes onto the command line after `--profile`.
 */
fun isValidProfileName(name: String): Boolean = PROFILE_NAME.matches(name)

private val PROFILE_NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
