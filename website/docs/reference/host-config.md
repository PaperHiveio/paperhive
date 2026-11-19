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
| `Software` | text | `papermc` | The server software every server runs. |
| `Version` | text | `1.21.11` | The Minecraft version every server runs. Only supported versions are accepted. |
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

`Host` changes apply when Paperhive starts.

## Servers

Each item:

| Key | Type | Default | |
|---|---|---|---|
| `Name` | text | — | **Required.** Letters, digits, `-`, `_`; up to 32. Unique, ignoring case. |
| `Port` | 1-65535 | — | **Required.** Unique. |
| `IP` | text | all addresses | The address to listen on (Paper's `--host`). |
| `Root` | path | `instances/<Name>` | The server's folder. Unique. |
| `Plugins` | path | `<Root>/plugins` | Its Paper plugins folder. |
| `Args` | list of text | `[]` | Extra arguments for Paper. |
| `Autostart` | `true`/`false` | `true` | Start it when Paperhive starts. |
| `Restart` | `never`/`on-crash` | `never` | Start it again after a crash. |
| `DisabledHivePlugins` | list of text | `[]` | Hive plugins not to load on this server. |
| `SharedPlugins` | list of paths | `[]` | Extra folders of Paper plugin jars ([Shared plugins](../guide/shared-plugins.md)). |

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
