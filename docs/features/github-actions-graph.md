# GitHub Actions workflow graph

**Modules:** `previews` (`WorkflowFileViewer`, `WorkflowPreview`, `WebViewWorkflowParser`) and `github-actions`
(model and drawing, `WorkflowGraphs`); decisions in [ADR 0075](../adr/0075-github-actions-workflow-graph.md) and
[ADR 0074](../adr/0074-path-matched-file-viewers.md)

## Which files
- `.yml` and `.yaml` files **directly** in a `.github/workflows` folder (case-sensitive; also below a project's
  subfolder, `sub/.github/workflows/ci.yml`). Not `.github/workflows/sub/x.yml`, not `.github/dependabot.yml`, not
  other files in that folder: those keep their viewers (YAML, plain text).
- Diffs and a commit's version of a workflow (by its repository-relative name) are highlighted as YAML, like any YAML
  file.

## The view
- Opens in **Source**: highlighted YAML, editable, Shortcut+/ comments out with `#`. **Split** or **Preview** shows
  the saved file as a graph, drawn by Mermaid like a Mermaid file ([previews.md](previews.md)): zoomable, fitted at
  first, drawn again on a theme change. No scroll sync.
- The saved text is parsed whenever the file is opened, saved or changed on disk, also in Source, where no graph
  shows. Meanwhile the preview says "Reading the workflow…". The parser runs about 5 ms on the FX thread for a usual
  workflow; the first workflow after startup takes a moment longer, since the parser (a 278 KB script, read once)
  starts then.
- If the parser itself fails (it does not answer within 10 seconds, or cannot start), the preview says so and the
  text is tried once more right away, with a new parser engine; after that, edit the file or reopen it. Saving or
  reloading unchanged text does not try again.
- The parser is GitHub's own (`@actions/workflow-parser`), so what it accepts, rejects and reports is what GitHub
  does.

## What is drawn
- **Triggers**: a box per event of `on`, in file order, with its filters (`push · branches: main, master`, a
  schedule's crons, the names of `workflow_dispatch` inputs).
- **A box around each job**, titled with its `name` (else its id), `runs-on` and a matrix summary
  (`matrix os×java (6)`, `matrix (dynamic)` when built by an expression). Inside, its `run` and `uses` steps from top
  to bottom: a step's name, else `run:` with the script's first line; for `uses` the reference; `if:` conditions as
  written. A job that calls a reusable workflow is one box with `calls <reference>`, its input names and
  `secrets: inherit`.
- **Links**: from each job a job `needs`, or from the triggers when it needs none. A job with an `if:` gets dotted
  links labelled with the condition.
- **Local `uses:` are drawn nested** (next section); remote actions (`owner/repo@ref`), remote reusable workflows,
  `docker://` images and references the parser cannot read are a **black box** (thick border) with the reference.
- A line above the graph names problems: GitHub's errors (`2 problems; first: Unexpected value 'colour' (line 2).`),
  jobs that need unknown jobs, and what was left out when the graph is too large (at most 200 boxes, 400 links and
  about 45,000 characters, below Mermaid's limits). The graph still shows what could be read.
- A file without jobs to draw (empty, broken YAML, not a workflow) shows only that line: GitHub's first error, or
  "No jobs in this workflow."
- Labels are escaped: names, conditions and expressions (`${{ … }}`) are drawn as text and cannot start Mermaid
  statements; `securityLevel: 'strict'` stays.

## Following local `uses:`
A workflow in `<root>/.github/workflows/` reads what its `./…` references point to from `<root>`, the folder holding
`.github` (as GitHub resolves them against the repository's root), also from the files it opens in turn:
- **Reusable workflow** (`jobs.<id>.uses: ./.github/workflows/x.yml`): parsed by the same parser and drawn inside the
  calling job's box as a box `calls x.yml` (with the `with:` inputs and `secrets: inherit`), holding its jobs with
  their steps and `needs` links, like the workflow's own.
- **Local action** (`steps[*].uses: ./path`): `<root>/path/action.yml`, else `action.yaml`, read as YAML with every
  value kept as text (so `on`, `yes` stay words; no types are built from tags, aliases, nesting and size are limited).
  - `runs.using: composite`: a box `<name or path> (composite)` (with the step's name and `if:`) in place of the
    step, holding its steps chained like a job's.
  - `node20`, `node24`, …: stays a black box, with `JS action (node20)` and `inputs: a*, b` (`*` for required ones).
  - `docker`: a black box with `Docker (<image>)` and its inputs.
- A box says why it was **not followed**, below the reference as written:
  - `not found` (dashed border): no such workflow; `not found (no action.yml)`: neither `action.yml` nor
    `action.yaml`.
  - `outside the repository` or a short reason (red border): a path leaving the root, also through a symbolic link,
    a file over 1 MB or unreadable (`unreadable (AccessDeniedException)`), a manifest that is not YAML (`line 3: …`)
    or has no known `runs.using`, a workflow the parser rejects (its first error) or that has no jobs.
  - `cycle: back to <where>` (orange, dashed): the file is already being expanded further out, in the job given by its id or
    `this workflow` (`a.yml` → `b.yml` → `a.yml`, an action using itself, however the path is written).
  - `expanded in <where>` (black box): the file is drawn expanded elsewhere in this graph already, in the jobs given by their
    ids (`build / compile`: job `compile` of the workflow job `build` calls); every file is expanded once, so an action
    used by every job shows up in full once.
  - `not expanded (depth limit)`, `(file limit)`, `(diagram limit)` (dotted): at most 10 expansions nest (GitHub's
    limit), at most 50 files are read per graph, and an expansion that would push the graph over its limits (above)
    is left out whole. Later, smaller ones are still tried, up to 10 that did not fit; after that, and once the graph
    is full, called workflows are not even read. The note line says so for the last two.
- Problems GitHub reports in a called workflow go into the note line (`Called workflows with problems: ./…`). If the
  parser fails on one, its box says so (`parser failed: …`), later ones say `parser unavailable`, and the whole graph
  is retried once and then kept, as when the workflow's own parse fails.
- The graph is kept for the workflow's own text: a change to a file it refers to shows once the workflow is edited or
  opened again. Files are read on the parsing thread, never on the FX thread; once the text is edited again, the
  outdated graph stops reading and is dropped.

## Not yet
- Remote actions and reusable workflows through a git cache.
- Clicking a box to jump to its line.
