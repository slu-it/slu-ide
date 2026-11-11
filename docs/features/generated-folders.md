# Generated folders

**Module:** `modules/file-explorer` (package `dev.sluide.fileexplorer.generated`)

A generated folder is a folder that a build tool or package manager creates and can recreate (`node_modules`, a Python
`.venv`, Gradle's `build`, Maven's `target`, ...). Nobody edits it by hand, and git usually ignores it. Since ADR 0163
single generated files count too, starting with macOS `.DS_Store` (see "Generated files" below); the toggle is called
Hide Generated Files.
`generatedReason(folder, siblingNames)` classifies one folder and returns a `GeneratedReason` (the text shown in a
tooltip as "Generated folder: <text>. ...") or null. It is a pure function over the file system: it runs on any thread,
never throws for IO problems (unreadable counts as "no") and does not look into the folder recursively. It costs at
most five file lookups in the folder (plus one per project file in the grandparent for two-segment rules) and at most
two small reads of regular files up to 4 KB; folders the catalogue knows cost no lookups in the folder at all. `siblingNames` are the names of all entries of the folder's parent,
which the caller already has from the listing, the folder's own name included.

Check order: the catalogue (rule 2) first, then the specific self-markers (`pyvenv.cfg`, `CMakeCache.txt`,
`meson-private`), then the generic ones (`CACHEDIR.TAG`, `.gitignore`), so reasons are as specific as possible.
Marker files must be regular files (a named pipe is never opened); `.gitignore` and `CACHEDIR.TAG` bigger than 4 KB
are not markers.

## Rule 1: self-markers (any folder name)

Checked in this order, after the catalogue finds nothing:

| Marker inside the folder | Reason text |
|---|---|
| `pyvenv.cfg` file | virtual environment (pyvenv.cfg) |
| `CMakeCache.txt` file | CMake build folder |
| `meson-private` folder | Meson build folder |
| `CACHEDIR.TAG` starting with `Signature: 8a477f597d28d172789f06886806bc55` | marked by CACHEDIR.TAG |
| `.gitignore` whose only line that is not blank and not a `#` comment is `*` (a leading BOM is ignored) | marked by its .gitignore |

A wrong signature, or a `.gitignore` with `*` plus another line (`!keep`) or bigger than about 4 KB, is not a marker.

## Rule 2: catalogue

A folder name plus a project file among its siblings (no I/O). The reason text is "<folder> for <project file>", for
example "node_modules for package.json" or "obj for App.csproj". Names and project files may start or end with one
`*` (`*.csproj`, `cmake-build-*`) with a non-empty rest (`.sbt` does not match `*.sbt`). Matching is case-sensitive. The catalogue is one data list,
`generatedCatalogue`, in `GeneratedFolders.kt`.

| Ecosystem | Folders | Project file next to it (any one) |
|---|---|---|
| JS/TS | `node_modules`, `.next`, `.nuxt`, `.output`, `.svelte-kit`, `.angular`, `.turbo`, `.parcel-cache`, `.docusaurus`, `.astro`, `.pnpm-store`, `dist`, `coverage` | `package.json` |
| JS (Bower) | `bower_components` | `bower.json` |
| Gradle (Java, Kotlin, Groovy) | `build`, `.gradle`, `.kotlin` | `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` |
| Maven | `target` | `pom.xml` |
| Scala | `target`, `.bsp`, `.bloop`, `.metals` | `*.sbt` |
| Scala | `project/target` (two segments) | `build.sbt` in the grandparent |
| Clojure | `target` | `project.clj`, `deps.edn` |
| Clojure | `.cpcache` | `deps.edn` |
| Clojure | `.shadow-cljs` | `shadow-cljs.edn` |
| Python | `__pycache__`, `.pytest_cache`, `.mypy_cache`, `.ruff_cache`, `.hypothesis`, `.ipynb_checkpoints`, `htmlcov` | none; reasons "Python cache", "Jupyter checkpoints", "coverage report" |
| Python | `.venv`, `.tox`, `__pypackages__`, `build`, `dist`, `*.egg-info` | `pyproject.toml`, `setup.py`, `setup.cfg` |
| Python | `.tox` / `.nox` | `tox.ini` / `noxfile.py` |
| Rust | `target` | `Cargo.toml` |
| .NET | `bin`, `obj` | `*.csproj`, `*.fsproj`, `*.vbproj` |
| .NET | `.vs` | `*.sln`, `*.slnx` |
| C/C++ (CMake) | `cmake-build-*` | `CMakeLists.txt` |
| Ruby | `.bundle`, `coverage` | `Gemfile` |
| Ruby | `vendor/bundle` (two segments) | `Gemfile` in the grandparent |
| PHP | `vendor` | `composer.json` |
| Swift | `.build`, `.swiftpm` | `Package.swift` |
| Swift (CocoaPods) | `Pods` | `Podfile` |
| Swift (Carthage) | `Carthage/Build` (two segments) | `Cartfile` in the grandparent |
| Dart/Flutter | `.dart_tool`, `build` | `pubspec.yaml` |
| Perl | `local` | `cpanfile` |
| Perl | `blib`, `_build` | `Makefile.PL`, `Build.PL` |
| Lua | `lua_modules` | `*.rockspec` |
| D | `.dub` | `dub.json`, `dub.sdl` |
| Fortran | `build` | `fpm.toml` |
| Terraform | `.terraform` | `*.tf` |

Two-segment rules take the folder's name as the second segment and its parent's name as the first; the project file is
looked up with one `Files.exists` in the grandparent.

## Deliberately not generated

- Go `vendor` (no `composer.json`): usually committed to the repository.
- `out`, `tmp`, `log`: too generic, often the user's own files.
- `.idea`, `.yarn`, `.mvn`: hold settings, wrappers or committed state.
- `build` without any of the project files above: could be anything.

## Hiding them in the explorer

The setting `explorer.hideGeneratedFolders` (default on, shared by all windows) leaves generated folders out of the
explorer tree (ADR 0158). The explorer bar's **Hide Generated Files** toggle (eye-off, after Compact Middle Folders) follows and
sets the setting.

- Every folder listed in the tree classifies its child folders, the project's own children included; a top-level node
  itself is never classified. Nothing is classified below a generated folder, shown or hidden, so a `node_modules`
  inside a shown `node_modules/pkg` is a normal folder.
- The structural reason above decides alone outside a git repository and before the window's git status has arrived.
  In a repository the folder must also be ignored by git: a tracked folder, or one that is untracked but not ignored,
  is a normal folder, so a tracked `build` reappears when the status arrives. A folder the status does not cover keeps
  the structural reason.
- A compacted row (Compact Middle Folders) is classified by its first folder, the entry in the listed folder; compacting
  itself ignores hiding.
- The tree is classified when a folder is listed (first load, watch events, F5) and when the setting changes. Each
  loaded folder remembers its generated folders, so a change of the git status re-checks only those and lists a
  folder again only if one of them becomes shown or hidden; otherwise it just updates their reasons.
- Turning the setting off shows the folders with their reason; turning it on hides them again, keeping expansion and
  selection (a selected row that vanishes is replaced by its nearest ancestor).
- Revealing a path inside a hidden folder (Reveal in Explorer, opening a file from outside) shows that folder
  anyway, and so does selecting a new entry inside it (New File, Rename, Paste or a move into a hidden `build`).
  Only a generated folder or file is shown this way: a path below `.git` stays out. The exception survives re-listings from watch events and ends when the setting changes, on Refresh (F5) and on
  Collapse All, which hide the folder again.

### The look

A generated folder that is shown (option off, or revealed) has Material `folder-cog` (`folder-cog-outline` while
expanded) in a muted grey and the tooltip "Generated folder: <reason>. Hidden while Hide Generated Files is on", with
the full path as first line on labelled or compacted rows. A library folder (virtual environment) that is also
generated keeps its amber lock (library wins) while "Open library code read-only" is on, and its tooltip lists the
library text, then the generated one; with that option off it shows the cog and the generated tooltip. The folders
inside keep their normal icons.

## Generated files

ADR 0163 extends the feature to single files. `generatedFileReason(file)` looks the file's exact, case-sensitive name up
in `generatedFileCatalogue` (name only, no I/O) and returns a `GeneratedReason` with `file = true`. Only regular files
count, a symlink to one too (a folder named `.DS_Store` is a normal folder), found in the same places as generated
folders: not as a top-level node and never below a generated folder.

| File name | Reason text |
|---|---|
| `.DS_Store` | macOS folder metadata |

**Git rule: hidden unless tracked.** Outside a repository, before the git status arrives, or for a path the status does
not cover, the name decides and the file is hidden. In a repository a file git ignores or has not added (also inside an
untracked or ignored folder) stays hidden, while a tracked file (clean, modified or added) is shown as a normal file
without a reason. So a Mac without a global gitignore still gets a clean tree, and a committed `.DS_Store` shows up
and is noticed. Folders keep their own rule (generated only if git ignores them). The status is asked through
`FileExplorerView.trackedByGit`, set by `showGitStatus` (`GitStatusSnapshot.tracksFile`), and `gitIgnoresChanged()`
re-checks file candidates like folders, so a `.DS_Store` that becomes tracked appears and one that is untracked
disappears.

Two cases where git has no say, so the name decides and the file stays hidden: a file that is absent from the snapshot
but was modified less than 2 s before the status run started or later (or whose modification time cannot be read), so
a `.DS_Store` that Finder just wrote does not flash up as "tracked" until the next status
(`GitStatusSnapshot.startedAt`, taken before git runs and not part of `equals`, so a run with an unchanged result is
no change); and a file inside a nested repository or submodule (a folder between the file and the project root has a
`.git` entry), which the outer status does not cover. Generated folders keep their behaviour there (see
[generated-folders-later.md](../ideas/generated-folders-later.md), "Git status for nested projects").

**Compacting.** While the setting is on, Compact Middle Folders does not count a `.DS_Store` file as an entry, so a
folder with one subfolder and a `.DS_Store` still compacts (by name only, without git, so a tracked `.DS_Store` in a
middle folder is not visible while the row is compacted). Hidden generated folders still count. Turning the setting
off rebuilds the chains. Revealing such a `.DS_Store` splits the compacted row so the file gets its row; Refresh (F5)
and Collapse All, which forget the revealed entries, join it again.

**Limit of the 2 s margin.** A run with an unchanged result keeps its old start time. A tracked `.DS_Store` that git
itself wrote (checkout, pull, reset --hard) or that Finder touched without a content change has an mtime within the
margin of the next run, and later runs return the same result, so it stays hidden until the status changes otherwise
or its folder is listed again after that. On a network share whose server clock runs ahead, absent files count as new
and tracked ones stay hidden too. Both err on hiding a noise file and affect only committed `.DS_Store` files.

**The look.** A shown generated file (option off, or revealed) has Material `file-cog` in the muted grey of the
generated folders and the tooltip "Generated file: macOS folder metadata. Hidden while Hide Generated Files is on".
Revealing and the exceptions work as for folders, also for a file in a middle folder of a compacted row (see
Compacting).
