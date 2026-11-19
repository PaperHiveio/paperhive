---
title: "Velocity"
description: "Register the hive's servers with a Velocity proxy automatically."
---

# Velocity

The Paperhive Velocity plugin keeps your proxy's server list in sync with the hive: when a server starts it's added to
Velocity, and when it stops it's removed. No more editing `velocity.toml` every time you create a server.

## Install

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

Velocity's modern forwarding has to be enabled on each Paper server, as usual:

1. In Velocity: `player-info-forwarding-mode = "modern"`, and note the secret in `forwarding.secret`.
2. In each server's `config/paper-global.yml`:

   ```yaml
   proxies:
     velocity:
       enabled: true
       online-mode: true
       secret: "<the secret>"
   ```

3. In each server's `server.properties`: `online-mode=false`.

::: callout tip
Bind the servers to `127.0.0.1` (`IP: 127.0.0.1` in `paperhive.yml`) so players can only reach them through the proxy.
:::
