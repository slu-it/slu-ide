# 0194. Git ssh prompts through an askpass helper on a Unix domain socket

- Status: accepted; amends [0043](0043-git-log-fetch.md) (never interactive: ssh's batch mode) and
  [0049](0049-push.md) (push runs as a fetch does)
- Date: 2026-11-27

## Context
Every remote git call of the git log ran `GIT_SSH_COMMAND="ssh -o BatchMode=yes"` (ADR 0043), so that ssh never asked
on a terminal nobody sees. Without an ssh agent holding the key, or with a host missing from `known_hosts`, fetch, pull
and push failed with "Permission denied (publickey)" or "Host key verification failed", and the user had to go to a
terminal. IntelliJ asks for the passphrase and the host key in dialogs. The user wants both: yes or no for an unknown
host, and a passphrase that can optionally be saved.

OpenSSH asks such questions through an `SSH_ASKPASS` program when told to (`SSH_ASKPASS_REQUIRE=force`): it runs the
program with the prompt as its only argument and reads the answer from its standard output; any exit value but 0 is "no
answer". Measured with OpenSSH 10.0p2 and git 2.56.1 against a real sshd (spike P0 of this round; the recorded texts
and the full list are in `modules/git/src/test/resources/ssh-askpass/`):
- ssh sets no `SSH_ASKPASS_PROMPT` for either prompt; the kind is told only by the text.
- Unknown host: four lines, the last `Are you sure you want to continue connecting (yes/no/[fingerprint])? `. Only an
  exact `yes` makes ssh add the host (it writes `known_hosts` itself); `no`, empty or exit 1 give "Host key
  verification failed."; any other text repeats `Please type 'yes', 'no' or the fingerprint: ` without limit.
- Passphrase: `Enter passphrase for key '<path>': `, the path as written in `-i`/`IdentityFile` (relative stays
  relative, `~` expanded, `..` kept), cut to 100 characters; a path may contain `'`. A wrong passphrase is asked for
  again up to 3 times per key and connection with the same text; empty or exit 1 stops asking for that key.
- ssh-add's prompts differ (`Enter passphrase for k1: `) and repeat without limit.
- Without batch mode ssh also tries a password (and keyboard-interactive) login where the server allows it:
  `<user>@<host>'s password: `, up to 3 times; exit 1 does not stop it but sends an empty password, which the server
  logs as a failed login (measured in the P1 review).
- git itself asks for https credentials through the first of `GIT_ASKPASS`, `core.askPass` and `SSH_ASKPASS` that is
  set, and runs it only if it is not empty.
- A changed host key never runs the askpass. ssh waits for the askpass without a timeout of its own; a killed askpass
  makes it fail at once; a child of the askpass that holds its standard output open makes ssh wait for it.

## Decision
- **Covered calls:** `GitRemotes.fetch`, `GitPush.push`, `GitPush.deleteRemoteRefs` (its `ls-remote` too) and
  `GitPush.pushTags` take `ssh: SshAccess = SshAccess.Batch`. `Batch` keeps the environment of ADR 0043 byte for byte;
  `RemoteSourceCache` (ADR 0076) stays on it. A `Prompting` session (from `SshPrompts.open(root, ui, onCancel)`, a
  singleton bean in package `dev.sluide.git.ssh`) replaces it with `SSH_ASKPASS=<wrapper>`, `SSH_ASKPASS_REQUIRE=force`,
  `SLUIDE_ASKPASS_SOCKET=<socket>` and `SLUIDE_ASKPASS_TOKEN=<64 hex digits>`, and no `BatchMode`: also when the user
  configured `core.sshCommand`, `ssh.variant` or `GIT_SSH*` (only OpenSSH reads the variables), and replacing an
  `SSH_ASKPASS` of the user's desktop. Where batch mode would be set (git runs plain ssh), the session sets
  `GIT_SSH_COMMAND=ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no` instead, so that ssh never
  sends the empty password of a "no answer". And `GIT_ASKPASS=` (empty) unless the user set `GIT_ASKPASS` or
  `core.askPass`, so that git's own https prompts end the chain before the session's helper and fail with today's
  message. `sshEnvironment(environment, settings, access)` decides it; the settings come from one
  `git config -z --name-only --get-regexp` for `core.sshCommand`, `ssh.variant` and `core.askPass` (unreadable: ssh
  counts as not chosen, `core.askPass` as set). After each git process of a session, `ended(success)` is called
  (success for exit 0): the answers given since the previous end belong to that process, and count as good only if it
  succeeded (`SshPassphrases` keeps a passphrase only then).
- **Helper:** a small Kotlin `main` (`dev.sluide.git.askpass.Askpass`), compiled as a source set of its own into
  `askpass.jar` and carried with `kotlin-stdlib.jar` as resources of the `git` module, like the JShell agent (ADR
  0191). `AskpassFiles.install` copies them to `~/.sluide/git/askpass/<first 16 hex digits of the SHA-256 of the jars
  and the java path>/`, each through a temporary file and `ATOMIC_MOVE`, with the wrapper script `askpass` (0700):
  `#!/bin/sh` and `exec '<java.home>/bin/java' -cp '<askpass.jar>:<kotlin-stdlib.jar>' dev.sluide.git.askpass.Askpass
  "$@"`, every path in single quotes (`'` as `'\''`); on Linux prefixed with `LC_ALL=C.UTF-8`, so that the helper's
  JVM decodes the prompt (a key path) as UTF-8 whatever the user's locale (macOS's JVM always does). `exec`, so that
  no shell lingers on ssh's pipe and the helper's parent is ssh. Thoreau's bundled runtime keeps `bin/java` (ADR
  0011). The helper never logs.
- **Channel:** `AskpassChannel`, an internal singleton bean under `SshPrompts` and `SshPassphrases` (so that both can
  use it without a constructor cycle), holds the helper's files, the server and the registered tokens. Each registration
  gets a random 32-byte token and its environment. Besides the sessions, `oneShot(answer)` registers a token for one
  `ssh-add` run: it answers the first prompt that starts with `Enter passphrase for ` with a copy of the passphrase and
  every other or later prompt "none" (ssh-add asks again without limit); closing it zeroes the copy. The server is a
  Unix domain socket (`UnixDomainSocketAddress`), started on the first registration, in a fresh directory with
  `rwx------` under `java.io.tmpdir`, or under `/tmp` when the socket's path could be longer than 100 bytes (macOS
  allows 104); closed and removed when Thoreau quits. One request per connection, at most 8 KiB: the token, ssh's pid
  (the helper's parent), `SSH_ASKPASS_PROMPT` (empty) and the prompt, each length-prefixed; the reply is an answer's
  bytes, which the helper prints with a line break (exit 0), or "none" (exit 1, also on any error, a missing variable or
  argument). The server compares tokens with `MessageDigest.isEqual`; an unknown or closed session gets "none". Each
  connection runs on a virtual thread `sluide-git-askpass-prompt`, which is where the UI is called (never the FX or the
  git-write thread).
- **Routing by the prompt's text** (`sshPrompt`): `KeyPassphrase` (fixed prefix `Enter passphrase for key '` and
  suffix `': `, the path between them, resolved against the repository and normalized) goes to `SshPassphrases`, which
  asks the session's `SshPromptUi` (this ADR ships it as a stub that always asks and keeps nothing); `UnknownHost`
  (starts with `The authenticity of host `, last line starts with `Are you sure you want to continue connecting`) goes
  to `SshPromptUi.confirmHost` with the text without its last line, and Connect answers exactly `yes`. A Cancel in
  either calls `onCancel` first, then answers "none". Everything else (PINs, the host re-prompt, ssh-add's texts
  outside a one-shot, a password prompt of the user's own ssh command) gets "none" without a dialog and without a
  cancel. Only the prompt's kind is logged, at debug.
- **The outcome of a Cancel is the caller's:** `onCancel` cancels the operation's `GitCancellation`, which only sets a
  flag that the git runner polls; ssh fails at once on "none", so git may exit 128 before the poll stops it, and the
  result then says failed, not cancelled. Callers set their own "the user cancelled" flag in `onCancel` and decide the
  outcome by it ("Fetch cancelled.", no error dialog), never by the git result's `cancelled`.
- **Closing a session** (the operation ended or was cancelled) unregisters its token and interrupts every connection
  thread waiting on the session's UI; the UI closes its dialog on the interrupt and the prompt gets "none".
- **Server down:** if the helper cannot be installed or the socket not bound, `open` returns `Batch`: logged once at
  warning, with the exception's class only (its message names the user's folders), and not tried again. If accepting
  fails later (other than by closing, e.g. out of file descriptors), the server logs the class once, closes its
  socket (which resets the connections waiting in the backlog, so their helpers exit 1 instead of waiting forever)
  and the channel turns unavailable: later sessions are `Batch`.
- **Why a Kotlin helper and a Unix socket:** no port (a TCP port on 127.0.0.1 would be open to every local user, and
  needs its own token in a path); the socket's directory is the user's only, so the file permissions keep others out
  before the token is even read; the helper and the server share one codec and the helper's logic runs in the test JVM.
  A shell script with a FIFO would need `mkfifo` and careful quoting of answers, and could not be tested in the JVM. A
  call of the helper takes about 36 ms warm (51 ms the first time; JVM start and a failed connect, measured 8 times on
  the sandbox's Linux ARM with JDK 25), noise next to a dialog.

## Consequences
- Fetch, pull, push, remote deletion and tag push work with a key that is not in an agent and with an unknown host,
  asked in Thoreau's dialogs (git log, a later step of this round), still never on a terminal.
- Accepted limits:
  - A key path longer than 100 characters reaches Thoreau cut as ssh shows it: memory and saving still work by that
    name, adding it to the agent does not (the file is not found).
  - The server's `LoginGraceTime` (120 s by default) closes the connection while a dialog waits; the operation then
    fails with ssh's "Connection closed by …".
  - plink and other non-OpenSSH commands ignore the variables; they behave as they did with the user's settings.
  - With the user's own ssh command (`core.sshCommand`, `ssh.variant`, `GIT_SSH*`) Thoreau does not turn password
    logins off: that ssh may try one and send the empty password of a "no answer" (up to three times; the server logs
    failed logins).
  - ssh may give up on a key after its three tries and log in with another identity: the process succeeds, and the
    first key's last (wrong) answer counts as good.
  - The socket path must fit macOS's 104 bytes: a long `java.io.tmpdir` falls back to `/tmp`.
  - On Linux without a `C.UTF-8` locale the helper's JVM decodes a non-ASCII key path in the C locale and the path
    arrives mangled (glibc 2.35 and later, Debian and Fedora have it).
  - A crash leaves the socket's empty folder in the temporary folder (owner-only); nothing sweeps it.
- The helper files stay under `~/.sluide/git/askpass/`, one folder per version and runtime.
