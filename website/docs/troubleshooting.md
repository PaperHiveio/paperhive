---
title: "Troubleshooting"
description: "Common problems running Paperhive and writing Hive plugins, and how to fix them."
---

# Troubleshooting

## Running a hive

### Paperhive won't start: "paperhive.yml has N problem(s)"

The config is invalid. The message lists every problem with the right spelling or allowed values; fix them all and
start again. See the [paperhive.yml reference](reference/host-config.md).

### "ConfigurationVersion N is from a newer Paperhive"

The file was written by a newer Paperhive than the one you're running. Use that newer version, or restore the
`paperhive.yml.v<N>.bak` the upgrade kept.

### A server stops right after starting

Usually the EULA. Start Paperhive with `-Dcom.mojang.eula.agree=true`, or set `eula=true` in that server's
`eula.txt`. Otherwise, check the server's output above the prompt, or `instances/<name>/logs/latest.log`.

### "Port ... already in use"

Another process (or another server in the hive) uses that port. Every server needs its own `Port`; Paperhive rejects
duplicates in the file, but can't know about other programs.

### The download fails

The first start needs the Paperhive mirror. Once Paper is cached in `.paperhive-cache/`, Paperhive starts offline. If
a downloaded jar doesn't match its SHA-256, it's deleted and the start fails rather than running an unverified jar.

### `hive <command>` says no host is running

Run it in the same folder Paperhive runs in: it connects through `paperhive.sock` there.

### The heap warning keeps appearing

All servers share one heap. Raise `-Xmx`, run fewer servers, or find the heavy one with a heap dump. See
[Memory and crashes](guide/memory-and-crashes.md).

### A server keeps crashing and isn't restarted any more

After `MaxCrashes` crashes within `CrashWindow`, Paperhive stops restarting it on purpose. Read its log to find the
cause, fix it, and `start` it.

## Writing plugins

### `IllegalStateException: Not running under a Paperhive host`

`Paperhive.getHive()` was called on a plain Paper server, or in a test. Hive APIs only work inside a hive.

### My server half isn't loaded

- Is `server-main` in your `paperhive.yml`, with the full class name?
- Is the jar in the **host's** `plugins/` folder, not a server's?
- Is the plugin disabled for that server (`DisabledHivePlugins`) or everywhere (`plugin list`)?

### `NoClassDefFoundError: org/bukkit/...` in the host half

The host half can't use Bukkit: there's no single server to talk to. Move that code to the
[server half](plugins/server-half.md) and trigger it with a [message](plugins/messaging.md).

### My event posted in the host half never reaches the server half

Your event class is a different class in each half. Use [messages](plugins/messaging.md) between halves.

### Bukkit complains about being called asynchronously

Hive events and messages arrive on a Paperhive thread. Wrap Bukkit calls in `runTask(...)` in the server half.

### The build fails with a duplicate `paperhive.yml`

You set `paperhive { main = ... }` *and* have `src/main/resources/paperhive.yml`. Keep one.

## Known limits

- **No per-server memory limit.** One heap, one `-Xmx`.
- **A JVM crash takes every server down.** Run Paperhive under a service manager that restarts it.
- **Native libraries** (some database drivers, native Netty transports) can be loaded only once per JVM. Paperhive
  makes Netty use its pure-Java transport for this reason.
- **Not a security boundary** between servers.

Still stuck? [Open an issue](https://github.com/paperhiveio/paperhive/issues/new/choose).
