# Idea: Saved secrets left for later

**Status:** idea, collected 2026-11-03 while building saved secrets ([ADR 0122](../adr/0122-saved-secrets.md),
[secrets.md](../features/secrets.md)); nothing decided or built. Each needs a decision of its own.

- **Windows:** keep the key with DPAPI (`CryptProtectData`) or in the Credential Manager, as one more `KeyBackend`;
  belongs to [Windows support](windows-support.md).
- **More secrets:** OAuth client secrets and tokens of the HTTP client, Docker registry logins, SSH tunnel passwords
  ([databases-later.md](databases-later.md)). Each decides where its value lives and how it is managed.
- **Key rotation:** a new key re-encrypts every saved value (the `v1:` prefix leaves room for a key id).
- **Removing unreadable values eagerly** after Forget All Saved Secrets, instead of when each one is next met.
- **Remembering the Save checkbox** after a failed login from the prompt (it starts unchecked again unless a password
  was saved before).
- **Hardening the JVM:** start the app with `-XX:+DisableAttachMechanism`, so a process of the same user cannot attach
  an agent to the running Thoreau and read the key from memory (the known limit in ADR 0122).
- **Developer ID signing** ([ADR 0011](../adr/0011-native-app-packaging.md)): the Keychain would recognise updates as
  the same app (no prompt after an update), and the data-protection keychain with access groups would become possible.
