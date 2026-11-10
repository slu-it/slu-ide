# Encrypted files: ideas for later

Explored while deciding [ADR 0162](../adr/0162-encrypted-files.md), not built. The feature is described in
[encrypted-files.md](../features/encrypted-files.md).

## More ways to unlock
- **FIDO2 / YubiKey slot:** a key slot whose key comes from the authenticator's `hmac-secret` extension, so a touch
  unlocks instead of the password. The JDK has no FIDO2 (CTAP) support; libfido2 through JNA would do it (JNA is
  already used for the macOS Keychain, ADR 0122). The slot wraps the same data key, so the password keeps working.
- **ML-KEM-768 recovery slot:** a recovery key pair made once and printed (or saved elsewhere); the slot holds the data
  key encapsulated to its public key. Here a post-quantum KEM fits, since there is a key pair to keep (JDK 25 has
  ML-KEM). Change Password must re-wrap it with the new data key.
- **Key file:** a random file as a second factor or instead of the password.
- **Passkeys:** the platform passkey APIs need an app signed with an Apple Team ID (and associated domains); Thoreau
  is not signed that way (see `secrets-later.md`).

## More file operations
- **Encrypt existing file:** turn a plain file into `<name>.sluenc` (and delete the plain one only after asking, since
  its blocks may stay on disk).
- **Save Decrypted Copy:** write the plain text to a chosen file, with a warning.

## Closing gaps the feature leaves open
- **Block remote images** in the Markdown preview of an encrypted file, so opening it never contacts a server.
- **Clipboard auto-clear:** clear the clipboard some seconds after copying from an unlocked tab, if it still holds
  what was copied.
- **Lock when the app is in the background** (all windows lose the focus, or the screen locks), beside the idle lock.
