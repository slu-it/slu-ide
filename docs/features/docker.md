# Docker tab

**Modules:** `modules/docker` (package `dev.sluide.docker`: the window bean `Docker` and `DockerSession`; the CLI in
`cli`, the model in `model`, event routing in `events`, the tab in `tab` with the tree in `tab.tree`, the details in
`tab.details`, the Logs and Stats views in `tab.logs` and `tab.stats`, Settings → Docker in `settings`); menu in `ide`
(`ToolsMenu`, `WindowTools`); decisions in [ADR 0117](../adr/0117-docker-through-the-cli.md) and
[ADR 0118](../adr/0118-services-menu.md)

The Docker tab shows what runs on the container engine (Docker or Podman) and manages it: containers, grouped by
Compose project, images, volumes and networks. It talks to the engine only through its CLI with JSON output, whatever
engine the user's terminal reaches (Docker Desktop, Colima, OrbStack, Rancher Desktop, Podman).

## Opening
- **Services → Docker** (Alt+8, Cmd+8 on macOS) opens the tab. There is one Docker tab per window: choosing the item
  again selects the open tab. Shortcut+W closes it; it is not restored after a restart.
- Other views open it with a container selected (round 2's Compose view, "Logs" of a service selects the container's
  Logs tab).

## Layout
- **Header:** "Docker", the engine (flavor and server version, and the current Docker context, e.g.
  `Docker 29.8.1 · context desktop-linux`), and **Refresh**. **F5** in the tab refreshes too.
- **Error bar** at the top: a failed action shows the CLI's message there until dismissed with ✕.
- **Left: the tree**, with a **filter** field on top (case-insensitive, over names, images, tags and the Compose project
  and service), then four groups with the number of rows each shows:
  - **Containers (n)**: Compose projects first, the projects whose working folder is inside the open project folder
    on top, marked bold with "this project"; their containers below them; then the standalone containers. A container
    row has a state dot (running green, paused amber, restarting blue, exited or dead grey) and its name; its tooltip
    names the image and status.
  - **Images (n)**: `repository:tag`, untagged ones as `<none>` with the short id, the size dimmed.
  - **Volumes (n)**: the name (anonymous volumes shortened to 12 characters), the driver dimmed.
  - **Networks (n)**: the name and the driver.
- **Right: the details** of the selected row.

The lists follow the engine's events (each change reloads the affected list after a short wait), so the tree updates
by itself; expansion and selection stay where they were across these reloads. A selected resource that is removed
leaves the selection empty.

## Engine states
- While the executable is looked up and the engine reached the first time, the tab shows a quiet
  "Connecting to Docker…".
- When the executable is not found, or the engine does not answer (Docker Desktop not running, …), the tab shows the
  message in place of the tree, with **Retry**. The tab retries by itself (after 2 s, doubling up to 30 s) and shows the
  tree again once the engine answers.

## Containers
The details have a header with the name, the status and the action buttons, and the tabs **Overview**, **Logs**,
**Stats**, **Environment** and **Inspect**. The same actions are in a row's context menu.

| Action | Offered for | Asks first |
|---|---|---|
| Start | created, exited, dead | no |
| Stop, Kill | running, restarting | Kill: yes |
| Restart, Pause | running | no |
| Unpause | paused | no |
| Shell | running | no |
| Remove | always; a running, restarting or paused container is stopped and removed (force), which the question says | yes, with "Also remove anonymous volumes" |

A button is disabled while its action runs; a failure goes to the error bar.

**Shell** opens a normal terminal tab named `<container> (shell)`, in the project folder (the home folder without
one), running `docker exec -it <id>` with bash if the container has it, else sh, in the login shell, with the
environment the Docker CLI was found with.

- **Overview:** image, state and status, health, created and started (relative, the time in the tooltip), ports
  (published TCP ports are links that open `http://localhost:<port>` in the browser, or the address they are
  published on), mounts (source → destination, read-only marked), networks with the address, command and entrypoint,
  restart count, exit code, the Compose project, service and working folder, and all labels folded. It is loaded when
  the container is selected ("Loading…" meanwhile) and again when its list entry changes.
- **Logs:** follows `docker logs -f --timestamps`, with a filter (case-insensitive, keeps the matching lines), a
  Timestamps toggle, Wrap, Follow (stays at the newest line; scrolling up turns it off), Clear and a tail limit (Last
  100, Last 1000 (the default), Last 10 000 or All); ANSI escapes are stripped and at most 50 000 lines are kept.
  Shortcut+C or Copy in the context menu copies the selected lines (several with Shift- or Ctrl-click). When the
  container stops, a "— container stopped —" line marks it, and the logs follow again once it runs, also after a quick
  restart (seen as its list entry changing).
- **Stats:** live CPU, memory, network and block IO, with sparklines of the last 60 samples. Network and block IO are
  shown as rates averaged over those samples, so the rate and its sparkline lag by up to about a minute: a burst stays
  visible for about 60 s and its value tapers off. `docker stats` rounds its counters to three significant digits, so they move in steps (above 1 GB in steps of 10 MB); rates are averaged over the last 60 samples (about a minute), which keeps the error of one step small, but a rate below one step per minute still reads 0 or one step. A stopped container shows "Not running" with
  its last values greyed; a stream that ends while the container runs shows "Stream ended", and the stats start again
  when it is (re)started.
- **Environment:** the variables as a Name | Value table, values masked (`••••••`) until **Show values** is on; Copy in
  a row's context menu copies `NAME=value`.
- **Inspect:** the raw `docker inspect` JSON, read-only and highlighted, loaded when the tab is first shown for the
  container.

Logs and Stats stream only for the selected container while their details tab is selected; choosing another
details tab or another row stops them. They keep streaming while the Docker tab itself is hidden behind another editor
tab, so switching editor tabs keeps the log lines and the 60 samples.

## Images, volumes and networks
Each has an **Overview** and **Inspect**, and **Remove** in the header and the row's context menu. Selecting a group
shows its actions; they are also in the group row's context menu.

- **Images:** the Overview shows reference, id, digest, size, created and the containers created from it. Remove asks
  first; when stopped containers use the image, the question offers to force it. **Pull…** asks for `name:tag` and
  pulls it as a background task whose name shows the CLI's progress; its ✕ cancels the pull. **Prune Dangling
  Images…** lists the dangling images it would remove (disabled when there are none).
- **Volumes:** name, driver, mountpoint, the containers using it (or whether any does), labels. Remove asks first.
  **Create Volume…** asks for a name; **Prune Unused Volumes…** lists the unused volumes, named ones included.
- **Networks:** name, id, driver, scope, the containers connected, labels. Remove asks first and is disabled for the
  engine's own networks (`bridge`, `host`, `none`, `podman`). **Create Network…** asks for a name and a driver
  (bridge, the default, overlay or macvlan); **Prune Unused Networks…** lists the unused networks.
- **Compose projects:** an overview of the working folder and the containers with their states; project actions come
  with the Compose view.

Pulls, prunes, removing an image, volume or network and creating one run as rows of the window's background tasks; the
lists follow through the events.

## Settings and Podman
- **Settings → Docker** sets the executable: `docker` (the default), `podman`, or the full path of either. It is looked
  up once per window in the login shell, with the variables that point it at its engine (`DOCKER_HOST`,
  `DOCKER_CONTEXT`, `CONTAINER_HOST`, …); a change connects again at once ([settings.md](settings.md)).
- With Podman the header shows `Podman <version>` and no context. Podman's `ps` does not name the volumes a container
  mounts, so "used by" of a volume shows only whether any container uses it.

## Limits
- No remote contexts and no context switcher: whatever context or connection is current in the shell is used.
- No push or registry login, no files of a container (`docker cp`), no "Start Docker" button.
- Docker before 23 is not supported (no JSON output).
- Later ideas: [../ideas/docker-later.md](../ideas/docker-later.md).

## Keys
F5 refreshes; Alt+8 (Cmd+8 on macOS) opens the tab ([keyboard-shortcuts.md](keyboard-shortcuts.md)).
