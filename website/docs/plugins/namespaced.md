---
title: "Namespaced keys"
description: "namespace:value keys for channels, services and shared data, so plugins never collide."
---

# Namespaced keys

Channels, services and shared data are named with a **`Namespaced`** key: `namespace:value`, like Paper's
`NamespacedKey`. The namespace says who a key belongs to, so two plugins can both have a `joins` channel without
hearing each other's messages.

```java
Namespaced joins = key("joins");                      // in a plugin: "myplugin:joins"
Namespaced queue = Namespaced.of("lobby", "queue");    // "lobby:queue"
Namespaced parsed = Namespaced.parse("lobby:queue");   // the same key
Namespaced ours = Namespaced.paperhive("servers");     // "paperhive:servers"
```

## Your plugin's keys

Both halves of a Hive plugin have `key(value)`, which puts the value in the plugin's own namespace: its name from
`paperhive.yml`, lowercased, with other characters replaced by `_` (`My Plugin` becomes `my_plugin`).

The host half's `key("joins")` and the server half's `key("joins")` are the **same key**, which is how the two halves
find each other:

```java
// host half
Paperhive.getHive().channel(key("joins"), MessageCodec.STRING).subscribe(m -> getLogger().info(m.payload()));

// server half
channel("joins", MessageCodec.STRING).publish(player.getName() + " joined");
```

## Rules

| Part | Allowed | Example |
|---|---|---|
| namespace | `a-z 0-9 . _ -` | `myplugin`, `my-plugin`, `paperhive` |
| value | `a-z 0-9 . _ - /` | `joins`, `queue/vip` |

Both are lowercase and not empty, so a key is written and read the same everywhere. An invalid key throws
`IllegalArgumentException` right where it's made. `Namespaced.parse("value")` without a `:` uses the `paperhive`
namespace.

## API

| Method | |
|---|---|
| `Namespaced.of(namespace, value)` | A key from both parts. |
| `Namespaced.parse("ns:value")` | Read a key. |
| `Namespaced.forPlugin(pluginName, value)` | A key in a plugin's namespace (what `key()` uses). |
| `Namespaced.paperhive(value)` | A key in Paperhive's namespace. |
| `key.with(value)` | Another key in the same namespace. |
| `key.namespace()`, `key.value()`, `key.asString()` | Its parts, and `namespace:value`. |

`Namespaced` is a record: compare keys with `equals`, use them as map keys, sort them.
