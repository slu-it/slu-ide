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
  succeeded (`SshPassphrases` remembers, saves and adds to the agent only then).
- **Helper:** a small Kotlin `main` (`dev.sluide.git.askpass.Askpass`), compiled as a source set of its own into
  `askpass.jar` and carried with `kotlin-stdlib.jar` as resources of the `git` module, like the JShell agent (ADR
  0191). `AskpassFiles.install` copies them to `~/.sluide/git/askpass/<first 16 hex digits of the SHA-256 of the jars
  and the java path>/`, each through a temporary file and `ATOMIC_MOVE`, with the wrapper script `askpass` (0700):
  `#!/bin/sh`, `unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS` (meant for the user's own JVMs; the JVM would
  print "Picked up …" for each into ssh's and so git's error output, found in the real-app check) and
  `exec '<java.home>/bin/java' -cp '<askpass.jar>:<kotlin-stdlib.jar>' dev.sluide.git.askpass.Askpass
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
  answers it (next bullet); `UnknownHost`
  (starts with `The authenticity of host `, last line starts with `Are you sure you want to continue connecting`) goes
  to `SshPromptUi.confirmHost` with the text without its last line, and Connect answers exactly `yes`. A Cancel in
  either calls `onCancel` first, then answers "none". Everything else (PINs, the host re-prompt, ssh-add's texts
  outside a one-shot, a password prompt of the user's own ssh command) gets "none" without a dialog and without a
  cancel. Only the prompt's kind is logged, at debug. A UI that fails (throws a RuntimeException: its dialog cannot be
  made or shown) is no Cancel: logged at warning with the exception's class, "none" without `onCancel`, so the
  operation fails with ssh's message; `null`/`false` from the UI mean the user's Cancel only.
- **Passphrases** (`SshPassphrases`, called by the session with its id, ssh's pid as the connection, and the key file):
  - *State:* a memory cache per key file, app-wide until Thoreau quits (`CharArray`s, zeroed on `destroy()`); per
    session the pending answers per key (source MEMORY, SAVED or ENTERED, the reply's Save and Add-to-agent flags,
    whether the current git process used it) and per (session, connection, key) how often ssh asked.
  - *Asked again = wrong:* if ssh asks for a key a second time on the same connection, the answer it got was wrong.
    The pending answer is dropped and, if memory still holds the same value, memory loses it too. The notice is
    SAVED_REJECTED for a saved answer, whose entry is also removed, and WRONG for the others; then the UI is asked.
  - *Order otherwise:* the session's pending answer (an earlier process of the same session: Fetch All after a failed
    remote, the delete's `ls-remote` before its push, or the same fetch on another remote's connection), then memory,
    then the saved value (`Secrets.decrypt`: unreadable is removed and counts as nothing; no key keeps the value and
    asks), then the UI with no notice. The request names why saving is unavailable (`Secrets.saveUnavailableReason()`)
    and why the agent is (`SSH_AUTH_SOCK` missing or empty in git's environment: "no ssh agent"; the key file is not a
    regular file, e.g. cut at 100 characters: "key file not found"). A box the request calls unavailable counts as
    unchecked whatever the reply says.
  - *Commit on `ended(true)`:* every answer the process used goes into memory at once (a copy). Then, on a virtual
    thread `sluide-git-ssh-passphrase`, because `ended` runs on the thread that ran git: an ENTERED answer with Save
    checked is encrypted (`git/ssh-passphrase/<absolute normalized path>`) and written to `~/.sluide/shared.json`
    (`git.sshPassphrases`; failure: `notSaved(reason)`), and with Add to agent checked `ssh-add -- <key>` runs. Each
    acts once. `ended(false)` commits nothing and keeps the pending answers for the session, so that the asked-again
    rule still catches a wrong one. A session is `opened` on `SshPassphrases` when it is registered, before its
    environment reaches git, and only an opened session gets answers: a call after `closed` (which zeroes and forgets
    the session's answers) or after `destroy` gets none and no dialog, so no state comes back and nothing grows with the
    number of operations. A commit in flight has its own copy. `notSaved` and `notAddedToAgent` therefore arrive on the
    thread `sluide-git-ssh-passphrase`, usually after the session closed; the UI shows them without tying them to a
    session and without blocking. When ssh's second ask drops a saved answer, only that value is removed, not a newer
    one saved meanwhile.
  - *ssh-add* (`SshAdd`): a one-shot token of the channel answers its first `Enter passphrase for ` prompt, with git's
    environment plus the token's; stdin is `/dev/null`, stdout discarded, stderr read up to 8 KiB, 30 s, then stopped.
    A non-zero exit, a timeout or a missing ssh-add becomes `notAddedToAgent(message)`.
  - *Known limit:* ssh may give up on key A after three wrong answers and log in with another identity; the process
    succeeds, and A's last answer counts as good.
- **The outcome of a Cancel is the caller's:** `onCancel` cancels the operation's `GitCancellation`, which only sets a
  flag that the git runner polls; ssh fails at once on "none", so git may exit 128 before the poll stops it, and the
  result then says failed, not cancelled. Callers set their own "the user cancelled" flag in `onCancel` and decide the
  outcome by it ("Fetch cancelled.", no error dialog), never by the git result's `cancelled`.
- **Closing a session** (the operation ended or was cancelled) unregisters its token and interrupts every connection
  thread waiting on the session's UI; the UI closes its dialog on the interrupt and the prompt gets "none".
- **The end of a git process** (`ended`) does the same for the prompts still open, but keeps the session: a session runs
  one git process at a time and prompts come only from it, so an open prompt belongs to a process that is gone (e.g.
  git, ssh and the helper stopped at `FETCH_TIMEOUT` while the operation goes on); it gets "none" without `onCancel`,
  and an answer that arrives after the end is dropped (not pending for the next process, so its success cannot commit
  it).
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
  asked in Thoreau's dialogs, still never on a terminal. The git log opens one session per run of an operation that
  contacts a remote (Add Remote's fetch included) and closes it when the run ends, however it ends.
- Accepted limits:
  - A key path longer than 100 characters reaches Thoreau cut as ssh shows it: memory and saving still work by that
    name, adding it to the agent does not (the file is not found).
  - The server's `LoginGraceTime` (120 s by default) closes the connection while a dialog waits. ssh notices only
    once the dialog is answered (measured in the real-app check: the dialog stays open), so the operation then fails
    with ssh's "Connection closed by …"; an unanswered dialog closes with the 10-minute timeout.
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
