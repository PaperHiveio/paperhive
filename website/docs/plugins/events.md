---
title: "Events"
description: "Listen to server starts, stops and crashes, cancel starts, and post your own events across the hive."
---

# Events

The Hive event bus delivers events about the whole hive, to the host half and to code inside any server. It works
like Guava's EventBus, with Bukkit's ideas: **any object with `@Subscribe` methods is a listener**, no interface
needed.

```java
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.api.event.Priority;
import org.abdullahcxd.paperhive.api.event.Subscribe;
import org.abdullahcxd.paperhive.api.event.server.ServerCrashEvent;
import org.abdullahcxd.paperhive.api.event.server.ServerStartEvent;

public final class Listeners {

    @Subscribe
    private void onStart(ServerStartEvent event) {
        if (event.getServer().getName().equals("maintenance")) {
            event.setCancelled(true); // keep it stopped
        }
    }

    @Subscribe(priority = Priority.MONITOR)
    private void onCrash(ServerCrashEvent event) {
        System.out.println(event.getServer().getName() + " crashed");
    }
}

// in onEnable:
Paperhive.getHive().getEventBus().register(new Listeners());
```

## Subscribing

- A `@Subscribe` method takes **exactly one parameter**, a `HiveEvent` type. It receives that event and its
  subtypes (subscribe to `ServerEvent` to get every server event).
- Any visibility works, including `private`.
- `register(listener)` returns a `Registration`; call `unregister()` on it, or `getEventBus().unregister(listener)`.

### Priority and cancelling

```java
@Subscribe(priority = Priority.HIGH, ignoreCancelled = true)
```

| Option | |
|---|---|
| `priority` | `LOWEST`, `LOW`, `NORMAL` (default), `HIGH`, `HIGHEST`, `MONITOR`. Lower runs first; `MONITOR` runs last and should only observe. |
| `ignoreCancelled` | Skip the event if a subscriber before this one cancelled it. |

A subscriber that throws is logged, and the others still run.

## Built-in events

All in `org.abdullahcxd.paperhive.api.event.server`.

| Event | When | Cancellable | Thread |
|---|---|---|---|
| `ServerStateChangeEvent` | Every state change, in order. `getFrom()`, `getTo()`. | no | Paperhive |
| `ServerStartEvent` | A server is about to start (command, API, autostart). Not for crash restarts. | **yes**: keeps it stopped | the one starting it |
| `ServerStartedEvent` | A server is up (`RUNNING`, after its first tick). | no | Paperhive |
| `ServerStoppedEvent` | A server stopped normally (`STOPPED`). | no | Paperhive |
| `ServerCrashEvent` | A server crashed (`CRASHED`). | no | Paperhive |

Every server event has `getServer()`, a [`HiveServer`](../reference/hive-api.md#hiveserver). The specific events follow
their `ServerStateChangeEvent`.

## Your own events

Extend `HiveEvent` (or `CancellableEvent`) and post it. Subscribers run on the posting thread, and `post` returns the
event, so you can check whether it was cancelled:

```java
public final class EventStartingEvent extends CancellableEvent {
    private final String arena;
    public EventStartingEvent(String arena) { this.arena = arena; }
    public String getArena() { return arena; }
}

EventStartingEvent event = Paperhive.getHive().getEventBus().post(new EventStartingEvent("castle"));
if (!event.isCancelled()) {
    // start it
}
```

::: callout warning "Events between halves"
An event class from your jar is a **different class in each half and each server** (see
[How Hive plugins work](overview.md#the-two-halves-are-separate-classes)). A subscriber in the server half
won't receive an `EventStartingEvent` posted by the host half, because they're two different classes. Use
[messages](messaging.md) to talk between halves; use your own events within one half.
:::

## Cleanup

Listeners registered from inside a server are dropped when that server stops, and a Hive plugin's when it's disabled.
Register them again in `onEnable`; there's nothing to clean up yourself.

## From the server half

Events arrive on a Paperhive thread. Use `runTask` before touching Bukkit:

```java
@Subscribe
private void onServerStarted(ServerStartedEvent event) {
    if (!event.getServer().equals(getHiveServer())) {
        runTask(() -> getServer().broadcast(Component.text(event.getServer().getName() + " is up")));
    }
}
```
