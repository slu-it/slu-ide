# Saved secrets

**Module:** `modules/secrets` (package `dev.sluide.secrets`; the page in `dev.sluide.secrets.settings`), decision in
[ADR 0122](../adr/0122-saved-secrets.md)

Some secrets can be saved so they are not asked for again after a restart. Saving is always the user's choice: the
"Save password" and "Save passphrase" checkboxes are off by default.

## What can be saved
- Database passwords: "Save password" in the connection dialog and in the "Connect to X" prompt, see
  [databases.md](databases.md).
- HTTP client certificate passphrases: "Save passphrase" in the passphrase prompt, see
  [http-client.md](http-client.md).
- Other secrets (OAuth client secrets and tokens) are not saved by Thoreau; each needs its own decision.

## How they are stored
- Each value is encrypted with AES-256-GCM and written as text (`v1:` + base64) into the JSON file of its feature
  under `~/.sluide`. The context (connection, certificate files) is bound into the value, so it only decrypts where it
  was written.
- One random key per installation is the only item in the OS secret store: the macOS Keychain ("Thoreau Safe Storage")
  or, on Linux, the Secret Service through `secret-tool` (package libsecret-tools). Without `secret-tool` or on other
  systems the checkboxes are disabled; the checkbox shows the reason, e.g. "(Keychain access denied)".
- The key is read on first use and kept in memory until the app quits. macOS asks once per update (Always Allow), not
  once per secret.
- Deny or Cancel on the Keychain prompt: the store is not asked again until restart or Try Again on the Secrets page;
  saved secrets are asked for as without them and saving is disabled with the reason.
- An unreadable value (other machine, restored `~/.sluide`, forgotten key) counts as nothing saved: it is removed when
  met and the secret is asked for. Without a key (denied, no store) the value is kept.

## Settings → Secrets
- Store line: "Store: macOS Keychain" or "Store: Secret Service (secret-tool)"; "Store: <name>, access denied" with
  Try Again; or "Not available: <reason>" (needs secret-tool, not supported on this system).
- Saved passphrases of each contributing source (a `SavedSecretSource` bean), one row per entry with its label
  (details as tooltip) and Remove. Remove only marks the row (struck through, the button becomes Undo); OK or Apply
  removes the marked ones, Cancel drops the marks. Database passwords are managed on their connection cards.
- Forget All Saved Secrets…: off while there is no store (no store, needs `secret-tool`); asks (Cancel is the default), then deletes the key from the store and from memory. Every
  saved value becomes unreadable and is removed when next met. This acts at once, not on Apply, and shows the result
  below the button.

## Limits
- Linux's Secret Service has no per-application check: any process of the user can read the item once the keyring is
  unlocked.
- Code of the same user injected into the running JVM (attach mechanism, debugger) gets the key in memory.
- Windows is not supported.
