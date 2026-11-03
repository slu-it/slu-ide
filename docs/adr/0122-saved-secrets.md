# 0122. Saved secrets: values encrypted in the JSON files, one key in the OS secret store

- Status: accepted; amends [0121](0121-databases-tab.md) and [0028](0028-http-client-secrets.md)
- Date: 2026-11-03

## Context
Database passwords live in memory until the app quits ([ADR 0121](0121-databases-tab.md)), HTTP client certificate
passphrases until the window closes ([ADR 0028](0028-http-client-secrets.md)); after that they are asked for again.
Writing them as plain text into `~/.sluide` is not acceptable, even though the files are only owner-readable
(`JsonFiles` writes through temp files).

Storing each secret as its own item in the macOS Keychain would ask the user on every item: Thoreau is only ad-hoc
signed ([ADR 0011](0011-native-app-packaging.md)), so every update is a new app to the Keychain, and each item
would need its own "Always Allow" after each update.

## Decision
**Values and key** (new foundation module `secrets`, `Secrets` bean)
- A saved secret is encrypted with AES-256-GCM (`javax.crypto`, no dependency) and stored as text inside the JSON file
  of its feature: `v1:` + standard base64 of nonce ‖ ciphertext ‖ tag, with a random 12-byte nonce per value and a
  128-bit tag (`SecretCipher`). The caller's context string (`databases/<connection id>/password`,
  `http-client/passphrase/<cert path>\n<key path>`) is the GCM associated data, so a value decrypts only where it was
  written. Plain text never passes through a `String`; buffers holding it are zeroed.
- One 32-byte random key per installation from `SecureRandom`, no KDF (it is not a human password). It is the only
  item in the OS secret store, so an update asks once.
- The key is read lazily, on the first encrypt or decrypt, under one lock (other callers wait while a system prompt
  is open), and kept in memory until the app quits or Forget All. `encrypt`, `decrypt` and `forgetAll` block and
  never run on the FX thread; `encrypt` returns the reason to show when it cannot save. `saveUnavailableReason` is
  cheap and never touches the store.

**Stores** (`KeyBackend`, chosen by `KeyBackends.forPlatform`)
- macOS: a generic password with service and label "Thoreau Safe Storage" and account "Thoreau" (`MacKeychain`),
  through `Security.framework` via JNA (the JNA version pty4j already brings). Not through the `security` CLI: the
  item's access list would then trust `security`, which any process can run to read it. Not the data-protection
  keychain: it needs a Team ID, which an ad-hoc signed app does not have.
- Linux: `secret-tool` of libsecret-tools (`SecretToolBackend`), label "Thoreau Safe Storage", attributes
  `application sluide` and `name safe-storage`. The key goes through stdin and stdout, never into the arguments; a
  call ends after 120 s (an unlock prompt may be open). Without `secret-tool` on the `PATH` nothing can be saved
  ("needs secret-tool").
- Other systems: no store (`NoBackend`, "not supported on this system").

**Missing, denied, failed**
- A new key is created only when the store says the item does not exist (macOS `errSecItemNotFound`; secret-tool:
  `lookup` exits 1 with empty stdout and stderr and an empty `search` confirms nothing is stored), and only to encrypt;
  decrypting never creates one. A dismissed unlock prompt prints nothing either, so the empty `lookup` is followed by
  `search`, which lists a locked item without unlocking it: a listed item is Denied ("Secret Service locked"), never
  Missing. `clear` of a locked item also exits 1 silently, so `delete` is confirmed by `search` too. A duplicate on
  create (another Thoreau process was first) reads the store again. A key is never created over an existing one,
  except in a race: two processes that both find the item missing store at the same moment and the later key replaces
  the first (accepted limit for secret-tool, which has no atomic create; no lock file).
- Deny or Cancel on the store's prompt makes the denial sticky: the store is not asked again until a restart or
  Settings → Secrets → Try Again. Saved secrets are then asked for as without them, and saving is disabled with the
  reason. Any other read error, and a store call that throws, fails only that call. Failures are logged once with
  their reason (of an exception only its class), never with keys, values or contexts.
- An unreadable value (unknown prefix, bad base64, too short, failed tag: written on another machine, restored
  `~/.sluide`, reset or forgotten key) counts as nothing saved: the feature removes it when met and asks for the
  secret. Without a key (denied, no store, read error) the value is kept.
- Forget All deletes the key from the store and zeroes it in memory; every saved value becomes unreadable and is
  removed when next met.

## Consequences
- One "Always Allow" per update on macOS, however many secrets are saved.
- Linux's Secret Service has no per-application check: any process of the user can read the item once the keyring is
  unlocked.
- Known limit: code of the same user injected into the running JVM (attach mechanism, debugger) gets the key in
  memory. Starting the launcher with `-XX:+DisableAttachMechanism` is an idea for later.
- Windows is out of scope; it would need its own store (Credential Manager or DPAPI).
- No key rotation; OAuth tokens and other secrets each need their own decision.
- The database passwords (ADR 0121) and the HTTP client's certificate passphrases (ADR 0028) can be saved with it;
  those ADRs record the change in their own amendments.
- Added to [../index.md](../index.md).
