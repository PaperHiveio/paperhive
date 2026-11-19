---
title: "Hive commands"
description: "Add your own commands to the hive> prompt and the hive client."
---

# Hive commands

A Hive command runs **in Paperhive, once**: typed at the `hive>` prompt or sent with
`java -jar paperhive.jar hive <command>`. Paperhive's own commands (`list`, `start`, ...) are Hive commands too.

Not to be confused with in-game commands, which run inside one server: those are registered by your
[server half](server-half.md#commands).

## Writing one

Extend `HiveCommand` and register it in your host half's `onEnable`:

```java
import org.abdullahcxd.paperhive.plugin.command.HiveCommand;
import org.abdullahcxd.paperhive.plugin.command.HiveCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class GreetCommand extends HiveCommand {

    GreetCommand() {
        //    name     usage    description   aliases...
        super("greet", "<who>", "say hello", "hi");
    }

    @Override
    public void execute(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        if (args.size() != 1) {
            sendUsage(sender); // "usage: greet <who>"
            return;
        }
        sender.sendMessage("Hello, " + args.getFirst() + "!");
    }

    @Override
    public @NotNull List<String> complete(@NotNull HiveCommandSender sender, @NotNull List<String> args) {
        return args.size() == 1 ? List.of("world") : List.of();
    }
}
```

```java
@Override
public void onEnable(@NotNull HiveContext<PluginContext> context) {
    context.getContext().getCommands().register(new GreetCommand());
}
```

```text
hive> greet world
Hello, world!
hive> hi Steve
Hello, Steve!
```

## The parts

| Part | |
|---|---|
| `name` | What's typed. Case-insensitive. |
| `usage` | The arguments after the name, for `help` and `sendUsage` (e.g. `<name> [port]`); empty if none. |
| `description` | One line for `help`. |
| `aliases` | Other names (`ls` for `list`). |
| `execute(sender, args)` | Runs the command. `args` are the words after the name. |
| `complete(sender, args)` | Optional tab completion: candidates for the last word in `args` (possibly empty). |

## The sender

`HiveCommandSender` is who ran it:

| Method | |
|---|---|
| `sendMessage(String)` | Reply. At the prompt it's printed; for a `hive` client it's sent back to that client. |
| `isConsole()` | `true` at the host's own prompt, `false` for a client through the control socket. |

## Colours

Reply in the same style as Paperhive's own commands with `ConsoleText` (in `org.abdullahcxd.paperhive.plugin.command`).
Paperhive drops the colours wherever they can't show: a plain terminal or a service's log, `hive` clients and the log
file.

```java
import static org.abdullahcxd.paperhive.plugin.command.ConsoleText.*;

sender.sendMessage(success("Greeted " + name(who)));                     // green »
sender.sendMessage(error("No player named " + value(who)));              // red ×
sender.sendMessage(warning(name(server) + " isn't running"));             // yellow !
sender.sendMessage(info("Teleporting " + muted("(this takes a second)")));// honey »
```

| Method | Looks like |
|---|---|
| `success`, `error`, `warning`, `info` | A coloured `»`, `×`, `!` or `»` before the text. |
| `name` | Bold honey, for server and plugin names. |
| `value`, `command`, `muted` | Bold, cyan (something to type), gray. |
| `state("RUNNING")`, `tps(19.8)` | Coloured by meaning: green when fine, yellow on the way or lagging, red when crashed or struggling. |
| `pad(text, width)`, `strip(text)` | Line up columns (colours take no room); remove the colours. |

## Rules

- Names and aliases are unique across the hive; `register` throws `IllegalArgumentException` if one is taken, and a
  plugin can't take a built-in command's name.
- Your commands are removed when your plugin is disabled.
- `help` lists them with Paperhive's own.
