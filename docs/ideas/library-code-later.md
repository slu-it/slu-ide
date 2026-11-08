# Library code: ideas left for later

Follow-ups of [ADR 0150](../adr/0150-library-code-read-only.md) ("Library code opens read-only",
[library-code.md](../features/library-code.md)) that the first round left out.

- **Other ecosystems.** The rules of `LibraryCode` are Python only. Candidates, each a path rule like the Python ones:
  `node_modules` (JavaScript, TypeScript), the Gradle and Maven caches (`~/.gradle/caches`, `~/.m2/repository`,
  sources jars extracted for navigation), Cargo's registry (`~/.cargo/registry/src`), Go's module cache
  (`$GOPATH/pkg/mod`) and Ruby gems. They matter once a language server of that language navigates into them.
- **Remembering Edit Anyway** beyond the view, e.g. per file for the session, for someone debugging a dependency over
  several restarts of a tab. Today every new view of the file opens locked again.
- **Live explorer icons for folders never expanded.** A folder that was listed but never expanded and later gains or
  loses its `pyvenv.cfg` updates only on F5 or Refresh, since nothing watches it.
- **PyPy's standard library** (`lib/pypy3.10`) is not recognised; its `site-packages` is.
- **Telling project test data from libraries.** Test data with a `pyvenv.cfg` or `lib/python3.12`, or a vendored
  `mypy/typeshed`, inside the project opens locked too; Edit Anyway covers it. A per-project exclusion would need a
  place to store it (`~/.sluide/projects`).
