---
title: "Console and commands"
description: "The hive> prompt, Hive commands, server commands, and controlling Paperhive from scripts."
---

# Console and commands

Paperhive has one console for the whole hive: the `hive>` prompt. It has line editing, history (kept in
`.paperhive-history`) and tab completion. Output from every server is printed above it, prefixed with the server's
name. The prompt's name, colours, banner and history file can be changed under
[`Host.Console`](configuration.md#console).

## Two kinds of commands

|          | Hive commands                          | Server commands                 |
|----------|----------------------------------------|---------------------------------|
| Runs     | in Paperhive, once                     | inside one server               |
| Examples | `list`, `start lobby`, `create events` | `say hi`, `op Steve`, `stop`    |
| Typed as | `list`                                 | `lobby say hi`, or `all say hi` |

Anything that isn't a Hive command is read as `<server> <command>` (one server) or `all <command>` (every running
server).

## Hive commands

| Command                                                          | What it does                                                                                                                           |
|------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `list` (`ls`)                                                    | Every server with its state, port, players and TPS. `list --tsv` prints `name<TAB>state<TAB>port` per line, a stable format for tools. |
| `status <name>`                                                  | One server in detail: state and uptime, port, folder, TPS (1/5/15 min), tick time, players, threads, last exit status.                 |
| `start <name>`                                                   | Start a server.                                                                                                                        |
| `stop <name>`                                                    | Stop a server (worlds are saved).                                                                                                      |
| `restart <name>`                                                 | Stop it if it runs, then start it.                                                                                                     |
| `create <name> [port]`                                           | Add a server to `paperhive.yml` (the next free port if none is given). It isn't started.                                               |
| `delete <name>`                                                  | Remove a stopped server from `paperhive.yml`. Its folder is kept.                                                                      |
| `set <name> <Key> [value]`                                       | Change a server setting in `paperhive.yml`; no value resets it to the default. Applies at the next start.                              |
| `attach <name>`                                                  | Type straight into one server and see only its output.                                                                                 |
| `detach`                                                         | Leave the attached server.                                                                                                             |
| `plugin list` / `plugin enable <name>` / `plugin disable <name>` | Manage [Hive plugins](../plugins/overview.md).                                                                                             |
| `help` (`?`)                                                     | List every command, including ones added by Hive plugins.                                                                              |
| `shutdown` (`exit`)                                              | Stop every server and end Paperhive.                                                                                                   |

A command can start with `hive` too (`hive list`), which is handy when attached to a server: `hive detach`.

## Attaching to a server

```text
hive> attach survival
survival> op Steve
survival> hive detach
hive>
```

While attached the prompt shows the server's name, you see only its output, and everything you type goes to it, like
a normal Paper console.

## Controlling Paperhive from outside

Any Hive command can be sent to a running Paperhive from another terminal, a script or a panel:

```bash
java -jar paperhive.jar hive list
java -jar paperhive.jar hive restart survival
java -jar paperhive.jar hive survival say Restarting in 1 minute
```

Run it in the hive's folder: it connects through the `paperhive.sock` file there. It prints the reply and exits with
`0`, or `1` if no Paperhive is running in that folder.

::: callout info "Who can do this?"
The control socket is a local file. Anyone who can access the hive's folder can control the hive, so keep that folder
private to the user running Paperhive.
:::

## Without a terminal

Under systemd, Docker or a panel without a terminal, Paperhive reads plain lines from standard input with no prompt.
If standard input closes, Paperhive keeps running and `hive` clients still control it.

## Plugin commands

Hive plugins can add their own Hive commands (they show up in `help`), and their server halves can add normal
in-game commands to each server. See [Hive commands](../plugins/hive-commands.md) and
[The server half](../plugins/server-half.md).
