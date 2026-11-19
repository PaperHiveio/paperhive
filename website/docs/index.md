---
title: "Paperhive"
description: "Run many Paper Minecraft servers in one JVM, and write plugins that span all of them."
---

# Paperhive

**One JVM, many Paper servers.** Paperhive runs several [Paper](https://papermc.io) Minecraft servers inside a single
Java process. Each server gets its own class loader, folder, console, logs and plugins, so it behaves like a normal
Paper server, but they all share one JIT-warmed runtime and one heap instead of one process each.

On top of that, **Hive plugins** span the whole network: one jar with a *host half* that sees every server and a
*server half* that runs inside each server with the full Bukkit and Paper API.

::: callout warning "Early development"
Paperhive is pre-1.0. APIs and the config format can still change between versions; the config is migrated for you
when it does. Each Paperhive release supports one pinned Paper version.
:::

## Why Paperhive?

::: card icon:server "Many servers, one process"
Start, stop, restart, create and delete servers at runtime from one console, or from scripts with
`java -jar paperhive.jar hive <command>`. A lobby, a few game servers and an event server, all in one place.
:::

::: card icon:shield "Isolated where it matters"
Every server has its own working directory, standard streams, logging, threads and `System.exit`. A crashing server
doesn't take the others down, and `Restart: on-crash` brings it back.
:::

::: card icon:puzzle "Plugins for the whole network"
Hive plugins react to every server starting, stopping or crashing, send messages between servers, add host commands,
and still use Bukkit inside each server.
:::

::: card icon:package "Batteries included"
A Velocity plugin that registers your servers with the proxy automatically, a Gradle plugin that sets up plugin
projects in one line, and a single validated config file.
:::

## Where to start

| You want to... | Read |
|---|---|
| Run a network of servers | [Installation](getting-started/installation.md), then [Your first hive](getting-started/first-hive.md) |
| Configure servers and the host | [Configuration](guide/configuration.md) |
| Put a Velocity proxy in front | [Velocity](guide/velocity.md) |
| Write a plugin for the whole hive | [How Hive plugins work](plugins/overview.md), then [Gradle plugin](plugins/gradle-plugin.md) |
| Look up an API | [Hive API reference](reference/hive-api.md) |

## How it works, in one picture

```text
one JVM
 ├─ Paperhive host ─────────── console, paperhive.yml, Hive plugins (host halves)
 │
 ├─ server "lobby"     own class loader · own folder · own logs · Paper plugins + server halves
 ├─ server "survival"  own class loader · own folder · own logs · Paper plugins + server halves
 └─ server "events"    own class loader · own folder · own logs · Paper plugins + server halves
```

Paperhive downloads a stock Paper jar, patches it once, and loads a fresh copy of Paper's classes for every server.
Nothing in Paper is forked: the few things that are JVM-wide (standard output, `System.exit`, the working directory,
...) are redirected per server while the classes load.

::: callout tip "Not a security boundary"
Servers in one hive share a JVM. Run servers you trust together; don't use Paperhive to host strangers' servers side
by side.
:::
