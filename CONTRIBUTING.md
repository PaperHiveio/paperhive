# Contributing to Paperhive

Thanks for helping. Bug reports, fixes, docs and ideas are all welcome.

## Before you start

- **Bugs**: open an issue with the Paperhive version, Java version, OS, your `paperhive.yml` (without secrets), and
  the relevant part of `logs/latest.log`.
- **Features and larger changes**: open an issue first so we can agree on the approach. Paperhive patches Paper at
  the bytecode level, and some changes have consequences that aren't obvious; [docs/architecture.md](docs/architecture.md)
  explains the design and the decisions behind it.
- **Security issues**: don't open a public issue; see [SECURITY.md](SECURITY.md).

## Development setup

- JDK 21
- Clone, then `./gradlew build`: compiles everything and runs every unit test, including the Gradle plugin's

Useful tasks:

| Task                                 | What it does                                                    |
|--------------------------------------|-----------------------------------------------------------------|
| `./gradlew build`                    | Compile and unit-test everything (what CI runs)                 |
| `./gradlew :paperhive-host:run`      | Run the host in `paperhive-host/run/`                           |
| `./gradlew :paperhive-host:shadowJar`| Build `paperhive.jar`                                           |
| `./gradlew publishToMavenLocal`      | Publish the SDK, host and Gradle plugin to `~/.m2` for plugin projects |

The tests against a real Paper server (`PaperInstanceIT`, `HivePluginLeakIT`) are opt-in; [docs/upgrading.md](docs/upgrading.md)
shows how to run them. Run them when you touch class loading, transforms, instance start/stop or the bridge.

## Pull requests

- Keep a pull request to one change, with a test for new behaviour or the bug it fixes.
- `./gradlew build` must pass on Linux and Windows (CI runs both).
- Follow the code around you: Java 21, 4-space indentation, Lombok where the module already uses it, comments that
  explain *why* rather than *what*.
- Update `docs/` when you change behaviour, config keys or commands, and `paperhive.yml`'s generated comments when you
  add a config key (`HiveConfig.DEFAULT`). A config format change needs a `ConfigurationVersion` migration.
- Write commit messages in the imperative ("Add IP key to servers"), with a body explaining why when it isn't obvious.

## License

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
