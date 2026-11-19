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

## Hive utilities

Shortcuts for talking to the rest of the hive, built for server halves: everything that needs Bukkit runs on the main
thread, and everything is cleaned up when the server stops.

| Method | |
|---|---|
| `key("name")` | A [namespaced key](namespaced.md) in your plugin's namespace. |
| `channel("name", codec)` | A typed [channel](messaging.md) in your namespace. |
| `service("name", requestCodec, replyCodec)` | A typed [request and reply](requests.md) service in your namespace. |
| `onMessage(channel, message -> ...)` | Every message on a channel, handled on the **main thread**. |
| `handle(service, request -> reply)` | Answer a service's requests to this server on the **main thread**. |
| `relay(key)` | Turn a channel's messages into Bukkit `HiveMessageEvent`s in this server. |
| `mainThread()` | An `Executor` for this server's main thread: `future.thenAcceptAsync(..., mainThread())`. |
| `getHive().getData()` | [Shared data](shared-data.md) for the whole hive. |

```java
@Override
public void onEnable() {
    HiveChannel<UUID> joins = channel("joins", MessageCodec.UUID_CODEC);
    onMessage(joins, m -> getServer().broadcast(Component.text(m.payload() + " joined " + m.from().getName())));

    HiveService<String, Integer> players = service("players", MessageCodec.STRING, MessageCodec.INTEGER);
    handle(players, request -> getServer().getOnlinePlayers().size());

    registerListener(this);
}
```

## Bukkit events for the hive

The bridge fires these in every server, on the main thread, so plain `@EventHandler` listeners work. Normal Paper
plugins can listen too.

| Event | When |
|---|---|
| `HiveServerStateEvent` | Any server of the hive changes state. `getServer()`, `getFrom()`, `getTo()`, `isThisServer()`. |
| `HiveMessageEvent` | A message arrives on a channel this server [relays](messaging.md#messages-as-bukkit-events). `getChannel()`, `getFrom()`, `getMessage()`, `decode(codec)`, `is(key)`. |
| `PlayerJoinHiveEvent` | A player joined this server. `getPreviousServer()` is the hive server the proxy moved them from, or null; `isNewToHive()`. Bukkit's join event can't tell the two apart. |
| `PlayerLeaveHiveEvent` | A player left the whole hive, not just this server: fired where they quit, 10 seconds later, unless they joined another hive server meanwhile. `getPlayer()` is an `OfflinePlayer`. |
| `PlayerMoveBlockEvent` | A player moved into another block or world (not every head turn, like `PlayerMoveEvent`). Cancellable; `getFrom()`, `getTo()`, `isNewChunk()`. |
| `PlayerDamageByPlayerEvent` | A player hurt another, by hand or with a projectile they shot. `getPlayer()` is the victim; `getAttacker()`, `getProjectile()`, `get/setDamage()`, `isLethal()`. Cancellable. |
| `PlayerIdleEvent` | A player went idle after 5 minutes without moving, looking, chatting, commands or clicks (`isIdle()` true), or came back (false). |

```java
@EventHandler
public void onHiveServer(HiveServerStateEvent event) {
    if (event.getTo() == HiveServerState.RUNNING && !event.isThisServer()) {
        getServer().broadcast(Component.text(event.getServer().getName() + " is up"));
    }
}
```

They're in `org.abdullahcxd.paperhive.bridge.event`. The move and damage events cost nothing while no one listens.

```java
on(PlayerJoinHiveEvent.class, e -> {
    if (e.isNewToHive()) getServer().broadcast(Text.mini("<yellow><name> joined the network",
            Placeholder.unparsed("name", e.getPlayer().getName())));
});
on(PlayerIdleEvent.class, e -> e.getPlayer().playerListName(e.isIdle() ? Text.mini("<gray>" + e.getPlayer().getName() + " (AFK)") : null));
```

## Utilities

Shortcuts for what most plugins write again and again. The first group is on `HiveBukkitPlugin`:

| Method | What it does |
|---|---|
| `on(type, handler)` | Listens to a Bukkit event with a lambda, no `Listener` class. Returns a `Registration`; removed on disable anyway. An overload takes the `EventPriority` and `ignoreCancelled`. |
| `runLater(duration, task)`, `runTimer(delay, period, task)` | Scheduling with `Duration` instead of ticks. |
| `send(audience, text, placeholders...)` | Sends a MiniMessage text. |
| `bukkitKey(value)` | A Bukkit `NamespacedKey` in your plugin's namespace, for persistent data and recipes. |
| `getConfig()`, `saveDefaultConfig()`, `saveConfig()`, `reloadConfig()` | `config.yml` in `getDataFolder()`, with your jar's `config.yml` as defaults, like a Bukkit plugin's. |

And in `org.abdullahcxd.paperhive.bridge.util`:

| Class | What it does |
|---|---|
| `Text` | `Text.mini(text, placeholders...)` parses MiniMessage; `Text.item(...)` does the same without the italics Minecraft adds to item names; `Text.strip(text)` removes the tags. |
| `ItemBuilder` | Builds an `ItemStack` in one expression: `name`, `lore` (MiniMessage), `amount`, `enchant`, `glow`, `unbreakable`, `flags`, `tag` (persistent data), `meta` for anything else. |
| `Cooldowns<K>` | Per-key cooldowns, safe from any thread: `tryUse(key)`, `remaining(key)`, `reset(key)`. |

```java
private final Cooldowns<UUID> heal = new Cooldowns<>(Duration.ofSeconds(30));
private ItemStack wand;

@Override
public void onEnable() {
    saveDefaultConfig();
    wand = ItemBuilder.of(Material.BLAZE_ROD)
            .name(getConfig().getString("wand-name", "<gold>Hive Wand"))
            .glow()
            .tag(bukkitKey("wand"), PersistentDataType.BOOLEAN, true)
            .build();

    on(PlayerInteractEvent.class, e -> {
        ItemStack item = e.getItem();
        if (item == null || !item.getPersistentDataContainer().has(bukkitKey("wand"))) return;
        Player player = e.getPlayer();
        if (!heal.tryUse(player.getUniqueId())) {
            send(player, "<red>Wait <s>s", Placeholder.unparsed("s", String.valueOf(heal.remaining(player.getUniqueId()).toSeconds() + 1)));
            return;
        }
        player.heal(20);
        send(player, "<green>Healed!");
    });
}
```

## What the bridge logs

The bridge reports what passes between it and the host in each server's console, coloured:

```text
[Paperhive] Hive | Connected to the host as lobby (port 25565, 3 server(s) in the hive)
[Paperhive] Hive | The host sent 2 server half(s) to load
[Paperhive] Hive | > Loading ExamplePlugin v1.0.0 from example.jar
[Paperhive] Hive | + ExamplePlugin enabled (12.4 ms)
[Paperhive] Hive | Enabled 2 of 2 server half(s) in 31.0 ms
[Paperhive] Hive | survival is now RUNNING
```

## What a server half doesn't have

Because it isn't a `JavaPlugin`, some Bukkit-plugin features aren't there:

- **No `plugin.yml` permissions or dependencies**: register permissions with Bukkit's `PluginManager` if you need to.
- **Not listed in `/plugins`**: only `Paperhive` (the bridge) appears there.

## Class loading

Each server half gets its own class loader that looks in **your jar first**, so libraries you shade stay yours and
don't clash with other plugins. Java, Bukkit/Paper, Adventure, Brigadier, logging and Paperhive itself come from the
server.
