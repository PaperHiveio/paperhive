---
title: "paperhive.yml (host)"
description: "Every key of the host's paperhive.yml, with types and defaults."
---

# paperhive.yml (host)

The host's configuration, in the folder Paperhive runs from. For how to use it, see
[Configuration](../guide/configuration.md). This page lists every key.

::: callout info "Two files called paperhive.yml"
This page is about the **host's** config. A Hive plugin's descriptor inside its jar is also called `paperhive.yml`;
see [paperhive.yml (plugin)](plugin-descriptor.md).
:::

## Top level

| Key | Type | Default | |
|---|---|---|---|
| `ConfigurationVersion` | integer | — | The file format version. Written and upgraded by Paperhive; don't change it. |
| `Software` | `papermc`/`velocitymc` | `papermc` | What servers run unless they set their own. |
| `Version` | text | `1.21.11` | The version of `Software` servers run unless they set their own. Only supported versions are accepted. |
| `Host` | section | all defaults | Settings of Paperhive itself, below. |
| `Servers` | list | — | **Required**, at least one server. |

## Host

| Key | Type | Default | |
|---|---|---|---|
| `Plugins` | path | `plugins` | The Hive plugins folder. |
| `Cache` | path | `.paperhive-cache` | Downloaded and patched server jars. |
| `Mirror` | https URL | Paperhive's mirror | Where server jars are downloaded from. |
| `StopTimeout` | seconds, 1-86400 | `60` | How long a stopping server gets before it's given up on. |
| `MemoryWarning` | percent, 1-100 | `90` | Warn when the shared heap is this full after a GC. |
| `RestartDelay` | seconds, 0-86400 | `3` | Wait before restarting a crashed `Restart: on-crash` server. |
| `MaxCrashes` | 1-1000 | `3` | No more restarts after this many crashes... |
| `CrashWindow` | seconds, 1-604800 | `600` | ...within this many seconds. |
| `VelocitySecret` | path | not set | Velocity's `forwarding.secret`. When set, every server is set up for [modern forwarding](../guide/velocity.md#forwarding-player-info) before each start. |
| `Logs` | path | `logs` | Paperhive's own log and heap dumps. |
| `Instances` | path | `instances` | The parent folder of servers without `Root`. |
| `Console.Prompt` | text | `hive` | The name in the console prompt. |
| `Console.Banner` | `true`/`false` | `true` | Show the logo at start. |
| `Console.Colors` | `true`/`false` | `true` | Colour in the console. |
| `Console.History` | path | `.paperhive-history` | The command history file. |
| `Unsafe.UseMachineRam` | `true`/`false` | `false` | Count servers' `Ram` against the machine's memory instead of the heap. |
| `Unsafe.HookTimeout` | seconds, 1-3600 | `30` | How long a stopping server's shutdown hooks get. |
| `Unsafe.ExitGrace` | seconds, 0-3600 | `5` | How long a stopped server's leftover threads get before they're interrupted. |
| `Unsafe.LeakTimeout` | seconds, 0-3600 | `10` | ...and then before they're reported as leaked. |

Any `Unsafe` setting that differs from its default is logged as a warning at start. `Host` changes apply when
Paperhive starts.

## Defaults

Optional. Any of `IP`, `Args`, `Autostart`, `Restart`, `DisabledHivePlugins`, `SharedPlugins`, `EULA`, `Ram`: every
server that doesn't set the key gets this value (Paper-only keys are skipped for Velocity servers).

## Servers

Each item:

| Key | Type | Default | |
|---|---|---|---|
| `Name` | text | — | **Required.** Letters, digits, `-`, `_`; up to 32. Unique, ignoring case. |
| `Port` | 1-65535 | — | **Required.** Unique. |
| `Software` | `papermc`/`velocitymc` | the top-level `Software` | What this server runs. |
| `Version` | text | the top-level `Version` if the software matches, else that software's newest | The version it runs (`4.3.1-45` for Velocity). |
| `IP` | text | all addresses | The address to listen on (Paper's `--host`). |
| `Root` | path | `<Host.Instances>/<Name>` | The server's folder. Unique. |
| `Plugins` | path | `<Root>/plugins` | Its Paper plugins folder. |
| `Args` | list of text | `[]` | Extra arguments for Paper. |
| `Autostart` | `true`/`false` | `true` | Start it when Paperhive starts. |
| `Restart` | `never`/`on-crash` | `never` | Start it again after a crash. |
| `DisabledHivePlugins` | list of text | `[]` | Hive plugins not to load on this server. |
| `SharedPlugins` | list of paths | `[]` | Extra folders of Paper plugin jars ([Shared plugins](../guide/shared-plugins.md)). |
| `Ram` | `512M`, `2G`, or MB | none | Its share of the heap: it won't start unless that much is free next to the running servers' `Ram`. |

A `velocitymc` server can't set `IP`, `Plugins`, `SharedPlugins` or `DisabledHivePlugins` (they're Paper's); that's a
validation error. Velocity 4.3 needs Java 25 on the host.

Paths are relative to the hive folder unless absolute. Server changes apply at that server's next start.

## Validation

Paperhive refuses to start with an invalid file, and reports every problem at once:

```text
paperhive.yml has 3 problem(s):
  - Servers[0] (lobby).port: unknown key, did you mean Port?
  - Servers[1] (survival).Restart: expected never or on-crash, got 'always'
  - Servers: lobby and survival both use Port 25565
```

## Upgrades

When a newer Paperhive changes the format, it migrates the file on start and keeps the original as
`paperhive.yml.v<N>.bak`. A file from a *newer* Paperhive than the one running is refused rather than misread.
