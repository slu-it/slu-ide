# Workflow parser bundle

GitHub's own GitHub Actions workflow parser ([`@actions/workflow-parser`](https://github.com/actions/languageservices),
the parser behind the Actions VS Code extension) bundled into one browser script with a small entry, `entry.js`, that
turns its result into our own JSON (schema 1, below). Thoreau runs the bundle in a hidden JavaFX `WebEngine` (WebKit,
no Node) and decodes the JSON with Jackson.

This folder holds the build inputs; they are not part of the jar. The output, `workflow-parser.min.js` plus the
licence of every bundled package, is checked in under
`src/main/resources/dev/sluide/previews/vendor/workflow-parser/` (listed in that folder's parent `README.md`).

| File | What |
|------|------|
| `package.json`, `package-lock.json` | Exact versions; the lockfile carries the `integrity` hash `npm ci` verifies |
| `entry.js` | Exposes `sluideWorkflowParser.parse(fileName, text)` |
| `build.sh` | Installs from the lockfile, bundles, copies the licences, prints size and sha256 |
| `check.mjs` | Developer check: runs the bundle and prints its JSON, not part of the Gradle build |
| `fixtures/` | Workflows covering needs, matrices, reusable workflows, step kinds, events and broken files |

## Rebuilding

Needs Node 20 or later and the npm registry.

```sh
./build.sh                    # npm ci --ignore-scripts, esbuild, licences, sha256
node check.mjs --compare      # repository workflows and fixtures/; exits 1 on a difference
node check.mjs some/file.yml  # one file
```

`build.sh` bundles with `--platform=browser --target=es2020 --format=iife --minify`: a Node built-in (`fs`, `path`, ...)
fails the build, and no syntax beyond ES2020 ends up in the bundle. `check.mjs` evaluates the bundle in an empty
`node:vm` context (no `require`, `process`, `window` or `document`) and, with `--compare`, checks that the synchronous
conversion of `entry.js` finds the same jobs, needs, step counts, events and error count as the parser's own
`convertWorkflowTemplate`. After a rebuild, update the sha256 and versions in the vendor `README.md`.

## Updating versions

1. `npm view @actions/workflow-parser version` and `npm view esbuild version`.
2. Set the exact versions in `package.json` (no ranges), then `npm install --ignore-scripts` to refresh the lockfile.
   `@actions/expressions`, `yaml` and `cronstrue` follow from the parser's own ranges; `npm ls --all` shows them.
3. `./build.sh`, `node check.mjs --compare`, read the JSON for the fixtures.
4. Check the licence of any newly bundled package (`build.sh` prints the bundled packages and copies their
   `LICENSE`); only permissive licences (MIT, ISC, Apache-2.0, BSD).
5. Update the vendor `README.md` (versions, sha256).

`entry.js` imports four modules below the package's public index (`model/converter/events`, `model/converter/jobs`,
`model/converter/concurrency`, `model/converter/handle-errors`; the package exports `./*`). They may move in a new
version; the build then fails to resolve them. A changed `convertWorkflowTemplate` (another root key converted, for
example) shows up in `check.mjs --compare` as a different error count or job list; mirror it in `entry.js`.

## Why synchronous

`convertWorkflowTemplate(context, root, fileProvider, options)` is `async`, because it can fetch reusable workflows
through the file provider. A Java caller of `WebEngine.executeScript` would only see a pending `Promise` and need a
callback into Java. Without a file provider and with `fetchReusableWorkflowDepth: 0` nothing in it awaits, so
`entry.js` runs its loop synchronously, minus the fetching of reusable workflows: `convertOn` for `on`, `convertJobs`
for `jobs` and `convertConcurrency` for `concurrency` (only for its errors), each wrapped in
`handleTemplateTokenErrors`, converting despite schema errors (`ErrorPolicy.TryConversion`); the list of errors is
the same. In addition it keeps the workflow `name` and the `jobs` token (for job and step lines). Errors come from the
parse context, not from the converted template (whose `errors` hold only messages, no positions).

Turning the converted template into our JSON happens in parts: the errors, the name, the events, the job and step
lines, and each job separately. A bug that throws in one part loses only that part (an empty list, null, or the job
left out) and adds an error entry `Could not read <part>: <reason>` without position.

## JSON contract, schema 1

`sluideWorkflowParser.parse(fileName, text)` returns a JSON string and never throws: anything that goes wrong becomes
an entry in `errors`. `sluideWorkflowParser.schema` is the schema number. Fields are always present; "or null" means
the value is `null` when absent. All lines and columns are 1-based. Field order is as listed.

```json
{
  "schema": 1,
  "errors": [{ "message": "Job 'deploy' depends on unknown job 'missing'.", "line": 11, "column": 20 }],
  "name": "CI",
  "events": [{ "name": "push", "detail": "branches: main, release/**; paths-ignore: docs/**" }],
  "jobs": [{
    "id": "build", "name": "Build (${{ matrix.os }})", "line": 16, "type": "job",
    "needs": [], "if": null, "runsOn": "${{ matrix.os }}",
    "matrix": {
      "expression": null, "axes": { "os": ["ubuntu-latest", "macos-latest"] },
      "include": [], "exclude": [], "includeExpression": null, "excludeExpression": null
    },
    "uses": null, "with": null, "secretsInherit": false,
    "steps": [{ "id": null, "name": null, "line": 24, "uses": "actions/checkout@v6", "run": null, "if": null }]
  }]
}
```

Top level:

- `schema`: `1`. A change that breaks readers of this contract raises it.
- `errors`: YAML syntax errors, schema errors (unknown or missing keys, invalid expressions) and model errors (unknown
  or cyclic `needs`, steps without `uses`/`run`) in the parser's order, then `Could not read <part>: <reason>` entries
  for parts of the result that could not be read (above). An empty list means the workflow is valid.
  - `message`: the parser's message without its `file (Line: n, Col: m)` prefix. YAML syntax errors are multi-line:
    the first line, then a code excerpt.
  - `line`, `column`: start of the offending token, or null (for example an empty file, or a `Could not read`).
- `name`: the workflow's `name`, or null.
- `events`: the `on` events in file order, one entry per event; empty when `on` is missing or invalid.
  - `name`: event name, e.g. `push`, `schedule`, `workflow_dispatch`.
  - `detail`: a short summary of the event's filters, or null when it has none: `key: values` pairs joined with `; `,
    values joined with `, `. Keys: `branches`, `branches-ignore`, `tags`, `tags-ignore`, `paths`, `paths-ignore`,
    `types`, `versions`, `names`, `workflows`; for `schedule` `cron: 0 3 * * 1, 30 2 * * *` (a timezone in
    parentheses after its cron; null without crons, as in `on: schedule`, `on: [push, schedule]` or an empty
    `schedule:`); for `workflow_dispatch` and `workflow_call` the names of their `inputs` (and `secrets`).
- `jobs`: the jobs in file order. With errors, the jobs the parser could still convert (possibly none).

Job:

- `id`: the job's key.
- `name`: the job's `name` as written, or null when it has none.
- `line`: line of the job's key.
- `type`: `job` (runs steps) or `reusableWorkflowJob` (has `uses`).
- `needs`: ids of the jobs it needs, as written (also unknown ones; those are in `errors`).
- `if`: the condition as written, bare (`github.ref == 'x'`) or as an expression (`${{ !cancelled() }}`), or null when
  there is none. GitHub's implicit `success()` is not reported; a written `success()` is.
- `runsOn`: `runs-on` as text, or null (always null for reusable workflow jobs). A label as is, a list as
  `[self-hosted, linux]`, a group as `{group: big-runners, labels: [linux, x64]}`, an expression as written.
- `matrix`: an object with all six fields when `strategy.matrix` is a mapping or an expression (or `strategy` is one
  expression), otherwise null: no matrix, or one of another shape such as `matrix: [1, 2]` (a schema error).
  - `expression`: the matrix (or the whole `strategy`) written as one expression, e.g.
    `${{ fromJSON(needs.setup.outputs.matrix) }}`; then the other fields are empty. Otherwise null.
  - `axes`: axis name to its values as strings (`21`, `true`), in file order; a value that is a mapping or list is in
    flow style (`{debug: true}`), YAML null is null. An axis written as an expression is a one-element list holding
    the expression. An axis that is neither a list nor an expression (a schema error) is left out.
  - `include`, `exclude`: the entries that are mappings, each an object of key to value text (as in `axes`).
  - `includeExpression`, `excludeExpression`: `include`/`exclude` written as one expression, otherwise null.
- `uses`: the reusable workflow reference (`./.github/workflows/build.yml`,
  `owner/repo/.github/workflows/deploy.yml@v1`), null for `job`.
- `with`: the reusable workflow job's inputs, key to value text, or null when there is no `with` (always null for
  `job`). The schema reads these values as strings: a YAML null is `""`, and a list or mapping (a schema error) is
  read as empty (`[]`, `{}`).
- `secretsInherit`: true for `secrets: inherit`.
- `steps`: the `run` and `uses` steps in file order, empty for reusable workflow jobs. Every other step is left out:
  the ones the parser rejects (neither `uses` nor `run`; they are in `errors`) and its experimental `wait`,
  `wait-all`, `cancel` and `parallel` steps (also not their nested steps).

Step:

- `id`: the step's `id`, or null (the ids the parser generates are not reported).
- `name`: the step's `name` as written, or null.
- `line`: line of the step's first key (the line of its `-`), or null when unknown.
- `uses`: the action reference as written (`actions/checkout@v4`, `./local`, `owner/repo/path@sha`,
  `docker://alpine:3`) for a `uses` step, otherwise null.
- `run`: for a `run` step the script's first non-blank line, trimmed; `""` when the script is empty or blank.
  Otherwise null. Exactly one of `uses` and `run` is non-null (a step with both, a schema error, counts as `run`).
- `if`: as for jobs.

Text values: strings unquoted, expressions as written (`${{ x }}` alone is rebuilt from the expression, so spacing
inside `${{ }}` is normalised; text mixed with expressions is kept as is), numbers and booleans as YAML resolved them
(`3.10` becomes `3.1`).
