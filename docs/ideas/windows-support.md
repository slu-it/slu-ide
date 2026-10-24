# Idea: Windows support

**Status:** idea, explored 2026-10-22; nothing decided or built. Reason: friends who have to use Windows want to try
Thoreau. Open question: is the ongoing cost of a third OS worth it?

## Where we stand

- [ADR 0003](../adr/0003-javafx-platform-jars.md) excludes Windows on purpose: JavaFX natives are per platform, so each
  fat jar is built on its own runner (linux-x64, linux-aarch64, mac-aarch64). Packaging
  ([ADR 0011](../adr/0011-native-app-packaging.md)) covers macOS only.
- The code is mostly portable, and a few places already plan for Windows: `SystemFileManager` (Explorer), the POSIX
  fallbacks in `FileExport` and `GitFolderStamps`, CRLF handling in `LineEnding`, PowerShell highlighting.
- Nothing has run on Windows. The list below comes from reading the code, not from testing.

## What would need adapting

### Build, CI, release
- `release.yml`: a `windows-latest` (x64) runner, maybe a Windows ARM runner. Check the artifact name that
  `ide/build.gradle.kts` derives from `os.name` (`ide-windows-x64`).
- `ci.yml`: Windows in the matrix. Unknown whether TestFX runs fast and reliably on the Windows runners' desktop; on
  macOS it took about 30 minutes and moved to an on-demand workflow.
- Packaging: `jpackage` needs a `.ico` icon (the build only picks between `.icns` and PNG). Either a zipped app image or
  an `.msi`, which needs WiX on the runner. The packaging code's `isMac` checks need a Windows case.
- There is no `.gitattributes`. With Git's default `autocrlf` on Windows, test fixtures and expected strings would
  check out with `\r\n`. `* text=auto eol=lf` prevents that.

### Terminal and shell commands
- `TerminalSession.defaultShell` starts `$SHELL -l`, falling back to `/bin/bash`. Windows has neither `SHELL` nor `-l`,
  so it needs PowerShell (`pwsh`, else `powershell.exe`) or `cmd.exe`. pty4j supports Windows through ConPTY.
- `loginShellRunning` (`$SHELL -l -i -c …`) is also used by the Docker sandbox and by `loginShellFinds` (`command -v …`), behind `SbxCheck` and the Gradle check. It needs
  a Windows version, e.g. `where sbx`.

### File system behaviour
- Windows can't replace or delete a file another process has open. Atomic saves (`Files.move` with
  `REPLACE_EXISTING`/`ATOMIC_MOVE` in `SettingsStore`, `RecentProjects`, `TrustedProjects`, `FileExport`, editor saves)
  can fail.
- `FileWatcher` registers every folder. On Windows a watched folder may not be renameable or deletable, which affects
  the explorer's move, rename and delete ([ADR 0073](../adr/0073-explorer-multi-selection-and-moving.md)). Needs
  testing.
- Creating symlinks needs admin rights or Developer Mode. The scratch-file rules and the git trust checks reason about
  symlinks.

### Paths as strings
- Some places turn a `Path` into a string and compare it with git's `/`-separated output, or pass it to git:
  `GitStatusService.relativeToGit`, `GitFolderStamps` (entries map), `GitCommits` (`relative`). On Windows those strings
  use `\`. Use `invariantSeparatorsPathString` or `joinToString("/")`, as `EnvTexts` and `RecentProject` already do.
- `~/` shortening in `RecentProject` and `~/` expansion in `GitTrustCheck` need checking against `C:\Users\…` paths.

### Keys
- `WindowGitLog` and `TerminalView` choose between Shortcut and Alt with a Mac check. On Windows, Alt alone opens the
  menu bar, so the Alt shortcuts need checking. Shortcut maps to Ctrl, the same as on Linux.

### Tests
- About 43 test classes use symlinks, POSIX permissions or `/bin`/`sh`. Each needs a portable variant or
  `@DisabledOnOs(OS.WINDOWS)`.

## Ongoing cost (the impact to weigh)

- A third platform in every decision: shell, process, path and key handling get a Windows branch, and new features
  that start processes or touch the file system need a Windows check.
- No Windows machine in daily development. The sandbox is Linux, so Windows bugs only show up in CI or through
  testers. A CI run that is slower or flakier on Windows slows every change down.
- Release builds get one more runner per tag, plus packaging and possibly code signing (unsigned apps trigger
  SmartScreen warnings).
- Docs: ADRs and feature pages describing OS behaviour (terminal, Docker sandbox, git, key bindings) would need
  Windows notes.

## Ways to scope it

1. **Testers build it themselves, no support.** Friends run `gradlew.bat :ide:bootJar` on Windows and get a jar with
   Windows natives. No cost for us, but the terminal, the git status paths and possibly saving will break in places,
   so the impression may be worse than the app.
2. **Best-effort preview.** Fix the known blockers (shell, git paths, `.gitattributes`), add a Windows jar or zipped
   app image to releases marked "untested", and keep Windows out of CI or let it fail without blocking. Bugs get
   fixed when testers report them.
3. **Full support.** Everything above, Windows in CI with the UI tests, an installer, and a new ADR replacing the "no
   Windows" part of ADR 0003.

Option 2 is a middle path that tests demand before committing to option 3. If Windows stays out, ADR 0003 needs no
change.
