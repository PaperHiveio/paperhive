---
title: "Messaging"
description: "Send messages between the host half and every server with publish and subscribe."
---

# Messaging

Messages are how the parts of a Hive plugin talk to each other: the host half and the server half in every server.
Anyone can **publish** a string on a **channel**, and every **subscriber** of that channel receives it, on the host
and in every server.

```java
// anywhere: the host half, a server half, even a normal Paper plugin
Paperhive.getHive().publish("myplugin:announce", "Restarting in 5 minutes");

// anywhere else
Registration subscription = Paperhive.getHive().subscribe("myplugin:announce",
        (from, channel, message) -> System.out.println(message));
```

## The listener

```java
@FunctionalInterface
public interface HiveMessageListener {
    void onMessage(@Nullable HiveServer from, @NotNull String channel, @NotNull String message);
}
```

- `from` is the server that published it, or `null` when the host published it.
- Messages are delivered **in order**, on a **Paperhive thread**. In a server half, hop onto the main thread with
  `runTask` before using Bukkit.
- `subscribe` returns a `Registration`; `unregister()` stops it.

## Channels

Channels are plain strings. Prefix yours with your plugin's name (`myplugin:announce`) so they don't collide with
other plugins'.

## Sending data

Messages are strings, so they cross every class loader safely. For structured data, encode it, for example as JSON
with the Gson that ships with Paper (server half) or a format of your own:

```java
getHive().publish("myplugin:join", player.getName() + "|" + getHiveServer().getName());

getHive().subscribe("myplugin:join", (from, channel, message) -> {
    String[] parts = message.split("\\|", 2);
    // parts[0] = player, parts[1] = server
});
```

## A complete example: network-wide announcements

**Host half**: a Hive command that announces on every server.

```java
final class AnnounceCommand extends HiveCommand {
    AnnounceCommand() {
        super("announce", "<message>", "broadcast a message on every server");
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.isEmpty()) {
            sendUsage(sender);
            return;
        }
        Paperhive.getHive().publish("myplugin:announce", String.join(" ", args));
        sender.sendMessage("Announced.");
    }
}
```

**Server half**: show it in game.

```java
@Override
public void onEnable() {
    getHive().subscribe("myplugin:announce", (from, channel, message) ->
            runTask(() -> getServer().broadcast(Component.text("[Network] " + message))));
}
```

Type `announce The event starts now!` at the `hive>` prompt, and every server's players see it.

## Cleanup

Subscriptions made inside a server are removed when that server stops, and a Hive plugin's when it's disabled.
Subscribe again in `onEnable`.
