# 0114. Generated scratch files

- Status: accepted; amends [0066](0066-scratch-files.md) (scratch files with content and descriptive names)
- Date: 2026-10-30

## Context
UUIDs, IDs, passwords, random keys and timestamps in all their formats are needed again and again: for test data, a
request, a config value or a database row. Getting them means a website, a shell one-liner or a throwaway program.
Scratch files ([ADR 0066](0066-scratch-files.md)) are already where such snippets go, so a scratch file created with
the values already in it saves that step.

## Decision
- **File → New Generated Scratch File… on Shortcut+Shift+G** (Cmd+Shift+G on macOS) opens a picker of generators;
  the pick creates a new scratch file holding what the generator writes, revealed in the explorer and opened with the
  focus, as New Scratch File does. Shortcut+Shift+G was free (Shortcut+G is Go to Line and Go To), and
  Ctrl+Shift+letter is not sent to the shell, so it works from a terminal tab too.
- **Generators are pure functions of a `GeneratorContext`** (random source, clock, time zone): `ScratchGenerator(group,
  name, baseName, extension, generate)` and the catalogue `SCRATCH_GENERATORS` in the root package of
  `modules/scratches`, the per-group lists in `dev.sluide.scratches.generators`. Tests pass a seeded random and a fixed clock, so every
  format and ordering rule is a unit test. Production uses `SecureRandom`, the UTC clock and the system zone.
- **Fixed output, no form:** each list holds 50 values, one per line; the time entry writes one labelled line per
  format. A count or format dialog would put a second step before every use for little gain; the file is editable.
- **Descriptive file names with the scratch numbering:** `uuid-v7.txt`, `password-20-alphanumeric.txt`, then
  `uuid-v7_1.txt` and so on with the lowest free number, created with `CREATE_NEW` and the content written through
  the same channel (`ScratchFiles.create(baseName, extension, content)`). The name says what is inside, unlike
  `scratch_7.txt`. Generated files have content, so closing their tab keeps them.
- **One picker with group headers** (UUIDs, IDs, Secrets, Time): `GeneratorPicker` shares the popup of the type
  picker (`picker.FilterPicker`: title, filter field, list, keys, placement), with headers that are never selected or picked;
  Up and Down skip them, a header shows while its group has matches. Filtering looks at the name, the group title and
  the file name. The generator picked last is preselected until the app ends, as the type.
- **UUID v1 and v6 use a random multicast node, never the MAC address** (RFC 9562 allows it): the MAC identifies the
  machine and would end up in shared test data.
- **Deliberately left out** (see [scratch-generators.md](../ideas/scratch-generators.md)): UUID v3/v5 (they need a
  namespace and names, i.e. a form); key pairs and certificates (they need BouncyCastle or own DER code, and private
  keys would lie unencrypted in `~/.sluide`); test data such as names, addresses or IBANs (a catalogue of its own,
  per locale); inserting values at the editor's caret.

## Consequences
- A new kind of value is one more `ScratchGenerator` in its group's list plus its unit test; the picker and the file
  creation need no change.
- Generation runs on the JavaFX thread (`WindowScratches`); it is 50 short values. A generator that throws creates no
  file and shows "The content cannot be generated." with its message.
- The type picker's list now holds `PickerRow`s instead of types and takes no focus (Tab stays in the field); only the
  picker's own keys move its selection.
- Amends [0066](0066-scratch-files.md): scratch files may now start with content and with names other than
  `scratch`. Feature page: [scratch-files.md](../features/scratch-files.md).
