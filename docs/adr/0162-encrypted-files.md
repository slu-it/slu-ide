# 0162. Encrypted files

- Status: accepted; deviates from [0020](0020-editing-and-saving-files.md) for the save of encrypted files
- Date: 2026-11-10

## Context
Notes with passwords, recovery codes or private text should not lie on disk as plain text, also not in the scratch
folder ([ADR 0066](0066-scratch-files.md)). The user wants text files that are stored encrypted, decrypted only in
memory while a tab shows them, locked again by hand or when the tab or window closes, and protected by a password that
is stored nowhere. They are created from the scratch type picker. The user also asked about post-quantum algorithms:
JDK 25 ships ML-KEM and ML-DSA.

Saved secrets ([ADR 0122](0122-saved-secrets.md)) solve a different problem: one random key per installation in the
OS secret store, no password. Here the password is the only key, so the file must resist offline guessing, and it must
open on any machine with the password.

## Decision
**Algorithms**
- The content is encrypted with AES-256-GCM (`javax.crypto`, `AES/GCM/NoPadding`, 128-bit tag) under a random 32-byte
  data key (DEK) per file. The DEK is wrapped with AES-256-GCM under a key-encryption key (KEK) that Argon2id derives
  from the password and a random 16-byte salt. All randomness comes from one `SecureRandom`.
- **Not ML-KEM or ML-DSA.** Both need a key pair, and a password-only design has none to keep: a KEM would need a
  private key stored somewhere, which then needs a password again, and a signature adds nothing that the GCM tag does
  not already give (only the password holder can write a valid file). They add capabilities (recovery keys, sharing,
  signed authorship), not strength.
- **AES-256 is already post-quantum.** Grover's algorithm halves the effective key length of a symmetric cipher, which
  leaves about 128 bits for AES-256, and Grover parallelizes badly. The weak point is the password, not the cipher:
  an attacker with the file guesses passwords, which costs one Argon2id run per guess on any computer.
- **Argon2id through BouncyCastle.** The JDK has no Argon2; PBKDF2 (in the JDK) needs no memory, so GPUs and ASICs guess
  orders of magnitude faster against it. Argon2id is memory-hard and the winner of the Password Hashing Competition
  (RFC 9106). It comes from `bcprov-jdk18on`, used only through its lightweight API
  (`Argon2BytesGenerator`, `Argon2Parameters`); no JCE provider is registered, so the signed jar's provider checks and
  the fat jar's nesting do not matter and no other code sees BouncyCastle's algorithms.
- **Parameters are stored per slot and capped.** The default is RFC 9106's second recommendation: 64 MiB, 3 passes,
  parallelism 4 (about 0.2 s on an M-class machine, up to about 1 s on slower ones; all crypto calls block and run off
  the FX thread). A reader
  refuses, before running Argon2, memory above 256 MiB or below 8 KiB per lane, passes 0 or above 10, parallelism 0 or
  above 16 ("unsupported parameters"), so a crafted file cannot make Thoreau allocate gigabytes or spin for minutes:
  one run at the caps took about 1.5 s on the 12-core Linux machine where the defaults took about 0.1 s (BouncyCastle
  computes the lanes one after another, so the time grows with memory times passes, about 13 times the default's), so
  a few seconds up to about 10 s on slow machines. The caps leave room for four times the default memory and more than
  three times its passes. Stronger defaults later need no new format.

**Format v1** (`.sluenc`, binary, big-endian; `SluencFormat`)
- `SLUENC` (6 ASCII bytes) | version u8 = 1 | slot count u8 | slots | body nonce (12) | body ciphertext and tag.
- The password enters Argon2id as the UTF-8 of its Unicode NFC form, so composed and decomposed accents (as macOS and
  other systems type them) are the same password. A password of characters below U+0300 only is NFC already and is
  encoded directly; any other is normalized through a String (see the wiping limits below).
- Every slot starts with type u8 | length u16 (the number of slot bytes that follow), then its own fields.
- Password slot: type 1 | length 85 | memory KiB u32 | passes u32 | parallelism u8 | salt (16) | wrap nonce (12) |
  wrapped DEK (32 + 16 tag). The wrap's associated data is the slot's bytes before the wrapped DEK (type and length
  included), so a changed parameter, salt or nonce fails like a wrong password.
- The body's associated data is everything from the magic to the end of the last slot. The file name is in no
  associated data, so renames and moves keep working.
- **Key slots.** v1 writes exactly one password slot; readers accept 1 to 8 slots, skip slots of types they do not
  know by their length, and use the first password slot. No password slot, or one whose length is not a password
  slot's, is damage. The list is room for later slots that wrap the same DEK differently: a FIDO2 `hmac-secret` slot
  (a YubiKey) and an ML-KEM-768 recovery-key slot, where a post-quantum KEM does fit, since the recovery key is a key
  pair printed once. A new slot type needs no new format version: older readers skip it, still open the file with the
  password, and keep it unchanged when they save.
- Failures (`SluencFailure`, fixed texts): "Wrong password." (the wrap's tag fails), "Not a Thoreau encrypted file."
  (0 bytes, too short or another magic), "Written by a newer Thoreau (format N)." (version above 1), unsupported
  parameters, "The file is damaged." (version 0, malformed or truncated, or the body's tag fails after a good unwrap),
  and a key mismatch (the body does not decrypt with the tab's DEK and the slot differs: the password was changed
  elsewhere).
- Plain text is the UTF-8 of the text with its line endings kept. Each save encrypts the body again with the same DEK
  and a fresh random nonce, writing the file's slot list unchanged, slots of unknown types included.
- **Change Password makes a new DEK** and a new salt and nonces, after checking the current password against the file
  on disk. A copy of the old file plus the old password, or a tab still holding the old DEK, cannot read new versions.
  It writes only the new password slot: other slots wrap the old DEK, so a later version that adds slot types must
  re-wrap them on Change Password.

**Saving** (`AtomicFileWrite`)
- Unlike the editor's save in place ([ADR 0020](0020-editing-and-saving-files.md)), an encrypted file is written
  atomically: a hidden temp file in the same folder (`Files.createTempFile`, owner-only), written and forced to disk
  through a `FileChannel`, then moved over the target with `ATOMIC_MOVE`; the folder is then forced too, where the
  platform can open a folder (not on Windows). A crash mid-write would otherwise leave a file that no password opens.
  The replaced file's POSIX permissions are kept; a new file is owner-only (600). A failure deletes the temp file and
  leaves the target as it was. File systems without POSIX permissions skip those
  steps. Like the conversions' atomic write, a symbolic link at the target is replaced, not written through.

**Secrets in logs and memory**
- Passwords, keys, content, and exception messages that could hold them are never logged
  ([ADR 0060](0060-logging-conventions.md)). `SluencException` messages are the fixed texts above.
- Best-effort wiping: the crypto core never keeps or zeroes the caller's password array (the caller owns and zeroes
  it), and zeroes every array it allocates for key material (KEK, DEK copies, Argon2 output, the password's UTF-8) in
  `finally` blocks. A `FileKey` holds the DEK; `close()` zeroes it, and a closed key refuses use. `SecretKeySpec` is
  made per operation and dropped. The tab takes the password from its field, clears the
  field at once and zeroes the array after the derivation; on lock and close the inner view is wiped (editor text,
  undo history, saved text, find state, rendered preview, the WebView loads an empty page), disposed and dropped.
  Plain bytes are zeroed once used: the encoded text a save encrypts, the decryption that checks a change on disk,
  and the decrypted bytes the inner view reads, which it decodes at once on the FX thread; the session zeroes those on
  the next read, on lock and on close. The decoded text is a `String` and stays (see below).
- Not achievable on the JVM, and said so on the feature page: immutable `String`s (RichTextFX's text, the
  `PasswordField`'s text, the WebView's DOM; a password with a character from U+0300 upward passes through a String
  during normalization), the key copy inside `SecretKeySpec` (its `destroy()` throws `DestroyFailedException` on
  JDK 25), the cipher's internal key schedule, the copies a moving garbage collector leaves, swap, and crash or core
  dumps. The always-on JFR buffer ([ADR 0127](0127-in-process-diagnostics.md)) holds no heap content, and Thoreau
  offers no heap dump.

**Files and types**
- The extension is `.sluenc`; the name before it gives the inner type (`notes.md.sluenc` is Markdown). The scratch type
  picker gets Plain Text, Markdown, JSON and YAML (encrypted), shown with a lock file icon. Creating one asks for a new
  password (twice, at least 12 code points, a weak/ok/strong hint, a warning that a forgotten password loses the
  content); the new file opens unlocked with the key handed over, so the password is derived once. Every later open,
  and a `.sluenc` file anywhere (renamed, moved into a project), starts locked; the viewer is chosen by the extension.

**Locked and unlocked tabs**
- A locked tab shows a password field and Unlock, with the lock icon in its header; it is never dirty and Save and
  reload do nothing. Unlocking runs Argon2 off the FX thread.
- An unlocked tab shows a strip (Encrypted · unlocked, Lock, Change Password…) above the inner type's normal view
  (plain-text code view, or the Markdown, JSON or YAML preview); dirty marker and Save behave as usual.
- **Lock** asks first when there are unsaved changes (Save, Don't Save, Cancel; Cancel is the default). **Lock All
  Encrypted Files** locks every unlocked encrypted tab in all windows, asking once for the unsaved ones; tabs whose
  save fails stay unlocked, and one message says how many. **Idle lock**
  saves and locks after N minutes without a key press, mouse press or scroll in the tab (Settings → Editor → Encrypted
  Files, default 10, 0 = off); if that save fails the tab stays unlocked and shows the error. Closing a tab or a window
  asks the usual question, then wipes.
- **Blocked while unlocked:** the previews' Export menu; every code-editor extension (Read Aloud, Translate, Open in
  Codecs or Converting, code intelligence) unless it opts in for confidential content; and the view does not offer
  its text to other windows' tools (`TextEditableView`).
- **Not blocked**, and stated on the feature page: Copy to the clipboard, and remote images in the Markdown preview
  (a request to the image's host reveals that the note was opened, not its text).
- **How the tab is built** (`EncryptedFileView`): a wrapper `EditableFileView`, not a `TextEditableView`, around the
  view that `FileViewers.contentViewerFor` picks for the inner name (`notes.md`), opened on the virtual path beside
  the file with a confidential `FileContent` that decrypts on read and encrypts and writes atomically on write. Its
  `dirty` is one stable property, bound to the inner view's while unlocked. Diffs and change markers are never passed
  on (`CodeView` would hand the virtual path to the host). Opening the tab neither reads nor decrypts the file.
- The decisions live in the FX-free `EncryptedSession` (ADR 0084): locked, unlocking, unlocked; the key it owns; the
  failure and warning texts; what a change on disk means. An unlock result that arrives after a lock, a newer unlock or
  the close is dropped and its key closed. `Sluenc.unlock` already checks that the body decrypts, so the tab does not
  decrypt a second time to verify. An `OutOfMemoryError` during Argon2 (a crafted file may ask for 256 MiB) shows "Not
  enough memory to unlock."; any other unexpected failure is logged by its type only.
- A new file's key reaches its first tab through the app-wide `EncryptedFiles` registry (`handOver`, taken once by
  path); one not taken within a minute, or replaced by a newer one for the same file, is closed.

**Several windows or tabs on one file**
- Each tab locks and unlocks on its own. A change on disk is decrypted with the tab's DEK first: if it fits, the usual
  reload or conflict banner of ADR 0020 follows. If it no longer fits (key mismatch: the password was changed
  elsewhere), a clean tab locks with "Changed elsewhere with another password. Unlock again."; a dirty tab warns, and
  Save then asks before replacing the file with the tab's version under the password it was unlocked with (Replace or
  Cancel, Cancel the default).
- A changed file that the tab's key cannot read for another reason (damaged, not `.sluenc`, newer format) is handled
  the same way, with that failure's text. Cancel on the Replace question fails the save ("Replacing the changed file
  was cancelled."), so closing the tab with Save keeps it open with its changes instead of dropping them; a save that
  replaces the file ends the warning, and so does a later change on disk that the key reads again.
- Change events can come late (macOS polls) or never, so every save reads the file first
  (`EncryptedSession.checkDisk`): bytes the key cannot read set the warning and the save asks Replace. The session
  also keeps the bytes the tab's saved state stands for (written, or read while clean; a read with unsaved changes is
  the inner view's conflict and does not count). With unsaved changes the idle lock saves only while the file still
  holds those bytes: never over a change from another tab or program, with any password, nor over a deleted file.

**Empty encrypted scratches**
- An encrypted scratch closed unlocked with empty saved content is deleted like an empty scratch (ADR 0066), without
  the size check (an empty `.sluenc` file is not 0 bytes). One closed while locked is never deleted, since its content
  is unknown.
- The registry records such a close with the file's bytes as last read or written (`takeClosedEmpty`, once), so the
  scratches delete the file only if it still holds those bytes; locking forgets them.

## Consequences
- Text can be kept encrypted on disk with a password that never leaves memory; the format leaves room for hardware
  keys and recovery keys without a migration of existing files.
- A forgotten password loses the content; nothing recovers it.
- New dependency `bcprov-jdk18on` (BouncyCastle, an MIT-style licence, about 7 MB in the fat jar), used for Argon2id
  only.
- Wiping is best effort; the honest limits are documented, and a memory dump of the running process can still hold
  fragments of decrypted text.
- Change Password derives the new key off the FX thread from the file on disk, then saves the tab's text through the
  inner view under it (so encoding and line endings stay); a save that fails or writes nothing puts the old key back.
  The idle lock reuses Lock with save first, skips the save when the file on disk is not the one the tab read or
  wrote (changed with any password, or deleted) and counts again after a failure. Idle time is read from
  `encryptedFiles.idleLockMinutes` (0 = off, default 10, at most 240). It is wall-clock time
  (`System.currentTimeMillis`), since the FX timers and `nanoTime` stop while the computer sleeps: a timer only ticks,
  at most 15 s apart, and a tick, an activity in the tab or its window getting the focus back fires once the idle time
  reached the period. A clock set back counts from its new time.
- Open, explored ideas (FIDO2 slot, ML-KEM recovery slot, key file, encrypting an existing file, Save Decrypted Copy,
  blocking remote images, clipboard auto-clear, lock when the app is in the background, passkeys) go to the ideas page
  `docs/ideas/encrypted-files.md`, and the behaviour to the feature page `docs/features/encrypted-files.md`, which
  later steps of this work write.
- Added to [../index.md](../index.md).
