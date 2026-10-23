# GitHub Actions workflow graph

**Modules:** `previews` (`WorkflowFileViewer`, `WorkflowPreview`, `WebViewWorkflowParser`, `WorkflowMermaidExport`)
and `github-actions` (model and drawing, `WorkflowGraphs`, the `RemoteSources` interface for remote files), `git`
(`RemoteSourceCache`) and `ide` (`WindowWorkflowSources`, the window's `RemoteSources`); decisions in
[ADR 0075](../adr/0075-github-actions-workflow-graph.md), [ADR 0076](../adr/0076-cache-of-remote-action-sources.md),
[ADR 0077](../adr/0077-graph-nodes-open-their-source.md) and [ADR 0074](../adr/0074-path-matched-file-viewers.md)

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
- **Export → Mermaid** (beside the zoom controls) writes `<name>.mmd` beside the workflow (`ci.yml` and `ci.yaml`
  give `ci.mmd`), which GitHub ignores and Thoreau opens in its Mermaid viewer. It is the graph as shown, including
  remote references fetched or cached, the inputs in their boxes (other renderers draw the block without its frame),
  and without the box links. It asks before replacing an existing file, and the note line (GitHub's errors, left-out
  parts, unfetched files) comes back as problems in the export's report. While the graph is still being read, or the
  file has no jobs, the export fails with a message saying so.
- The parser is GitHub's own (`@actions/workflow-parser`), so what it accepts, rejects and reports is what GitHub
  does.

## What is drawn
- **Triggers**: a box per event of `on`, in file order, stacked vertically, with its filters
  (`push · branches: main, master`, a schedule's crons, the names of `workflow_dispatch` inputs).
- **A box around each job**, titled with its `name` (else its id), `runs-on` and a matrix summary
  (`matrix os×java (6)`, `matrix (dynamic)` when built by an expression). Inside, its `run` and `uses` steps from top
  to bottom: a step's name, else `run:` with the script's first line; for `uses` the reference, **bold**, below the
  step's name; `if:` conditions as written. A job that calls a reusable workflow is one box with `calls <reference>`
  (the reference bold) and `secrets: inherit`.
- **Inputs**: a `uses:` step's `with:` (and a reusable workflow job's) is shown inside the step's box, below its name,
  reference and type, as a block with a thin dashed, rounded frame: one `name: value` line per input actually set (a
  multi-line value shows its first line and ` …`), at most 8 lines then `+N more`, each on one line. An expanded
  composite action or called workflow shows them in a dashed box at the top of its box instead, above its steps or
  the jobs that need none (kept there by invisible links). No `with:`, no block. Inputs are added last: those that do
  not fit the diagram's limits are left out, without a note, and the step stays.
  - The block is a `div.sluide-inputs` in the box's label (Mermaid sizes the box around it), framed by `preview.css`;
    it has no opacity, transform or filter, which JavaFX's WebKit paints at the page's origin inside an SVG label.
- **Links**: from each job a job `needs`, or from the triggers when it needs none. A job with an `if:` gets dotted
  links labelled with the condition.
- **Local `uses:` are drawn nested** (next section), and so are remote ones once fetched
  ([Remote `uses:`](#remote-uses)); `docker://` images and references the parser cannot read are a **black box**
  (thick border) with the reference.
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
  calling job's box as a box `calls x.yml` (the file name bold), holding its jobs with their steps and `needs` links,
  like the workflow's own; the job's `with:` is a dashed box at its top.
- **Local action** (`steps[*].uses: ./path`): `<root>/path/action.yml`, else `action.yaml`, read as YAML with every
  value kept as text (so `on`, `yes` stay words; no types are built from tags, aliases, nesting and size are limited).
  - `runs.using: composite`: a box `<name or path> · <composite>` (name bold, `<composite>` italic; with the step's
    name and `if:`) in place of the step, holding its steps chained like a job's, each with its inputs, and the
    step's own `with:` in a dashed box at its top.
  - `node20`, `node24`, …: stays a black box, with an italic `<JS action (node20)>` below the reference.
  - `docker`: a black box with an italic `<Docker (<image>)>`.
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

## Remote `uses:`
Remote actions (`owner/repo@ref`, `owner/repo/path@ref`) and remote reusable workflows
(`owner/repo/.github/workflows/x.yml@ref`) are read from the window's remote sources (`RemoteSources`, a window
service the app registers; how it caches and fetches: [ADR 0076](../adr/0076-cache-of-remote-action-sources.md)).
Without that service they stay black boxes.
- **Not fetched by default:** drawing the graph only reads what is cached, never the network. A reference that is not
  cached is a box `not fetched` (dashed border), and the note line says
  `N references not fetched — Fetch References to show them.`
- **Fetch References** (a button on the right of the toolbar, before Source / Split / Preview, only while the graph
  shows): draws the graph again, letting the remote sources fetch every repository and ref the graph needs (each once
  per fetch; a branch or tag again, a full commit SHA only if not cached), as a background task "Fetching workflow
  references" of the window (cancellable there). Meanwhile the note line says `Fetching references…` and the old graph
  stays. The new graph is kept like a parsed one; later drawings, also after an edit, find the files in the cache. The
  button is disabled while a fetch runs and for a graph without remote references; it is offered again afterwards to
  refresh branches and tags. Editing the file or closing it stops a fetch; its graph is dropped.
- Followed like local ones: `action.yml`, else `action.yaml`, under the reference's path (normalised; a `..` leaving
  the repository is `outside the repository`). A composite action is drawn nested as
  `owner/repo[/path]@ref · <composite>`, JS and Docker actions are annotated black boxes, a reusable workflow is parsed
  by the same parser and drawn nested as `calls x.yml`.
- Inside a remote file, a reusable workflow's `./.github/workflows/y.yml` is read from the same repository and ref; an
  action's `./…` (in a composite action or a remote workflow's step) refers to the caller's workspace checkout and is a
  black box `workspace-relative`. Remote references in remote files are followed in turn.
- Boxes say why a remote reference was not followed: `not found` (`not found (no action.yml)` for actions; dashed),
  or the reader's reason (red, e.g. the repository could not be fetched). The same guards apply as for local ones:
  cycles (also across repositories, `a@v1` → `b@v1` → `a@v1`), 10 nested expansions, 50 files, the diagram limits and
  the parser's failures.

### Where remote files come from
- **Hosts:** a reference `owner/repo` is looked for on the host of the project's `origin` remote first, if that is not
  github.com (GitHub Enterprise Server, which may also serve github.com's actions through GitHub Connect), and then on
  github.com. The origin is read only if the folder is trusted for git (a graph drawn right after the folder opened
  waits for the trust checks, at most 30 s; only that view waits, and it stops waiting when the workflow is edited or
  its view closes); otherwise, without a folder, or with an origin that is not an `https://`, `http://`,
  `ssh://` or `[user@]host:path` URL (`git+ssh://`, `ssh+git://`, local paths and others), only github.com is asked.
- **How:** a host is reached as the origin is, if it is that host: by ssh with the origin's user and port
  (`git@host:owner/repo.git`, `ssh://user@host:2222/owner/repo.git`), otherwise by https (an `http://` origin too, on
  the default port). Thoreau takes no token; git uses the user's ssh keys and credential helpers, and does not ask on
  a terminal unless the environment sets its own ssh (`GIT_SSH_COMMAND`, ADR 0076). Credentials in the origin's URL
  are never used. Owner and repository names count without case.
- **Fetch References** fetches each repository and ref once per click: from the origin's host and, if that fails,
  from github.com. A box that could not be fetched from either says why for each host (e.g.
  `ghes.example.com: repository not found or no access; github.com: network error`). A host that could not be reached
  (network error, timeout) is not asked again during that fetch.
- **The cache** is `~/.sluide/cache/git/<host>/<owner>/<repo>.git`: shallow bare repositories that hold one commit's
  folders per fetched ref and the files that were read (at most 1 MB each), shared by all windows. Nothing is removed
  automatically; delete the folder by hand to free the space (not while a fetch runs). Then references show
  `not fetched` again until they are fetched.

## Going to the source
A click on a box opens where it is written ([ADR 0077](../adr/0077-graph-nodes-open-their-source.md)); the pointer is
a hand over those boxes and their label is underlined while hovered.
- **Which boxes:** steps, the box of a job's subgraph title, the call of a reusable workflow (the job's line), a
  composite action's or called workflow's title (the line of its `uses:`), black boxes and the boxes saying why a
  reference was not followed (the line of their `uses:`), all also inside nested workflows and actions, at the line
  of the file they come from. Not the triggers, not the inputs box at the top of an expanded composite action or
  called workflow (its title opens the same line), and not the area of a subgraph: a click there zooms as before.
- **A file of the repository** (the workflow itself, a local reusable workflow, an `action.yml`) opens in its tab, or
  a new one, with the caret at the start of the line, scrolled into view. A workflow whose preview shows alone shows
  its source beside it. Paths stay within the repository's root.
- **A remote file** opens in the browser on the page of the file at the commit it was read at, at the line
  (`https://<host>/<owner>/<repo>/blob/<commit>/<path>#L<line>`, by https on the host it was read from, also when
  fetched over ssh). Boxes of a remote file whose page is not known are not clickable.
- In `github-actions`, `WorkflowGraph.locations` maps the Mermaid ids to `SourceLocation`s; the page lists the
  clickable ids in `data-node-links`, `preview.js` marks the drawn elements and hands a click to
  `WorkflowPreview.openNode`, which opens it through `FileViewHost.openFile(file, line)` or `openWebPage`.
