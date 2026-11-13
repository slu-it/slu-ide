# Content of the thoreau-dev kit: an overlay that puts the install script into the sandbox's filesystem, where the
# kit's install hook (thoreau-dev.yaml) runs it. The packages themselves are installed by that hook, not here: a
# mixin is an overlay on the agent's image and cannot run apt-get against it at build time.
FROM scratch
COPY --chmod=0755 install.sh /opt/thoreau-dev/install.sh
