---
title: "Contributing"
description: "Build Paperhive from source, run the tests, and contribute changes and docs."
---

# Contributing

Paperhive is MIT-licensed and developed on [GitHub](https://github.com/paperhiveio/paperhive). Bug reports, fixes,
docs and ideas are all welcome. Start with
[CONTRIBUTING.md](https://github.com/paperhiveio/paperhive/blob/main/CONTRIBUTING.md) for the process.

## Build from source

You need JDK 21.

```bash
git clone https://github.com/paperhiveio/paperhive.git
cd paperhive
./gradlew build
```

`build` compiles every module and runs every unit test, the Gradle plugin's included.

| Task | |
|---|---|
| `./gradlew build` | Compile and test everything (what CI runs). |
| `./gradlew :paperhive-host:run` | Run a host in `paperhive-host/run/`. |
| `./gradlew :paperhive-host:shadowJar` | Build the host jar. |
| `./gradlew publishToMavenLocal` | Publish the SDK, host and Gradle plugin to `~/.m2`, to try them in a plugin project. |

## Modules

| Module | |
|---|---|
| `paperhive-host` | The host: config, server instances, class loading, console. |
| `paperhive-api` | The Hive API. |
| `paperhive-plugin` | The host-half plugin API. |
| `paperhive-bridge` | The Paper plugin each server loads; the server-half API. |
| `paperhive-sdk` | Everything a Hive plugin compiles against. |
| `paperhive-gradle` | The Gradle plugin. |
| `paperhive-velocity` | The Velocity plugin. |
| `paperhive-downloader`, `paperhive-constants` | Downloading and verifying server jars. |
| `test-plugin` | An example Hive plugin, also used by the tests. |
| `website` | This documentation site. |

The design and the reasons behind it are in
[docs/architecture.md](https://github.com/paperhiveio/paperhive/blob/main/docs/architecture.md). Read it before
changing class loading or the bytecode transforms.

## Improve these docs

Every page has an **Edit this page on GitHub** link. The site is built with [docmd](https://docmd.io) from
`website/docs/`:

```bash
cd website
npm install
npm run dev      # live preview
npm run build    # the static site, in website/site/
```

## Security

Please report vulnerabilities privately:
[report a vulnerability](https://github.com/paperhiveio/paperhive/security/advisories/new). See
[SECURITY.md](https://github.com/paperhiveio/paperhive/blob/main/SECURITY.md).
