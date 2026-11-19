---
title: "Shared plugins"
description: "Install a Paper plugin once and load it on several servers."
---

# Shared plugins

Each server has its own `plugins/` folder, like any Paper server. When several servers use the same plugin (a
permissions plugin, a chat formatter, ...), you can keep **one copy of the jar** in a shared folder instead.

## Set it up

```yaml
Servers:
  - Name: lobby
    Port: 25565
    SharedPlugins: [shared/plugins]

  - Name: survival
    Port: 25566
    SharedPlugins: [shared/plugins, shared/survival-only]
```

Put the jars in `shared/plugins/`. Both servers load them, and `survival` also loads the jars in
`shared/survival-only/`. Folders are relative to the hive folder; missing ones are created.

## Jars are shared, data is not

Paper loads the jar from the shared folder, but each plugin still keeps its **config and data in the server's own
`plugins/<PluginName>/`**. So `lobby` and `survival` can configure the same plugin differently, and one server's data
never leaks into another's.

```text
shared/plugins/LuckPerms.jar          one jar
instances/lobby/plugins/LuckPerms/    lobby's config and data
instances/survival/plugins/LuckPerms/ survival's config and data
```

## Updating

Replace the jar in the shared folder. Each server picks the new version up at its **next start**, so you can roll it
out one server at a time with `restart <name>`.

::: callout tip "Paper plugins vs. Hive plugins"
Shared plugins are ordinary Paper plugins, loaded by each server separately. A plugin that should know about *all*
servers at once is a [Hive plugin](../plugins/overview.md) instead, and goes in the host's `plugins/` folder.
:::

## Under the hood

`SharedPlugins` folders are passed to Paper with its own `--add-plugin-dir` option, so plugins load exactly as they
would on a normal Paper server.
