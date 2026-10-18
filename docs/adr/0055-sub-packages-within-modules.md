# 0055. Sub-packages within modules: the root holds the module's API

- Status: accepted
- Date: 2026-10-18

## Context
Apart from `http-client` ([ADR 0025](0025-http-client.md)), every module kept all its files in one package
`dev.sluide.<module>`: `file-viewers` had 86 main files there, `git-log` 46, `git` 36. A flat package does not
show which files belong together, nor what a module offers to others, and an agent looking for one part reads
through a long listing. The splits of [ADR 0054](0054-keep-source-files-small.md) added more files to the same
packages.

## Decision
- **The root package `dev.sluide.<module>` holds the module's API:**
  - what other modules' main code imports;
  - the launch points, e.g. `SluIdeLauncher` (named by `mainClass`) and `SluIdeApplication`.

  Imports from other modules' tests do not count. `GitCli` is constructed by `ide` tests and still lives in
  `git.cli`.
- **Everything else moves into sub-packages of files that belong together.** The grouping is chosen per module:
  - by feature or file type, e.g. `fileviewers.markdown`, `fileviewers.pem`, `gitlog.push`, `gitlog.history`;
  - by layer, e.g. `git.cli` (the process core and its reads), `git.parse`, `gitlog.data`;
  - shared helpers in a small `ui` package, e.g. `gitlog.ui`.
- **Spring beans that nothing imports live with their group**, e.g. the file viewers in `fileviewers.languages` or
  `fileviewers.markdown`, and the settings pages in `settings.pages`. Component scanning covers `dev.sluide`
  recursively, and bean names come from the simple class name, so a move changes neither.
- **Small modules stay flat:** about six files or fewer, or nearly all of them API. These are `text-editing`,
  `editor`, `docker-sandbox`, `project-trust`, `projects`, `file-icons` and `file-watcher`.
- **Visibility is unchanged.** `internal` covers the whole module. Inheritors of a sealed type stay in its package,
  which is why `CommitChanges` and `PushOperation` live in `gitlog.operations` next to `RefOperation`.
- **Moved classes load their resources by absolute path** (`/dev/sluide/<module>/…`), and the resources stay in the
  module's resource folder. Classes in the root keep their relative lookups.
- **Tests sit in the package of the code they test.** Tests of the API, and UI tests that drive the root component,
  stay in the root. A shared test helper goes where most of its users are.
- **Dependencies between sub-packages run one way where a single move allows it.** For example, the heading
  functions of the table of contents live in `fileviewers.preview` rather than `fileviewers.markdown`.
  `gitlog.operations` ↔ `gitlog.branches` remains (`BranchActions` uses `BranchNode`).

## Consequences
- The root listing of a module shows what it offers. `file-viewers` has 13 root files instead of 86, and `git-log` 3
  instead of 46. `git` keeps its 7 components and its public domain types in the root, which is its API, and the
  internals live in `cli` and `parse`.
- The files of a module now import each other. ktlint forbids wildcard imports, so the import lists of the git
  components are long: they import the `GitCli` read functions by name.
- Class names written in configuration follow their class, e.g. `dev.sluide.ide.logging.LogFolder` in
  `logback-spring.xml` ([ADR 0051](0051-log-file.md)).
- New files go into the sub-package of what they belong to. A new kind of file viewer gets its own package if it
  brings more than the viewer, e.g. a renderer.
- Added to [../index.md](../index.md).
