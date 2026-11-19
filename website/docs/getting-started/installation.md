---
title: "Installation"
description: "What you need to run Paperhive, and how to get the jar."
---

# Installation

## Requirements

|             |                                                                                                                               |
|-------------|-------------------------------------------------------------------------------------------------------------------------------|
| **Java**    | 21 (the version Paper 1.21 needs). Any distribution, e.g. [Temurin](https://adoptium.net).                                    |
| **Memory**  | Enough heap for *every* server together. A Paper server typically needs 1-2 GB with a few players, so plan `-Xmx` as the sum. |
| **OS**      | Linux, Windows or macOS.                                                                                                      |
| **Network** | Access to the Paperhive mirror on the first start, to download Paper (it's cached afterwards).                                |

::: callout info "One heap for all servers"
Paperhive can't give each server its own memory limit: the JVM has one heap. See
[Memory and crashes](../guide/memory-and-crashes.md) for how to size it and what happens when it runs low.
:::

## Download

Get `paperhive-<version>.jar` from the
[latest GitHub release](https://github.com/paperhiveio/paperhive/releases). It's self-contained: Paperhive downloads
the Paper version it supports by itself.

The same release has `paperhive-velocity-<version>.jar`, the optional [Velocity plugin](../guide/velocity.md).

## Or build it yourself

```bash
git clone https://github.com/paperhiveio/paperhive.git
cd paperhive
./gradlew :paperhive-host:shadowJar
```

The jar is in `paperhive-host/build/libs/paperhive-<version>.jar`.

## Supported versions

Each Paperhive release supports one Minecraft version, because it adjusts a few Paper internals. The current
supported version is the default in a new `paperhive.yml`:

| Software  | Version   |
|-----------|-----------|
| `papermc` | `1.21.11` |

Paperhive refuses unknown versions with a list of the supported ones, so a typo can't start the wrong server.

## Next

[Start your first hive →](first-hive.md)
