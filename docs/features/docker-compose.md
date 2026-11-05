# Compose files

**Module:** `modules/docker`, package `dev.sluide.docker.compose` (`ComposeFileViewer`, `ComposeView`, `ComposePane`
with `ComposeToolbar`, `ServiceList` and `EnvironmentSection`, the FX-free `ComposePaneModel` and `ComposeRules`; the
model of options, commands, configuration and service states beside them); decisions in
[ADR 0119](../adr/0119-compose-files.md), the engine access in [ADR 0117](../adr/0117-docker-through-the-cli.md)

Compose files open as YAML source beside a Compose pane that starts, stops and watches the project, like
[shell scripts](shell-scripts.md) run from their view. The containers also show in the [Docker tab](docker.md).

## Which files
- The names compose looks for, `compose.yaml`, `compose.yml`, `docker-compose.yaml` and `docker-compose.yml`, and their
  variants `compose.<part>.yaml` / `docker-compose.<part>.yml` (`compose.override.yaml`, `docker-compose.prod.yml`),
  ignoring case. They no longer get the [YAML preview](yaml-preview.md); other YAML files keep it.
- Highlighted as YAML; Shortcut+/ comments with `#`.

## The view
- [Soft Wrap](soft-wrap.md) is the first button of the toolbar, left of the modes, and hidden while only the Compose pane shows.
- The toolbar has the modes **Source | Split | Compose**; the file opens in **Split**. Compose is the pane:
  1. **Toolbar:** **Up** with **Detached** (on by default, stored per file), **Down** (its menu also has **Down and
     Remove Volumes…**), **Build**, **Pull**, **Restart**. They are disabled while the engine is not available or the
     file's configuration could not be read; a note says why ("Connecting to Docker…", "Docker is not reachable: …",
     or the message that the executable was not found).
  2. **Options:** **Profiles** (a menu of the file's profiles to tick; the button names the chosen ones),
     `--build`, `--force-recreate`, `--remove-orphans`, and **Project** (the project name; the empty field shows the
     name compose picks; an invalid name gets a red outline and is not used).
  3. **Services**, in the order of the file, then orphans (containers of services no longer in the file): a state dot,
     the name, the state (`running`, `exited`, `2/3 running`, `not created`), the ports, the profiles (dimmed), and
     **Up** (`up -d <service>` in the Output), **Stop** and **Restart** (on the service's containers, directly, without
     output; a failure shows in a red line below), **Logs** (opens the [Docker tab](docker.md) with the container and
     its Logs tab) and **Shell** (a terminal tab with a shell in the container). The buttons follow the states: Up
     for the file's services, Stop for running or restarting containers, Restart and Shell for running ones, Logs once
     a container exists. The list follows the engine live while the view is open.
     "Also uses compose.override.yaml" says when an override file is added; a file that cannot be used shows
     compose's message here instead ("✖ The compose file cannot be used: …").
  4. **Environment** (collapsed at first; its title counts unresolved variables, e.g. "Environment (⚠ 1 not set)"):
     - **Env files:** **Add…** picks a file (stored relative to the compose file's folder when inside it), **Remove**,
       **Use Default**. While the list was never changed, compose reads the `.env` beside the file (the list says
       whether there is one); adding the first file keeps an existing `.env` in the list; an emptied list reads none.
     - **Variables:** Name | Value rows with ✕, **Add**, values masked until **Show values**. Names must be valid
       environment variable names (others are marked red and not used).
     - A warning line names the **unresolved variables** (`${VAR}`s without a value, which compose replaces by a
       blank string).
  5. **Output:** the status line with **Stop** and **Rerun**, and the terminal of the last command (no arguments row).
     Attached Up (Detached off) runs there, and Stop (Ctrl+C) stops the stack. **Rerun** runs the last command that
     started again like its button, with the current options and the same questions (Down and Remove Volumes asks
     again).
- The services and the Environment section scroll together below the toolbar, so an expanded Environment does not
  squeeze the services.
- The toolbar and the pane use the UI font; going to a line while only the pane shows switches to Split first.
- Outside an IDE window the pane stays disabled ("Compose commands run in an IDE window.").

## Running
- **Up** (the button, **Run** in the explorer's context menu, a service's **Up**, or the gutter's **▶** on each
  service of the source, tooltip "Up <service>") shows the pane beside the source if only the source shows.
- Before an Up: unsaved changes ask **Save and Up**, **Up Saved Version** or **Cancel**; unresolved variables ask
  "Variables X, Y are not set and default to a blank string. Up anyway?". **Down and Remove Volumes…** asks too; no
  other command does. An Up asked for while the engine is being reached or the file read (e.g. explorer Run on a
  file just opened, right after Save and Up, or right after a change of the variables or project name) runs once that
  is done, so the question about unresolved variables is about the current file; meanwhile the toolbar says "Up waits
  for the configuration…".
- Every command runs in the compose file's folder as `<docker> compose -f <file> [-f <override>] [-p <name>]
  [--env-file <f>]… [--profile <p>]… <verb>` in the user's login shell. `<docker>` is the executable the Docker
  session found ([settings.md](settings.md)). With variables in the Environment table the line starts with
  `env "NAME=$SLUIDE_COMPOSE_ENV_0" …`: the values go into the terminal under private names, and the shell hands them
  to compose under their own names after its start files ran, so a `.zshrc` that sets the same name does not win and
  the values never show in the terminal.
- Profiles: Up, Build, Pull and Restart use the chosen ones; Down uses every profile, so it also removes services of
  profiles chosen earlier; a single service's commands work whatever is chosen.
- Variables: the Environment table beats the env files, which beat the `.env`.
- Starting a command while another runs asks first whether to stop it; closing the tab while one runs asks too.

## Configuration
- The services, profiles, project name and unresolved variables come from `docker compose config --format json` with
  every profile. It is read when the view opens, after each save (or reload from disk), shortly (300 ms) after the
  project name or environment change, and when the engine becomes available.
- The options (Detached, profiles, flags, project name, env files, variables) are stored per file in the project data
  (field `compose`, [ADR 0090](../adr/0090-per-project-metadata-store.md)), 300 ms after the last change and when the
  view closes; a moved file keeps them.

## Limits
- No Shortcut+Enter, no picker for extra `-f` files, no "Open Compose File" from the Docker tab
  ([../ideas/docker-later.md](../ideas/docker-later.md)).
- No project-trust check: every command is an explicit click, and reading the configuration runs no project code.
- Podman compose is unverified.
