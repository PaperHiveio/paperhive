---
title: "Hive API"
description: "Reference for Paperhive, Hive, HiveServer, HiveServerState, HiveServerStats and Registration."
---

# Hive API

Package `org.abdullahcxd.paperhive.api`, in `paperhive-api` (included in `paperhive-sdk`). The same API works from the
host half, from server halves, and from ordinary Paper plugins running in a hive. Everything here is thread-safe.

## Paperhive

The entry point.

```java
Hive hive = Paperhive.getHive();
```

| Method | |
|---|---|
| `static Hive getHive()` | The hive. Throws `IllegalStateException` when not running under Paperhive (e.g. your plugin on a plain Paper server). |

## Hive

Every server of this Paperhive host.

| Method | |
|---|---|
| `List<HiveServer> getServers()` | Every server, in `paperhive.yml` order. |
| `HiveServer getServer(String name)` | One server, or `null`. |
| `HiveServer getCurrentServer()` | The server the calling thread belongs to; `null` on the host's own threads. |
| `HiveServer createServer(String name, int port)` | Adds a server to `paperhive.yml`, not started. Throws `IllegalArgumentException` listing every reason it would make the file invalid (name taken, port used, ...). |
| `EventBus getEventBus()` | [Hive events](../plugins/events.md). |
| `void publish(String channel, String message)` | [Sends a message](../plugins/messaging.md) to every subscriber of `channel`, on the host and in every server, in order. |
| `Registration subscribe(String channel, HiveMessageListener listener)` | Receives messages on `channel`. |

Listeners and subscriptions made by code inside a server are removed when that server stops, and a Hive plugin's when
it's disabled.

## HiveServer

One server. A `HiveServer` stays valid while the server exists, across restarts, so you can keep a reference.

| Method | |
|---|---|
| `String getName()` | Its name. |
| `int getPort()` | Its port. |
| `HiveServerState getState()` | Its state now. |
| `Path getRoot()` | Its folder. |
| `boolean start()` | Starts it. `false` if it's already running, a `ServerStartEvent` was cancelled, or it couldn't start (the host logs why). |
| `void stop()` | Asks it to stop and returns at once; a `ServerStoppedEvent` follows. |
| `void restart()` | Stops it if it runs, then starts it; returns at once. |
| `HiveServerStats getStats()` | TPS, tick time and players right now; `null` if it isn't running or not far enough yet. |
| `boolean dispatchCommand(String command)` | Runs a console command in it, as if typed there. `false` if it isn't running. |
| `void runOnServer(Runnable task)` | Runs `task` on its main thread. Throws `IllegalStateException` if it isn't running yet. |

## HiveServerState

```text
CREATED → STARTING → RUNNING → STOPPING → STOPPED
                                       ↘ CRASHED
STOPPED / CRASHED → STARTING  (start again)
```

| State | |
|---|---|
| `CREATED` | Known, never started. |
| `STARTING` | Booting. |
| `RUNNING` | Booted (after its first tick). |
| `STOPPING` | Saving and shutting down. |
| `STOPPED` | Stopped normally. |
| `CRASHED` | Ended with a non-zero exit status, or its main method threw. |

## HiveServerStats

A record, read when asked.

| Component | |
|---|---|
| `double tps1m`, `tps5m`, `tps15m` | Ticks per second over 1, 5 and 15 minutes. 20 is full speed (Paper may report a hair above 20). |
| `double msptAverage` | Average milliseconds per tick. 50 is the budget at 20 TPS. |
| `int players`, `int maxPlayers` | Online and maximum players. |

## Registration

Returned by `subscribe` and `EventBus.register`.

| Method | |
|---|---|
| `void unregister()` | Removes the listener or subscription. Calling it again does nothing. |

## Events

See [Events](../plugins/events.md) for `EventBus`, `@Subscribe`, `Priority`, `HiveEvent`, `CancellableEvent` and the
built-in server events.
