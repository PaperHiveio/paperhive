---
title: "Velocity"
description: "Register the hive's servers with a Velocity proxy automatically."
---

# Velocity

The Paperhive Velocity plugin keeps your proxy's server list in sync with the hive: when a server starts it's added to
Velocity, and when it stops it's removed. No more editing `velocity.toml` every time you create a server.

## Inside the hive

The simplest setup: let Paperhive run the proxy as one of its servers.

```yaml
Servers:
  - Name: proxy
    Port: 25565
    Software: velocitymc
```

Paperhive downloads Velocity, puts this plugin into the proxy's `plugins/` folder and starts it with the rest. The
plugin reads the hive's servers straight from Paperhive (no socket, no config) and registers every Paper server while
it's `RUNNING`. Run proxy commands from Paperhive's console as `proxy <command>`, e.g. `proxy glist`.

Velocity 4.3 needs Java 25: start Paperhive with Java 25 or newer, or it refuses with a clear message.

## On its own

::: steps

1. **Run Velocity on the same machine as Paperhive.** The plugin talks to Paperhive through its control socket,
   which is a local file.

2. **Add the plugin.** Put `paperhive-velocity-<version>.jar` (from the
   [release](https://github.com/paperhiveio/paperhive/releases)) in Velocity's `plugins/` folder and start Velocity
   once. It writes `plugins/paperhive/config.properties`.

3. **Point it at the hive.** Edit the config:

   ```properties
   # The Paperhive host's control socket: paperhive.sock in the folder the host runs in
   socket=/srv/my-hive/paperhive.sock
   # Where this proxy reaches the hive's servers (they run on the host's machine)
   host=127.0.0.1
   # How often to ask the host, in seconds
   interval=2
   ```

4. **Restart Velocity.** Every `RUNNING` server of the hive now shows up as `127.0.0.1:<port>` under its name.

:::

## How it behaves

- Every `interval` seconds the plugin asks Paperhive for its servers (`list --tsv`).
- A server is **registered** when it's `RUNNING`, and **unregistered** when it stops, crashes, changes port, or when
  Paperhive can't be reached.
- Servers you defined yourself in `velocity.toml` are **never touched**, so you can mix hive servers with others.
- Players still need somewhere to go first: list a hive server in Velocity's `try` list or a forced host.

```toml
# velocity.toml
[servers]
try = ["lobby"]   # "lobby" is registered by the plugin while it runs
```

## Forwarding player info

With Velocity's **modern forwarding**, the proxy tells each server who a player really is. Paperhive sets it up on
every server for you:

1. In Velocity's `velocity.toml`: `player-info-forwarding-mode = "modern"`. Velocity keeps the secret in its
   `forwarding.secret` file.
2. In `paperhive.yml`, point Paperhive at that file:

   ```yaml
   Host:
     VelocitySecret: /srv/velocity/forwarding.secret
   ```

3. Restart Paperhive. Before each server starts, Paperhive writes `proxies.velocity` (enabled, online-mode and the
   secret) into its `config/paper-global.yml` and sets `online-mode=false` in its `server.properties`. Everything else
   in those files is kept.

If the secret file is missing or empty, the server doesn't start and Paperhive says why: without forwarding, the proxy
couldn't log players in. When you rotate Velocity's secret, restart the servers to pick it up.

::: callout info "Doing it by hand"
Without `VelocitySecret`, set it up per server as Velocity's docs describe: `proxies.velocity` in
`config/paper-global.yml` (`enabled: true`, `online-mode: true`, `secret: "<the secret>"`) and `online-mode=false` in
`server.properties`.
:::

::: callout tip
Bind the servers to `127.0.0.1` (`IP: 127.0.0.1` in `paperhive.yml`) so players can only reach them through the proxy.
:::
