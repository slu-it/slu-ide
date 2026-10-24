package dev.sluide.shellscripts

import dev.sluide.commandruns.ShellWords
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The command line that runs [script] directly (its shebang, or the shell without one, decides what runs it) with the
 * [arguments], each passed as one word: `unset -f cd 2>/dev/null; \cd '<folder>' && '<script>' 'arg'`. The folder
 * change is part of the command, since the login shell's startup files may change the folder. Those files may also
 * define a function or alias named `cd` (e.g. zoxide's): `unset -f` drops the function, the backslash skips the alias,
 * and the builtin runs, in bash, zsh and dash alike (`command cd` would look for a program `cd` in zsh) (ADR 0089).
 */
internal fun scriptCommand(
    script: Path,
    arguments: List<String>,
): String {
    val path = script.toAbsolutePath().normalize()
    val folder = checkNotNull(path.parent) { "$path has no folder" }
    val words = listOf(path.toString()) + arguments
    return "unset -f cd 2>/dev/null; \\cd ${ShellWords.quote(folder.toString())} && " +
        words.joinToString(" ", transform = ShellWords::quote)
}

/** Lets the owner of [file] execute it (`u+x`); the other permissions stay as they are. */
@Throws(IOException::class, UnsupportedOperationException::class)
internal fun addOwnerExecute(file: Path) {
    val permissions = Files.getPosixFilePermissions(file)
    if (permissions.add(PosixFilePermission.OWNER_EXECUTE)) Files.setPosixFilePermissions(file, permissions)
}
