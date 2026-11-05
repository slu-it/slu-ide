# Dockerfiles

**Module:** `modules/docker`, package `dev.sluide.docker.dockerfile` (`DockerfileBuildViewer`, `DockerfileView`,
`BuildPane` with `BuildForm`, the FX-free `BuildPaneModel` and the pure `BuildRules`, `BuildCommands`,
`parseDockerfile` and `DockerBuildStore` beside them); the engine access in
[ADR 0117](../adr/0117-docker-through-the-cli.md), the pattern of the view in [ADR 0119](../adr/0119-compose-files.md)

Dockerfiles open as source beside a Build pane that builds the image and then offers to run it, like
[compose files](docker-compose.md) run from theirs. The images also show in the [Docker tab](docker.md).

## Which files
- `Dockerfile` and `Containerfile` by name, `app.Dockerfile` by the extension `dockerfile`, and `Dockerfile.dev`,
  `dockerfile.prod.local` by the pattern `Dockerfile.<anything>`, all ignoring case. The pattern ranks below the viewers
  for an extension (`NamedFileViewer.weakFileNamePatterns`, [file-viewers.md](file-viewers.md)), so `Dockerfile.md`
  stays Markdown and `Dockerfile.yaml` YAML; the ignore file `Dockerfile.dockerignore` goes to the ignore files' viewer.
- Highlighted as Dockerfile, also in diffs and Markdown code fences; Shortcut+/ comments with `#`.

## The view
- [Soft Wrap](soft-wrap.md) is the first button of the toolbar, left of the modes, and hidden while only the Build pane shows.
- The toolbar has the modes **Source | Split | Build**; the file opens in **Split**. Build is the pane:
  1. **Toolbar:** **Build** and **Run…**. Build is disabled while the engine is not available or the options are
     invalid; a note says why the engine is not ("Connecting to Docker…", "Docker is not reachable: …", or the message
     that the executable was not found). **Run…** is enabled once a build started from this view ended with exit code 0
     and had a tag, until the next build starts; its tooltip says why it is disabled ("Build with a tag first"). It
     opens the Run dialog of the [Docker tab](docker.md) for the first tag, as entered (Docker adds `:latest` itself).
  2. **Options:**
     - **Tags:** several tags separated by spaces or commas (`myapp:latest, myapp:1.0`). The list holds the texts of
       earlier builds of the file, newest first, at most 10; a build that started records the text.
     - **Context:** the build context folder; empty is the Dockerfile's folder, whose name the empty field shows.
       **Choose…** picks a folder; it is stored relative to the Dockerfile's folder when inside it, else absolute.
     - **Target:** "(last stage)" or one of the stages of the file (`FROM … AS name`). A stored target that is no longer
       in the file still shows, with an orange outline and a tooltip, and the build passes it anyway.
     - **Build args:** a row per `ARG` of the file, in file order (global ones and those of the stages, each name once).
       The default of `ARG NAME=default` shows in the empty value field. Only rows with a value are passed; an empty
       value lets the default apply. Values are not masked: build args are visible in the image's history anyway
       (`docker history`), so they are no place for secrets (use `--secret` mounts for those).
     - **Flags:** `--no-cache`, `--pull`.
     - **Platform:** empty (the engine's), `linux/amd64`, `linux/arm64`, or any `os/arch[/variant]` typed in.
  3. **Command:** a read-only, selectable field (wrapped, three lines high) with the exact command Build runs, or the
     problem with the options ("✖ Not a valid tag: …", "✖ The context folder does not exist: …").
  4. **Output:** the status line with **Stop** and **Rerun**, and the terminal of the last build (no arguments row).
- The stages and ARGs follow the editor's text 300 ms after typing pauses, so the Target list and the Build args follow
  edits without a save.
- The toolbar and the pane use the UI font. Outside an IDE window the pane stays disabled ("Builds run in an IDE
  window.").

## Building
- **Build** (the button, **Run** in the explorer's context menu, or **Rerun**) shows the pane beside the source if
  only the source shows. Unsaved changes ask **Save and Build**, **Build Saved Version** or **Cancel**; Build Saved
  Version passes the ARGs of the file on disk (if the file cannot be read, the status line says so and nothing is
  built). A build while another runs asks first whether to stop it; closing the tab while one runs asks too. Rerun
  goes through Build again, with the current options and the same questions.
- A build asked for while the engine is being reached (e.g. explorer Run on a file just opened) waits for it, with the
  note "Build waits for the engine…", and starts once the engine answers; it is dropped if the engine cannot be
  reached.
- The build runs in the Dockerfile's folder in the user's login shell as
  `<docker> build -f <file> [-t <tag>]… [--target <stage>] [--build-arg "NAME=$SLUIDE_BUILD_ARG_<i>"]… [--no-cache]
  [--pull] [--platform <p>] <context>`, the file and the context as absolute paths, every word in single quotes but the
  build-arg words, which are in double quotes.
  `<docker>` is the executable the Docker session found ([settings.md](settings.md)). The background task row is
  named `Build: build <first tag or file name> (<file>)`.
- Build-arg values never appear in the command line, the preview or the terminal: each value goes into the build's
  environment under a private name (`SLUIDE_BUILD_ARG_0`, `SLUIDE_BUILD_ARG_1`, … in row order), and the word
  `"NAME=$SLUIDE_BUILD_ARG_0"` (in double quotes) is expanded by the shell after its startup files ran. So a startup
  file cannot override a value, and names the docker CLI reads itself (`DOCKER_HOST`, `HOME`, `HTTP_PROXY`) do not
  change the CLI. Values stored for ARGs no longer in the file are not passed.
- Checked before Build is enabled: tags must be image references (lower-case name components, an optional
  `registry[:port]/`, an optional `:tag` of up to 128 letters, digits, `_`, `.`, `-`); the context folder must exist;
  the platform must be `os/arch[/variant]`; the target a stage name (`[A-Za-z0-9][A-Za-z0-9_.-]*`); build-arg names
  `[A-Za-z_][A-Za-z0-9_]*`; values must not contain a NUL character.

## Storing
- The options (tags text, its history, context, target, build-arg values, flags, platform) are stored per file in the
  project data (field `dockerBuild`, [ADR 0090](../adr/0090-per-project-metadata-store.md)), 300 ms after the last
  change and when the view closes; only values that differ from the defaults are written. Build-arg values of ARGs
  no longer in the file are kept, so a temporary edit of the file does not lose them. A moved file keeps its options.

## Limits
- No push, no gutter markers to build up to a stage, no Shortcut+Enter
  ([../ideas/docker-later.md](../ideas/docker-later.md)).
- No project-trust check: every build is an explicit click.
- Podman (`podman build`) is unverified; the executable of the settings is used as `<executable> build …`.
