# 0029. Matching files by name and shebang; languages without lexers

- Status: accepted; partly superseded by [0031](0031-own-tokenizers.md) (TOML, Bicep and KQL are no longer postponed: own tokenizers)
- Date: 2026-10-15

## Context
Code viewers matched only file extensions ([ADR 0004](0004-file-viewer-selection.md)). Many files that projects
carry have none or a misleading one: `Dockerfile`, `Dockerfile.dev`, `Makefile`, `Caddyfile`, `sshd_config`, `.env`,
`.bashrc`, scripts with only a shebang. Once names count, several viewers can claim one file (`Dockerfile.md`,
`Caddyfile.json`, `Dockerfile.dockerignore`), and the first bean asked must not decide, since Spring's bean order is
arbitrary. Some of these formats have no RSyntaxTextArea lexer ([ADR 0005](0005-syntax-highlighting.md)).

## Decision
- **Three ways to match a name**, all case-insensitive (`FileMatch`): an extension, an exact file name (`names`), or
  a regex matching the whole name (`namePatterns`). A name with a leading dot (`.bashrc`) counts as having an
  extension, so dotfiles are listed in `names`.
- **Precedence** (`NameMatch`, in `FileViewers`): of the viewers that can open a file, an exact name beats an
  extension, an extension beats a name pattern. Viewers other than `CodeFileViewer`s (previews, images, `.http`)
  count as extension matches, except `NamedFileViewer`s, whose `fileNames` match exact names like a code viewer's
  `names` ([ADR 0057](0057-visual-environment-editor.md)). Spring's order only breaks ties. So `Dockerfile.md` is
  Markdown, `Caddyfile.json` and `.env.json` JSON, while `Dockerfile.dev` (no viewer takes `.dev`) is a Dockerfile.
  Patterns may exclude names another viewer takes by extension anyway (`Dockerfile.dockerignore` is an ignore file).
- **Shebangs** only for text files without an extension that no viewer opens by name, after all name matches and
  before `DefaultTextFileViewer`. The first line comes from the 8 KiB sample read once for all viewers. `env`
  options with their arguments and `NAME=value` are skipped; versions are stripped (`python3.12` counts as `python3`
  and `python`); `uv run` counts as `python`, no other launcher is recognised.
- **Names beat shebangs:** a name is a deliberate statement about the file, while a shebang only says what runs it
  (a `Dockerfile` stays a Dockerfile whatever its first line says). The shebang is read only when no name decides.
- **Languages without a lexer** get line rules on the plain lexer, but only when small: ignore files,
  `.git-blame-ignore-revs`, SSH config, Caddyfiles, Mermaid, cookie jars, PEM. The plain lexer itself is skipped: it
  styles nothing and takes quadratic time on long lines full of backslashes. Line rules and lexer spans are merged
  in near-linear time (sorted lists, overlap checks against neighbours only), guarded by `HighlightingPerformanceTest`.

Rejected:
- **First viewer that agrees:** `Dockerfile.md` would depend on bean order.
- **Shebang before names:** overrides what the name states, and reads the first line of every file.
- **Hand-written highlighters for large languages without a lexer:** TOML (`uv.lock`), Bicep and KQL stayed plain
  text for now, by the user's choice; since [ADR 0031](0031-own-tokenizers.md) they have own tokenizers.

## Consequences
- A new name-matched format is a `names` or `namePatterns` entry; conflicts resolve without ordering beans.
- A pattern viewer loses to any viewer of the extension, also where that is not wanted (a `Caddyfile.conf` opens as
  config).
- Binary files other than images (`.xlsx`, `.pptx`, SQLite) are still not opened.
- Partly supersedes [ADR 0004](0004-file-viewer-selection.md) (first viewer that agrees).
- Amended by [ADR 0074](0074-path-matched-file-viewers.md): a viewer for a path (`.github/workflows`) beats every name match.
