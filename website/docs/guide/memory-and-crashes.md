---
title: "Memory and crashes"
description: "Sizing the shared heap, the memory warning, crash restarts, and what happens on OutOfMemoryError."
---

# Memory and crashes

## One heap for every server

All servers allocate from the same JVM heap. Java can't limit or measure memory per server, so the only limit is the
`-Xmx` you start Paperhive with. **Size it for all servers together.**

| Servers                     | Players | A reasonable start                                      |
|-----------------------------|---------|---------------------------------------------------------|
| 2-3 small (lobby, hub)      | few     | `-Xmx4G`                                                |
| 3-5 mixed                   | 20-50   | `-Xmx8G`                                                |
| larger worlds, many plugins | 50+     | `-Xmx12G` and up; watch `status` and the memory warning |

To keep smaller servers from crowding out the rest, give servers a `Ram` share
([Configuration](configuration.md#memory-ram)). A server only starts if its share is still free in the heap:

```text
Not starting survival: its Ram is 4096 MB but only 3072 MB of the 8192 MB heap is free (reserved: {lobby=1024, minigames=4096})
```

It's a reservation, not a cap: a server can still use more than its `Ram` while it runs. With
`Host.Unsafe.UseMachineRam` the shares are counted against the machine's memory instead, which lets them add up to
more than the heap.

Paperhive logs the heap limit when it starts. `status <name>` shows each server's TPS and tick time, the best signal
that the machine (or the heap) is struggling.

## The memory warning

When the heap is still more than `Host.MemoryWarning` percent full (default 90) right after a garbage collection,
Paperhive logs a warning naming the running servers. That's the moment to stop a server or raise `-Xmx`, before
anything runs out.

## When memory does run out

An `OutOfMemoryError` usually hits the server that was allocating. That server **crashes**, its memory is freed
with it, and the others keep running. Paperhive deliberately doesn't exit the JVM, which would stop every server
without saving.

The first `OutOfMemoryError` also writes a **heap dump** into `logs/` (`Host.Logs`), unless your launch flags already set
`-XX:+HeapDumpOnOutOfMemoryError` or `-XX:-HeapDumpOnOutOfMemoryError`. Open it with a tool like Eclipse MAT to see
which server held the memory.

## Crash restarts

With `Restart: on-crash`, a crashed server starts again by itself:

```yaml
Host:
  RestartDelay: 3     # seconds to wait before restarting
  MaxCrashes: 3       # give up after this many crashes...
  CrashWindow: 600    # ...within 10 minutes
Servers:
  - Name: survival
    Port: 25566
    Restart: on-crash
```

After `MaxCrashes` crashes within `CrashWindow`, Paperhive stops restarting it and logs why: something is wrong that
a restart won't fix. `stop <name>` cancels a pending restart.

## Watchdogs

Paper's own watchdog still works per server: a server whose main thread hangs for 60 seconds is stopped (only that
server). There's no watchdog for the JVM itself; if the whole process dies (a native crash, `kill -9`), let your
service manager restart it (`Restart=on-failure` in systemd, a Docker restart policy). Servers with
`Autostart: true` come back with it.

## Stopping takes a while?

A stopping server gets `Host.StopTimeout` seconds (default 60) to save its worlds and exit. If it hasn't by then,
Paperhive stops waiting and reports it, so one stuck server can't block a `shutdown` forever.
