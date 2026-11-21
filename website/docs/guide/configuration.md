---
title: "Configuration"
description: "Configure the host and every server in paperhive.yml."
---

# Configuration

Everything about a hive is in one file, `paperhive.yml`, in the folder Paperhive runs from. It's written for you on
the first start, with every optional key shown in comments.

## A complete example

```yaml
ConfigurationVersion: 1

# What every server runs
Software: papermc
Version: 1.21.11

# Settings of Paperhive itself (all optional)
Host:
  Plugins: plugins
  Cache: .paperhive-cache
  StopTimeout: 60
  MemoryWarning: 90
  RestartDelay: 3
  MaxCrashes: 3
  CrashWindow: 600
  VelocitySecret: ../velocity/forwarding.secret
  Logs: logs
  Instances: instances
  Console:
    Prompt: hive
    Colors: true

# What every server gets unless it says otherwise (optional)
Defaults:
  Restart: on-crash
  Ram: 1G

# What runs
Servers:
  - Name: lobby
    Port: 25565

  - Name: survival
    Port: 25566
    IP: 0.0.0.0
    Root: servers/survival
    Args: [--nogui]
    Autostart: true
    Restart: on-crash
    DisabledHivePlugins: [Chat]
    SharedPlugins: [shared/plugins]
```

The full list of keys, types and defaults is in the [paperhive.yml reference](../reference/host-config.md). This page
explains how to use them.

## Rules of the file

- **Keys are PascalCase.** `Port`, not `port`. A key in the wrong case or spelling is an error that names the right
  one: `Servers[0] (lobby).port: unknown key, did you mean Port?`
- **Every problem is reported at once.** Paperhive refuses to start with an invalid file and lists everything that's
  wrong, so you can fix it in one go.
- **It's upgraded for you.** When a new Paperhive changes the format, it migrates the file on start and keeps the
  original as `paperhive.yml.v<N>.bak`. Don't edit `ConfigurationVersion` yourself.
- **Comments survive.** Commands that change the file (`create`, `set`, `delete`) edit it in place and keep your
  comments when they can.

## Servers

Each entry under `Servers` is one server: Paper unless it says otherwise.

### Software and Version

Every server runs the top-level `Software` and `Version` unless it sets its own. A hive can mix Paper servers with a
Velocity proxy:

```yaml
Servers:
  - Name: lobby
    Port: 25566
  - Name: proxy
    Port: 25565
    Software: velocitymc   # Version defaults to the newest supported Velocity
```

See [Velocity](velocity.md#inside-the-hive) for what the proxy does there.

### Name and Port

The only required keys. `Name` is also the server's folder name and its prefix in the console, so it's limited to
letters, digits, `-` and `_` (up to 32). Names are case-insensitive: `Lobby` and `lobby` can't both exist. Every
server needs its own port.

### Where it lives: Root and Plugins

`Root` is the server's folder, `instances/<Name>` by default (`Host.Instances` moves that parent folder). It's a normal Paper server folder: `server.properties`,
worlds, `plugins/`, `logs/`. You can point it at an existing Paper server folder to bring that server into the hive.

`Plugins` moves the server's Paper plugins folder somewhere else (default `<Root>/plugins`).

### Listening address: IP

By default a server listens on every address. Set `IP` to bind one, e.g. `127.0.0.1` for a server only reachable
through a proxy on the same machine:

```yaml
  - Name: survival
    Port: 25566
    IP: 127.0.0.1
```

### Starting: Autostart and Restart

- `Autostart: false` creates the server but leaves it stopped until you `start` it.
- `Restart: on-crash` starts it again after a crash. A crash is a non-zero exit (Paper exits with 70 after a crash)
  or its main method throwing. A normal `stop` is never restarted. See
  [Memory and crashes](memory-and-crashes.md) for the limits.

### Memory: Ram

Every server shares Paperhive's one heap (`-Xmx`), and Java can't cap one server inside it. `Ram` instead reserves
a share of the heap for the server while it runs: it won't start unless that much is still free next to the other
running servers' `Ram`. Use `M` or `G` (a bare number is MB):

```yaml
  - Name: lobby
    Port: 25565
    Ram: 1G
```

A server without `Ram` reserves nothing; only the whole heap limits it. A crash restart keeps its reservation. See
[Memory and crashes](memory-and-crashes.md).

### Extra arguments: Args

Arguments passed to Paper for this server, as a list:

```yaml
    Args: [--nogui, --world-container, worlds]
```

::: callout tip
Launching Paperhive with arguments (`java -jar paperhive.jar --help`) passes those to every server *instead of*
Paperhive's defaults. That's mostly useful for one-off checks.
:::

### Plugins per server: SharedPlugins and DisabledHivePlugins

- `SharedPlugins` adds folders of Paper plugin jars that several servers load. See
  [Shared plugins](shared-plugins.md).
- `DisabledHivePlugins` turns named [Hive plugins](../plugins/overview.md) off for this server: their server half isn't
  loaded there and their host half isn't told when it starts.

## The Host section

`Host` configures Paperhive itself. Leave it out to use the defaults. Changes apply the next time Paperhive starts.

| Key              | Default            | What it does                                                                                                                  |
|------------------|--------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `Plugins`        | `plugins`          | The Hive plugins folder.                                                                                                      |
| `Cache`          | `.paperhive-cache` | Where the downloaded and patched Paper jar is kept. Several hives on one machine can share one cache.                         |
| `Mirror`         | Paperhive's mirror | Where Paper is downloaded from. Must be `https://`; every jar is checked against its SHA-256.                                 |
| `StopTimeout`    | `60`               | Seconds a stopping server gets to save and exit before Paperhive gives up on it.                                              |
| `MemoryWarning`  | `90`               | Warn when the shared heap is still this % full after a garbage collection.                                                    |
| `RestartDelay`   | `3`                | Seconds before a `Restart: on-crash` server starts again.                                                                     |
| `MaxCrashes`     | `3`                | Stop restarting a server after this many crashes...                                                                           |
| `CrashWindow`    | `600`              | ...within this many seconds.                                                                                                  |
| `VelocitySecret` | not set            | Velocity's `forwarding.secret`: set up [modern forwarding](velocity.md#forwarding-player-info) on every server at each start. |
| `Logs`           | `logs`             | Paperhive's own log folder (`latest.log`), also where heap dumps go.                                                          |
| `Instances`      | `instances`        | Where a server without `Root` gets its folder: `<Instances>/<Name>`.                                                          |
| `Console`        | see below          | How the console looks.                                                                                                        |
| `Unsafe`         | see below          | Unsafe and experimental options.                                                                                              |

### Console

```yaml
Host:
  Console:
    Prompt: hive                  # the name in the prompt: `hive > `
    Banner: true                  # the logo at start
    Colors: true                  # false: plain text, even in a colour terminal
    History: .paperhive-history   # where typed commands are remembered
```

### Unsafe options

These trade safety for flexibility. When any differs from its default, Paperhive logs a warning at start.

```yaml
Host:
  Unsafe:
    UseMachineRam: true
```

| Key             | Default | What it does                                                                                                                                              |
|-----------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UseMachineRam` | `false` | Count servers' `Ram` against the machine's memory instead of the heap. Servers may then start with more `Ram` than the heap holds, and run out of memory. |
| `HookTimeout`   | `30`    | Seconds a stopping server's shutdown hooks get (1-3600). Too short and plugins may not finish saving.                                                     |
| `ExitGrace`     | `5`     | Seconds a stopped server's leftover threads get before they're interrupted (0-3600).                                                                      |
| `LeakTimeout`   | `10`    | Seconds after that before leftover threads are reported as leaked and the server is let go anyway (0-3600).                                              |

## Defaults for every server

`Defaults` holds settings every server gets unless it sets its own:

```yaml
Defaults:
  Restart: on-crash
  Ram: 1G
  Args: [--nogui]
Servers:
  - Name: lobby
    Port: 25565
  - Name: survival
    Port: 25566
    Ram: 4G          # its own value wins
```

It takes `IP`, `Args`, `Autostart`, `Restart`, `DisabledHivePlugins`, `SharedPlugins`, `EULA` and `Ram`. A server's own
value replaces the default whole (an `Args` list isn't merged). Paper-only keys aren't given to a Velocity server.
`Name`, `Port`, `Root` and `Plugins` differ per server, and `Software`/`Version` already have top-level defaults.

## Changing settings while running

Use `set` at the console; it validates the change and writes nothing if the result would be invalid:

```text
hive> set survival Port 25570
hive> set survival Restart on-crash
hive> set survival Args [--nogui]
hive> set survival Restart            # no value: back to the default
```

A server picks up its new settings at its next start (`restart survival`). `Name` can't be changed: delete the
server and create it again (its folder is kept).
