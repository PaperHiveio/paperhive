---
title: "Your first hive"
description: "Start Paperhive, add a second server, and control it from the console."
---

# Your first hive

This walks you from an empty folder to two running servers.

::: steps

1. **Make a folder for the hive**

   Everything Paperhive writes lives in the folder you start it from: the config, each server's folder, logs and the
   download cache.

   ```bash
   mkdir my-hive
   cd my-hive
   cp ~/Downloads/paperhive-<version>.jar .
   ```

2. **Start Paperhive**

   ```bash
   java -Xmx4G -Dcom.mojang.eula.agree=true -jar paperhive-<version>.jar
   ```

   `-Dcom.mojang.eula.agree=true` accepts the [Minecraft EULA](https://www.minecraft.net/eula) for every server.
   Without it, each server stops on its first start and asks you to accept it in its `eula.txt`.

   On the first start Paperhive writes `paperhive.yml` with one server called `main` on port `25565`, downloads
   Paper, patches it once (it's cached under `.paperhive-cache/`), and starts `main`.

3. **Look around**

   You're at the `hive>` prompt. Server output is printed above it, prefixed with the server's name.

   ```text
   hive> list
   main             RUNNING  port 25565  0/20 players  20.0 TPS
   hive> main say Hello from Paperhive
   ```

   `main say ...` sends a command to the `main` server's console. Join `localhost:25565` in Minecraft to see it.

4. **Add a second server**

   ```text
   hive> create lobby 25566
   hive> start lobby
   ```

   `create` adds the server to `paperhive.yml` (keeping your comments); its folder is `instances/lobby/`.
   You could also add it to the file by hand and restart Paperhive.

5. **Talk to one server at a time**

   ```text
   hive> attach lobby
   ```

   Now you only see `lobby`'s output, and what you type goes to `lobby`. Type `hive detach` to go back.

6. **Stop**

   ```text
   hive> shutdown
   ```

   Every server stops properly (worlds are saved), then Paperhive exits. `Ctrl+C` does the same.

:::

## What's in the folder now

```text
my-hive/
├─ paperhive.yml          the config: what runs, and how
├─ paperhive-<version>.jar
├─ instances/
│  ├─ main/               a normal Paper server folder: worlds, server.properties, plugins/, logs/
│  └─ lobby/
├─ plugins/               Hive plugins (not Paper plugins: those go in instances/<name>/plugins/)
├─ logs/                  Paperhive's own log
└─ .paperhive-cache/      the downloaded and patched Paper jar
```

## Control it from scripts

While Paperhive runs, any command can be sent from another terminal in the same folder:

```bash
java -jar paperhive-<version>.jar hive list
java -jar paperhive-<version>.jar hive restart lobby
```

This talks to the running host through `paperhive.sock` in the hive folder. See
[Console and commands](../guide/commands.md) for everything you can type.

## Run it as a service

Paperhive reads plain lines when there's no terminal, so it runs fine under systemd, Docker or a panel. Use
`hive <command>` for control, and a restart policy for the (rare) case of the JVM itself dying:

```ini
# /etc/systemd/system/paperhive.service
[Unit]
Description=Paperhive
After=network-online.target

[Service]
User=minecraft
WorkingDirectory=/srv/my-hive
ExecStart=/usr/bin/java -Xmx8G -Dcom.mojang.eula.agree=true -jar paperhive.jar
ExecStop=/usr/bin/java -jar paperhive.jar hive shutdown
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Next

- [Configure your servers and the host](../guide/configuration.md)
- [Put Velocity in front](../guide/velocity.md)
