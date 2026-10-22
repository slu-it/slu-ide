# Parser output for the tests

The JSON of the bundled workflow parser (schema 1) for `.github/workflows/ci.yml` (`ci.json`) and for the fixtures in
`modules/previews/src/main/js/workflow-parser/fixtures/` (same names), one line each. Regenerate them with
`node check.mjs <file.yml>` in that folder and keep the output of one file per `.json` file.

`nested/` is a small repository for nested graphs: `nested-caller.json` and `nested-build.json` are the JSON of its
`.github/workflows/caller.yml` and `build.yml`, made the same way.
