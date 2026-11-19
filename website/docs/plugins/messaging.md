---
title: "Messaging"
description: "Typed channels between the host and every server: publish to all, or send to one server."
---

# Messaging

Messages are how the parts of a Hive plugin talk: the host half and the server half in every server. A **channel**
is named by a [namespaced key](namespaced.md) and carries one type of value.

```java
HiveChannel<UUID> joins = channel("joins", MessageCodec.UUID_CODEC);   // server half; host half: hive.channel(key("joins"), ...)

joins.subscribe(message -> getLogger().info("{} joined {}", message.payload(), message.from()));
joins.publish(player.getUniqueId());                    // every subscriber: the host's and every server's
joins.send(getHive().getServer("lobby"), uuid);         // only the lobby's subscribers
joins.send(null, uuid);                                 // only the host's (Hive plugins' host halves)
```

## Channels

Get a channel with `hive.channel(key, codec)` (in a server half, `channel("name", codec)` uses your plugin's
namespace). It's a small value object: make it once, in `onEnable`, and keep it in a field.

| Method | |
|---|---|
| `publish(value)` | To every subscriber, on the host and in every server. |
| `send(server, value)` | To the subscribers in one server; `null` sends to the host's. |
| `subscribe(message -> ...)` | Receive every message; returns a `Registration`. |

A received `HiveMessage<T>` has:

| Method | |
|---|---|
| `payload()` | The value, decoded. |
| `from()` | The server that sent it, or `null` when the host did (`fromHost()`). |
| `channel()` | The channel's key. |

## Codecs: any type, safely

Only text travels between the host and servers, because each server has its own copy of your classes. A
`MessageCodec<T>` turns your type into text and back on each end.

| Built in | Type |
|---|---|
| `MessageCodec.STRING` | `String` |
| `MessageCodec.INTEGER`, `LONG`, `DOUBLE`, `BOOLEAN` | numbers and booleans |
| `MessageCodec.UUID_CODEC` | `UUID` (players) |
| `MessageCodec.enumOf(GameMode.class)` | any enum |

For your own types, give two functions, for example JSON with the Gson that Paper ships (server half):

```java
record Invite(UUID from, UUID to, String party) {}

Gson gson = new Gson();
MessageCodec<Invite> invites = MessageCodec.of(gson::toJson, json -> gson.fromJson(json, Invite.class));
HiveChannel<Invite> channel = channel("invites", invites);
```

`codec.map(...)` adapts an existing codec: `MessageCodec.STRING.map(PartyName::new, PartyName::value)`.

A message that doesn't decode (another plugin version sending something else) is logged and skipped; the next one
still arrives.

## Threads

Listeners run on a **Paperhive thread**, in the order messages were sent. In a server half, Bukkit calls need the
server's main thread, so use the helpers:

```java
onMessage(joins, message -> {
    // runs on this server's main thread: Bukkit is safe here
    Player player = getServer().getPlayer(message.payload());
});
```

## Messages as Bukkit events

`relay(key)` turns every message on a channel into a Bukkit `HiveMessageEvent` in that server, so any
`@EventHandler` can react, even in normal Paper plugins:

```java
// server half onEnable
relay(key("announce"));

@EventHandler
public void onHiveMessage(HiveMessageEvent event) {
    if (event.is(key("announce"))) getServer().broadcast(Component.text(event.getMessage()));
}
```

`event.decode(codec)` decodes the text with any codec.

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
        Paperhive.getHive().publish(key("announce"), String.join(" ", args));
        sender.sendMessage("Announced.");
    }
}
```

**Server half**: relay it, and show it in game.

```java
@Override
public void onEnable() {
    relay(key("announce"));
    registerListener(this);
}

@EventHandler
public void onAnnounce(HiveMessageEvent event) {
    if (event.is(key("announce"))) getServer().broadcast(Component.text("[Network] " + event.getMessage()));
}
```

Type `announce The event starts now!` at the `hive>` prompt, and every server's players see it.

## Plain strings

The underlying API still works with raw strings: `hive.publish("myplugin:announce", text)`,
`hive.subscribe(channel, (from, channel, message) -> ...)` and `hive.send(server, channel, text)`. Channels are just a
typed layer over it.

## Cleanup

Subscriptions made inside a server are removed when that server stops, and a Hive plugin's when it's disabled.
Subscribe again in `onEnable`.

## Next

Need an answer back? Use [request and reply](requests.md). Need state every server can read? Use
[shared data](shared-data.md).
