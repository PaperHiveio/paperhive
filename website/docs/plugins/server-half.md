---
title: "The server half"
description: "HiveBukkitPlugin: the part of a Hive plugin that runs inside each server with Bukkit and Paper."
---

# The server half

The server half is a class extending `HiveBukkitPlugin`, named by `server-main` in your plugin's `paperhive.yml`.
Every server your plugin is enabled for loads its own instance of it, with the full Bukkit, Paper, Adventure and
Brigadier APIs, plus the Hive API.

It works like a Bukkit `JavaPlugin`, but **it isn't one**: there's no `plugin.yml`, and Paperhive loads it for you.

```java
package com.example.myplugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.abdullahcxd.paperhive.bridge.HiveBukkitPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class MyServerPlugin extends HiveBukkitPlugin implements Listener {

    @Override
    public void onEnable() {
        registerListener(this);
        registerCommand("servers", "Lists the hive's servers", List.of("hs"), new ServersCommand());
        getLogger().info("Enabled in {}", getHiveServer().getName());
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        // tell the host half (and every other server) about it
        getHive().publish("joins", event.getPlayer().getName() + " joined " + getHiveServer().getName());
    }

    private final class ServersCommand implements BasicCommand {
        @Override
        public void execute(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
            getHive().getServers().forEach(server -> source.getSender().sendMessage(
                    Component.text(server.getName() + ": " + server.getState())));
        }
    }
}
```

## Lifecycle

| Method | When |
|---|---|
| `onEnable()` | The server enables its plugins (after its worlds load). Register listeners and commands here. |
| `onDisable()` | The server stops. Listeners, commands and Hive subscriptions are removed for you afterwards. |

If `onEnable` throws, the error is logged, what the half registered is taken back, and the server and other plugins
carry on.

## API

### The server and the hive

| Method | |
|---|---|
| `getServer()` | Bukkit's `Server` of *this* server. |
| `getHiveServer()` | This server as a `HiveServer` (name, port, state...). |
| `getHive()` | The [Hive API](../reference/hive-api.md): every server, events, messaging. |
| `getName()`, `getVersion()` | Your plugin's name and version, from `paperhive.yml`. |
| `getLogger()` | An Adventure `ComponentLogger` printing `[<plugin name>] ...` in this server's console. |
| `getDataFolder()` | `plugins/<plugin name>/` of this server, like a Bukkit plugin's. Not created until you do. |
| `getBukkitPlugin()` | A Bukkit `Plugin` for APIs that insist on one (it's Paperhive's bridge, shared by all server halves in this server). |

### Listeners

| Method | |
|---|---|
| `registerListener(Listener)` | Registers Bukkit `@EventHandler` methods. |
| `unregisterListener(Listener)` | Removes them again. |

### Commands

Commands are Paper's Brigadier commands. Register them in `onEnable`.

| Method | |
|---|---|
| `registerCommand(label, BasicCommand)` | A simple `/label args...` command. |
| `registerCommand(label, description, aliases, BasicCommand)` | The same, with a description and aliases. |
| `registerCommand(LiteralCommandNode, description, aliases)` | A full Brigadier tree, built with `Commands.literal(...)`. |

Every command is also reachable as `/paperhive:<label>`, in case another plugin takes the plain label.

```java
registerCommand(Commands.literal("hub")
        .executes(ctx -> {
            ctx.getSource().getSender().sendMessage("Sending you to the lobby...");
            return Command.SINGLE_SUCCESS;
        })
        .build(), "Go to the lobby", List.of("lobby"));
```

### Scheduling

| Method | |
|---|---|
| `runTask(Runnable)` | On this server's main thread, next tick. |
| `runTaskLater(Runnable, delayTicks)` | On the main thread after a delay. |
| `runTaskTimer(Runnable, delayTicks, periodTicks)` | Repeatedly on the main thread. |
| `runTaskAsync(Runnable)` | Off the main thread. |

Each returns a Bukkit `BukkitTask` you can cancel.

## Threads: hop onto the main thread

Hive events and messages arrive on a **Paperhive thread**, not on the server's main thread. Bukkit mostly wants its
main thread, so hop over before touching the world or players:

```java
getHive().subscribe("announce", (from, channel, message) ->
        runTask(() -> getServer().broadcast(Component.text(message))));
```

## What a server half doesn't have

Because it isn't a `JavaPlugin`, some Bukkit-plugin features aren't there:

- **No `getConfig()`**: read and write your own files in `getDataFolder()` (e.g. with Bukkit's `YamlConfiguration`).
- **No `plugin.yml` permissions or dependencies**: register permissions with Bukkit's `PluginManager` if you need to.
- **Not listed in `/plugins`**: only `Paperhive` (the bridge) appears there.

## Class loading

Each server half gets its own class loader that looks in **your jar first**, so libraries you shade stay yours and
don't clash with other plugins. Java, Bukkit/Paper, Adventure, Brigadier, logging and Paperhive itself come from the
server.
