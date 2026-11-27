# ssh's askpass prompts (ADR 0194)

The prompt texts ssh gives its `SSH_ASKPASS` program, measured with OpenSSH 10.0p2 and git 2.56.1 against a real sshd
on 127.0.0.1 (spike P0 of the ssh prompts round, 2026-11-27). Each `.txt` file holds one prompt byte for byte, as ssh
passes it as the askpass's only argument: no line break at the end, trailing spaces kept. The tests read them from
here, so the fixtures have one source.

| file | prompt |
|---|---|
| `unknown-host.txt` | a host not in `known_hosts` (four lines, a space after the final `?`) |
| `key-passphrase.txt` | the passphrase of a key (`-i k1`) |
| `unknown-host-reprompt.txt` | the host prompt again after an answer other than yes, no, the fingerprint or empty |
| `ssh-add-passphrase.txt` | ssh-add's own passphrase prompt |
| `ssh-add-bad-passphrase.txt` | ssh-add's prompt after a wrong passphrase |

## Facts the code relies on
- ssh runs the askpass with exactly one argument, and sets no `SSH_ASKPASS_PROMPT` for either prompt.
- The host prompt's text without its last line (and without the line break before it) is what the dialog shows.
- The passphrase prompt names the path as written in `-i`/`IdentityFile`: a relative one stays relative, `~` is
  expanded to the passwd home, `..` is not normalised, and it is cut to 100 characters (`%.100s`). A path may itself
  contain `'`: the path is what lies between the fixed prefix `Enter passphrase for key '` and the fixed suffix `': `.
- After an invalid host answer ssh asks `Please type 'yes', 'no' or the fingerprint: `, without limit, for any answer
  other than yes, no, the fingerprint or empty.
- Host answers: `yes` writes `known_hosts` ("Warning: Permanently added …"); `no`, empty, or askpass exit 1 give
  `Host key verification failed.`, and git exits 128.
- A wrong passphrase: ssh asks up to 3 times with the same text per key and connection, then tries the next identity or
  fails with `Permission denied (publickey,…)`; an empty answer or exit 1 stops asking for that key.
- ssh-add's prompts differ (no quotes, no "key"), and ssh-add asks again without limit after a wrong passphrase.
- Without batch mode ssh also tries a password login (and keyboard-interactive) when the server allows it:
  `<user>@<host>'s password: `, asked up to 3 times; an askpass exit 1 does not stop it but sends an empty password
  (sshd logs "Failed password" for each). So a session where git runs plain ssh turns those logins off
  (`-o PasswordAuthentication=no -o KbdInteractiveAuthentication=no`, measured in the P1 review).
- A killed askpass makes ssh fail at once (no new question); ssh waits for the askpass without a timeout of its own.
- A changed host key never runs the askpass: ssh refuses with its own message.
- The askpass must not leave a child holding its standard output: ssh waits for that pipe to close, so the wrapper
  script `exec`s java.
