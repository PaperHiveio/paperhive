---
title: "Shared data"
description: "A hive-wide key-value store held by the host: counters, flags, claims, and change notifications."
---

# Shared data

`Hive.getData()` is a key-value store **shared by the whole hive**. The host holds it, and the host and every
server read and write the same values. Use it for things every server needs to agree on: an event flag, a network
MOTD, counters, who claimed a name.

```java
HiveData data = getHive().getData();

data.set(key("motd"), "Summer event!");
String motd = data.get(key("motd"));

long joins = data.increment(key("joins-today"), 1);
boolean mine = data.compareAndSet(key("arena/castle"), null, getHiveServer().getName());  // claim it

data.watch(key("motd"), (key, before, after) -> refreshScoreboards(after));
```

## Keys and values

Keys are [namespaced](namespaced.md); use `key("...")` for your plugin's. Values are text; read and write other types
with a [codec](messaging.md#codecs-any-type-safely):

```java
data.set(key("party-limit"), MessageCodec.INTEGER, 8);
Integer limit = data.get(key("party-limit"), MessageCodec.INTEGER);   // null if not set
```

## Methods

| Method | |
|---|---|
| `get(key)` / `get(key, codec)` | The value, or `null` if it isn't set. |
| `set(key, value)` / `set(key, codec, value)` | Set it. |
| `remove(key)` | Unset it; `true` if it was set. |
| `compareAndSet(key, expected, update)` | Set `update` only if the value is `expected` now (`null` = not set; a `null` update removes it). Returns whether it did. |
| `increment(key, delta)` | Add to a number (0 if not set) and return the new value. |
| `entries(namespace)` | Every key of a namespace with its value, right now. |
| `watch(key, listener)` | Hear every change: `(key, before, after)`, with `null` for "not set". |

Every method is thread-safe and atomic for its key, so `increment` and `compareAndSet` never lose an update even when
several servers write at once.

## Watching

Watchers run on a Paperhive thread, in order. In a server half, hop to the main thread for Bukkit:

```java
data.watch(key("event-running"), (key, before, after) ->
        runTask(() -> bossBar.setVisible("true".equals(after))));
```

Watchers made inside a server are removed when it stops. The data itself stays: it outlives server restarts.

## Patterns

**A one-time claim**: only one server wins.

```java
if (data.compareAndSet(key("event-host"), null, getHiveServer().getName())) startEvent();
```

**A network counter**:

```java
long online = data.increment(key("online"), +1);   // on join
data.increment(key("online"), -1);                  // on quit
```

**A setting every server follows**: set it from the host's console (a Hive command) and `watch` it on each server.

::: callout info "In memory"
Shared data lives as long as the Paperhive host runs. It starts empty when the host starts. For data that must survive
a restart, save it yourself (a file or a database), and use shared data as the live copy.
:::
