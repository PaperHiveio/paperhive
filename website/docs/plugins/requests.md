---
title: "Request and reply"
description: "Ask another server (or the host) a question and get its answer as a CompletableFuture."
---

# Request and reply

A **service** lets one part of the hive ask another a question and get the answer back: "is this player online on
the lobby?", "how many players does survival have?", "reserve a slot in the event server". You get a
`CompletableFuture`, with timeouts and errors handled for you.

```java
HiveService<UUID, Boolean> isOnline = service("is-online", MessageCodec.UUID_CODEC, MessageCodec.BOOLEAN);

// in every server: answer for this server (on its main thread)
handle(isOnline, request -> getServer().getPlayer(request.payload()) != null);

// anywhere: ask the lobby
isOnline.request(getHive().getServer("lobby"), uuid)
        .thenAcceptAsync(online -> player.sendMessage("In the lobby: " + online), mainThread());
```

## Getting a service

| Where | How |
|---|---|
| Server half | `service("name", requestCodec, replyCodec)`: in your plugin's namespace |
| Host half | `Paperhive.getHive().service(key("name"), requestCodec, replyCodec)` |

The request and the reply each have their own [codec](messaging.md#codecs-any-type-safely), so a question about a
`UUID` can be answered with a `Boolean`, an `Integer`, or your own JSON type.

## Answering

| Method | |
|---|---|
| `handle(service, request -> reply)` | Server half helper: answers on the server's **main thread**, so Bukkit calls are safe. |
| `service.handle(request -> reply)` | Answers right away, on a Paperhive thread (host half, or no Bukkit needed). |
| `service.handleAsync(request -> future)` | Answers later: return a `CompletionStage` and complete it when ready. |

Each server answers with its own handler, and so does the host. If a server registers a second handler for the same
service, the newest one answers until it's removed. Handlers made inside a server are removed when it stops.

## Asking

```java
CompletableFuture<Integer> count = players.request(getHive().getServer("survival"), "");
CompletableFuture<Integer> fromHost = players.request(null, "");                 // ask the host
CompletableFuture<Integer> patient = players.request(server, "", Duration.ofSeconds(30));
```

| Outcome | The future... |
|---|---|
| The target answers | completes with the reply |
| Nothing handles the service there (or the server is stopped) | fails with `NoResponderException` |
| No answer within the timeout (5 seconds by default) | fails with `TimeoutException` |
| The handler throws | fails with that exception |

The future completes on a **Paperhive thread**. In a server half, continue on the main thread with the `*Async`
methods and `mainThread()`:

```java
players.request(target, "")
        .thenAcceptAsync(n -> sender.sendMessage(target.getName() + ": " + n + " players"), mainThread())
        .exceptionallyAsync(e -> {
            sender.sendMessage(target.getName() + " didn't answer");
            return null;
        }, mainThread());
```

::: callout warning "Don't block"
Never call `join()` or `get()` on a request from a Paperhive thread (inside a listener or a handler): the answer is
delivered on that same thread, so it would wait forever. Chain with `thenAccept...` instead.
:::

## Ask every server

Combine requests with `CompletableFuture.allOf`:

```java
List<CompletableFuture<Integer>> counts = getHive().getServers().stream()
        .filter(s -> s.getState() == HiveServerState.RUNNING)
        .map(s -> players.request(s, "").exceptionally(e -> 0))
        .toList();
CompletableFuture.allOf(counts.toArray(CompletableFuture[]::new))
        .thenApply(v -> counts.stream().mapToInt(CompletableFuture::join).sum())
        .thenAcceptAsync(total -> getServer().broadcast(Component.text(total + " players in the network")), mainThread());
```

## Plain strings

Underneath: `hive.request(server, "ns:name", text, timeout)` and `hive.respond("ns:name", (from, channel, text) -> future)`.
