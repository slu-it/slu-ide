# Idea: Docker features left for later

**Status:** idea, collected 2026-11-01 while planning the Docker integration
([ADR 0117](../adr/0117-docker-through-the-cli.md), [ADR 0118](../adr/0118-services-menu.md)); nothing decided or
built. These were left out of the three rounds (Docker tab, Compose files, Dockerfiles) on purpose; each needs a
decision of its own before it is built.

## Docker tab
- **Push and registry login**: `docker push` of an image, `docker login` with credentials kept by Docker's credential
  helpers, not by Thoreau.
- **Remote contexts and a context switcher**: today the current context or connection of the user's shell is used and
  only named in the header. Switching would mean `docker --context` on every call, SSH and TLS contexts, and Podman
  connections.
- **A Files tab for containers** with `docker cp` to and from the host.
- **A "Start Docker" button** in the "not available" note (Docker Desktop, Colima, `podman machine start`), which
  differs per platform and engine.
- **Compose project actions in the tab** (Up, Down, Restart for a project node), beside those of the Compose view.
- **Open Compose File** from a Compose project node (its working folder's compose file).

## Compose and Dockerfile views
- **Shortcut+Enter** to run a compose file or build a Dockerfile, as for shell scripts.
- **Gutter ▶ markers in Dockerfiles** (build up to a stage).
- **A picker for extra compose `-f` files** beyond the override file Docker uses by default.
