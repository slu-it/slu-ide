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
- **`uses:` is a black box** (thick border): actions and reusable workflows are not opened yet.
- A line above the graph names problems: GitHub's errors (`2 problems; first: Unexpected value 'colour' (line 2).`),
  jobs that need unknown jobs, and what was left out when the graph is too large (at most 200 boxes, 400 links and
  about 45,000 characters, below Mermaid's limits). The graph still shows what could be read.
- A file without jobs to draw (empty, broken YAML, not a workflow) shows only that line: GitHub's first error, or
  "No jobs in this workflow."
- Labels are escaped: names, conditions and expressions (`${{ … }}`) are drawn as text and cannot start Mermaid
  statements; `securityLevel: 'strict'` stays.

## Not yet
- Following `uses:` into local actions and reusable workflows, then remote ones through a git cache.
- Clicking a box to jump to its line.
