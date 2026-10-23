# 0074. Path-matched file viewers

- Status: accepted
- Date: 2026-10-22

## Context
[ADR 0029](0029-file-name-and-shebang-matching.md) ranks viewers by a file's name only: exact name, extension, name
pattern. A GitHub Actions workflow is a plain YAML file that needs a different viewer than other YAML files, and only
its location tells them apart: `.github/workflows/ci.yml`. Diffs by name get repository-relative paths, which must
work the same way.

## Decision
- **`PathFileViewer`** (`file-viewers`): `matchesPath(file)` tells whether a file, absolute or relative, is at one of
  the viewer's paths. `isInFolders(file, ".github", "workflows")` does the common check: the folders directly
  containing the file end with the given names (case-sensitive).
- **`NameMatch.PATH` is the strongest match**, before the exact name, extension and pattern.
- **A `PathFileViewer` takes part only when its path matches**, in `viewerFor` and `viewerByName`, even if it is also
  an `ExtensionFileViewer`; elsewhere it is never picked, so plain `.yml` files stay with the YAML code viewer.
  `canOpen` must still accept the files at its paths.
  Amended by [ADR 0079](0079-yaml-preview.md): YAML files outside workflow folders now open with the YAML preview
  (`YamlFileViewer` in `previews`), no longer a code viewer; the path match still wins for workflows.
- **`matchesPath` is about the location only;** the type of file is checked by `canOpen`, and by name by the
  `fileExtensions` of a viewer that is also an `ExtensionFileViewer`, so `.github/workflows/README.md` is not a
  workflow. Shebang viewers that are `PathFileViewer`s are not asked for shebangs.
- **The path only, never the disk:** nothing is read or checked for existence, so `viewerByName` works for files of
  a commit and relative names.

## Consequences
- A viewer for a location needs no change to other viewers and no bean ordering.
- Matching is by folder names, not by repository root: `sub/.github/workflows/x.yml` matches too.
- Amends [ADR 0029](0029-file-name-and-shebang-matching.md): `NameMatch` gains `PATH` in front.
