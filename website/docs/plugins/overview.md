---
title: "How Hive plugins work"
description: "The host half and the server half: what runs where, and how the two talk."
---

# How Hive plugins work

A Hive plugin is **one jar that works across the whole hive**. It's different from a Paper plugin, which lives
inside one server and only knows about that server.

## Two halves in one jar

```text
Paperhive host                        server "lobby"                 server "survival"
 └─ MyHivePlugin (host half)           └─ MyServerPlugin (server half) └─ MyServerPlugin (server half)
     loaded once                           loaded in each server           loaded in each server
     sees every server                     full Bukkit/Paper API           full Bukkit/Paper API
     Hive commands, events                 listeners, commands, tasks      listeners, commands, tasks
```

|           | Host half                                                        | Server half                                             |
|-----------|------------------------------------------------------------------|---------------------------------------------------------|
| Class     | extends `PaperhivePlugin`                                        | extends `HiveBukkitPlugin`                              |
| Named by  | `main`                                                           | `server-main` (optional)                                |
| Loaded    | once, by Paperhive                                               | in every server the plugin is enabled for               |
| Can use   | the [Hive API](../reference/hive-api.md), Hive commands, Hive events | Bukkit, Paper, Adventure, Brigadier, *and* the Hive API |
| Can't use | Bukkit (there's no single server to talk to)                     | —                                                       |

A plugin can be **host-only** (no `server-main`): for example, one that restarts servers on a schedule or posts
crashes to Discord.

## Why two halves?

Each server has its own copy of Bukkit and Paper (that's how Paperhive keeps them apart). So there's no single
`org.bukkit.Server` class that code on the host could use: there's one per server. The host half works with the hive
as a whole; the server half runs inside each server, where Bukkit is.

## The two halves are separate classes

The halves are loaded by different class loaders, so **a class from your jar is a different class in each half and
in each server**. A static field set in the host half isn't visible in the server half. They talk through the Hive
API instead:

- [**Events**](events.md): `ServerStartedEvent`, `ServerCrashEvent`, ... and your own events.
- [**Messages**](messaging.md): `publish` a string on a channel; every subscriber, on the host or in any
  server, receives it.
- **`HiveServer`**: from the host, start/stop servers, run console commands, or run code on a server's main thread
  with `runOnServer`.

## Lifecycle

```text
Paperhive starts
 └─ host half: onEnable()
     server "lobby" starts
      ├─ Paper enables Paperhive's bridge
      ├─ server half: onEnable()           register listeners and commands here
      └─ lobby is RUNNING
          ├─ ServerStartedEvent
          └─ host half: onServerStart()
     server "lobby" stops
      ├─ server half: onDisable()
      └─ its listeners, commands and Hive subscriptions are removed
Paperhive stops (or `plugin disable`)
 └─ host half: onDisable()                 its Hive commands are removed
```

Anything your code registers with Paperhive (event listeners, message subscriptions, commands) is **cleaned up for
you** when the server stops or the plugin is disabled, so a stopped server's classes can be freed.

## Where plugins go

Hive plugin jars go in the host's `plugins/` folder (next to `paperhive.yml`, or `Host.Plugins`), **not** in a
server's `plugins/` folder. The server half is loaded by Paperhive itself, so it doesn't need a `plugin.yml`.

## Turning a plugin off

- For one server: `DisabledHivePlugins: [MyPlugin]` in that server's entry (no server half there, no
  `onServerStart` for it).
- Everywhere, at runtime: `plugin disable MyPlugin` (and `plugin enable MyPlugin`).

## Next

[Set up a plugin project with the Gradle plugin →](gradle-plugin.md)
