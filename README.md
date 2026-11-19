<p align="center">
  <img src="docs/logo.svg" alt="Paperhive" width="160">
</p>

<h1 align="center">Paperhive</h1>

<p align="center">One JVM, many Paper servers.</p>

Paperhive runs several [Paper](https://papermc.io) Minecraft servers inside a single JVM. It patches a stock Paper jar
once, then starts each server in its own class loader with its own folder, console, logs and plugins, so the servers
share one JIT-warmed runtime and one heap instead of one process each. Hive plugins span the whole hive: a host half
that sees every server, and a server half with full Bukkit/Paper access in each one.

> **Status:** early development (`0.0.1-SNAPSHOT`). Paper internals are pinned per release; see
> [docs/upgrading.md](docs/upgrading.md).

## Features

- **Many servers, one process**: start, stop, restart, create and delete servers at runtime from the `hive>` console
  or `java -jar paperhive.jar hive <command>`.
- **Isolation where it matters**: per-server working directory, stdout/stdin, logging, `System.exit` and threads;
  a crashing server doesn't take the others down (`Restart: on-crash`).
- **Hive plugins** with a host half and a server half in one jar, a typed event bus, and messaging between servers.
- **Velocity plugin** that registers the hive's servers with your proxy.
- **One config file**, `paperhive.yml`, validated with clear errors and migrated across versions.
- **Gradle plugin** for Hive plugin projects: dependencies, descriptor, scaffolding and a `runHive` task.

## Requirements

- Java 21
- Enough heap for every server together (`-Xmx`): there is no per-server memory limit

## Running a hive

```shell
./gradlew :paperhive-host:shadowJar          # builds paperhive-host/build/libs/paperhive.jar
mkdir my-hive && cd my-hive
java -Xmx8G -Dcom.mojang.eula.agree=true -jar ../paperhive-host/build/libs/paperhive.jar
```

The first start writes a `paperhive.yml` with one server, downloads the Paper jar from the Paperhive mirror and
patches it. Type `help` at the `hive>` prompt for the commands. Passing `-Dcom.mojang.eula.agree=true` means you
accept the [Minecraft EULA](https://www.minecraft.net/eula).

### Configuration

```yaml
ConfigurationVersion: 1
Software: papermc
Version: 1.21.11
Host:                     # optional; every key has a default
  Plugins: plugins
  StopTimeout: 60
  MemoryWarning: 90
Servers:
  - Name: lobby
    Port: 25565
  - Name: survival
    Port: 25566
    Restart: on-crash
    SharedPlugins: [shared/plugins]
```

Every key and its default is listed in the comments of a freshly generated `paperhive.yml`.

## Writing a Hive plugin

The Gradle plugin sets up everything. Until it's on a public repository, publish it from this repo with
`./gradlew publishToMavenLocal`, then:

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }

// build.gradle.kts
plugins { id("org.abdullahcxd.paperhive.userdev") version "0.0.1-SNAPSHOT" }
```

```shell
./gradlew createHivePlugin   # a starter host half, server half and paperhive.yml
./gradlew runHive            # build it and run a Paperhive host with it in run/
```

See [`test-plugin`](test-plugin) for a fuller example, and "Gradle plugin (userdev)" in
[docs/architecture.md](docs/architecture.md) for the options.

## Modules

| Module                | What it is                                                                 |
|-----------------------|----------------------------------------------------------------------------|
| `paperhive-host`      | The host: config, server instances, class loading, console (`paperhive.jar`) |
| `paperhive-api`       | The Hive API: servers, events, messaging                                   |
| `paperhive-plugin`    | The Hive plugin API (host half)                                            |
| `paperhive-bridge`    | The Paper plugin each server loads; the server-half API (`HiveBukkitPlugin`) |
| `paperhive-sdk`       | Everything a Hive plugin compiles against, as one dependency                |
| `paperhive-gradle`    | The Gradle plugin for Hive plugin projects                                  |
| `paperhive-velocity`  | The Velocity plugin                                                        |
| `paperhive-downloader`, `paperhive-constants` | Fetching and verifying server jars from the mirror     |

## Documentation

- [Architecture](docs/architecture.md): how it works and why
- [Tasks](docs/tasks.md): milestones and what was verified
- [Upgrading Paper](docs/upgrading.md)

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). Please report vulnerabilities privately as described in
[SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE). Paperhive is not affiliated with PaperMC or Mojang.
