# Generated folders

**Module:** `modules/file-explorer` (package `dev.sluide.fileexplorer.generated`)

A generated folder is a folder that a build tool or package manager creates and can recreate (`node_modules`, a Python
`.venv`, Gradle's `build`, Maven's `target`, ...). Nobody edits it by hand, and git usually ignores it.
`generatedReason(folder, siblingNames)` classifies one folder and returns a `GeneratedReason` (the text shown in a
tooltip as "Generated folder (<text>)") or null. It is a pure function over the file system: it runs on any thread,
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
