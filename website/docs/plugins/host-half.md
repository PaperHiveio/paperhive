---
title: "The host half"
description: "PaperhivePlugin: the part of a Hive plugin that runs once and sees every server."
---

# The host half

The host half is a class extending `PaperhivePlugin`, named by `main` in your plugin's `paperhive.yml`. Paperhive
loads it once, for the whole hive.

```java
package com.example.myplugin;

import org.abdullahcxd.paperhive.api.HiveServer;
import org.abdullahcxd.paperhive.api.Paperhive;
import org.abdullahcxd.paperhive.plugin.HiveContext;
import org.abdullahcxd.paperhive.plugin.PaperhivePlugin;
import org.abdullahcxd.paperhive.plugin.context.PluginContext;
import org.abdullahcxd.paperhive.plugin.context.ServerContext;
import org.jetbrains.annotations.NotNull;

public final class MyHivePlugin extends PaperhivePlugin {

    @Override
    public void onEnable(@NotNull HiveContext<PluginContext> context) {
        getLogger().info("Enabled for {} servers", Paperhive.getHive().getServers().size());
        context.getContext().getCommands().register(new BroadcastCommand());
        Paperhive.getHive().getEventBus().register(this);
    }

    @Override
    public void onServerStart(@NotNull HiveContext<ServerContext> context) {
        HiveServer server = context.getContext().getServer();
        getLogger().info("{} is up on port {}", server.getName(), context.getContext().getServerPort());
    }

    @Override
    public void onDisable() {
        getLogger().info("Bye");
    }
}
```

## Lifecycle methods

| Method                                      | When                                                                           | Thread             |
|---------------------------------------------|--------------------------------------------------------------------------------|--------------------|
| `onEnable(HiveContext<PluginContext>)`      | Paperhive starts, or `plugin enable`. **Required.**                            | host               |
| `onServerStart(HiveContext<ServerContext>)` | A server this plugin is enabled for is `RUNNING`.                              | a Paperhive thread |
| `onDisable()`                               | Paperhive stops, or `plugin disable`. Your Hive commands are removed after it. | host               |

### PluginContext

What `onEnable` gets through `context.getContext()`:

| Method          |                                                                                    |
|-----------------|------------------------------------------------------------------------------------|
| `getCommands()` | The `HiveCommandRegistry`: [register Hive commands](hive-commands.md) here. |

### ServerContext

What `onServerStart` gets through `context.getContext()`:

| Method            |                                                                                        |
|-------------------|----------------------------------------------------------------------------------------|
| `getServer()`     | The `HiveServer` that's now up.                                                        |
| `getServerName()` | Its name.                                                                              |
| `getServerPort()` | Its port.                                                                              |
| `getServerIp()`   | The `server-ip` from its `server.properties` (empty when it listens on every address). |

## Useful methods

| Method                  |                                                                                                        |
|-------------------------|--------------------------------------------------------------------------------------------------------|
| `getLogger()`           | An SLF4J `Logger` that prefixes messages with `[<plugin name>]`. Use `{}` placeholders.                |
| `getPluginDescriptor()` | Your `paperhive.yml`: `getName()`, `getVersion()`, `getDescription()`, `getMain()`, `getServerMain()`. |
| `getPluginManager()`    | Enable, disable or find other Hive plugins.                                                            |
| `Paperhive.getHive()`   | The [Hive API](../reference/hive-api.md): every server, events, messages.                                  |

## Don't touch Bukkit here

The host half runs outside every server, so Bukkit classes aren't available to it. To do something inside a server,
either:

- run it on that server's main thread from the host half:

  ```java
  server.runOnServer(() -> {
      // this runs inside the server, on its main thread
  });
  ```

  The code inside the lambda can't reference Bukkit classes directly either (they're compiled against the host's
  view); use it to call `dispatchCommand`, or to trigger your server half through an event or a message;

- or, better, put that logic in your [server half](server-half.md) and tell it what to do with a
  [message](messaging.md).

Running a console command is the simplest case:

```java
Paperhive.getHive().getServer("lobby").dispatchCommand("say The event starts in 5 minutes!");
```

## Example: tell every server about a crash

```java
@Subscribe
private void onCrash(ServerCrashEvent event) {
    String name = event.getServer().getName();
    getLogger().warn("{} crashed", name);
    for (HiveServer server : Paperhive.getHive().getServers()) {
        if (server.getState() == HiveServerState.RUNNING) {
            server.dispatchCommand("say " + name + " crashed and is restarting, hang tight!");
        }
    }
}
```
