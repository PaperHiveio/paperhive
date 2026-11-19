# Upgrading Paper

Paperhive runs a stock Paper jar; it doesn't patch Paper. What can break on a new Paper version is the short list of
Paper/Paperclip behaviour it relies on (below), not a set of patches.

## Procedure
1. Publish the new Paper build on the Paperhive mirror (new file name; the CDN caches jar URLs for 24h, see M11).
2. Add the version to `MCVersion` (`paperhive-downloader`) and, if it's the new default, to the default
   `paperhive.yml` (`HiveConfig.DEFAULT`).
3. Point `paper-api` in `gradle/libs.versions.toml` at the new version (the bridge and Hive plugins compile against it).
4. Fetch the jar once (start the host with `Version: <new>`), then run the real-Paper tests against it:
   ```
   ./gradlew build -Dpaperhive.it.paperJar=<home>/.paperhive-cache/papermc/<version>/server.jar \
       -Dpaperhive.it.cache=<home>/.paperhive-cache/paperclip -Dcom.mojang.eula.agree=true
   ```
   (`PaperInstanceIT`: boots, restarts without leaks, file isolation, two instances joinable; `HivePluginLeakIT`: the
   bridge and a server half across restarts, stats.) Also set `PAPER_VERSION` in `.github/workflows/paper-it.yml`, which
   runs the same tests in CI on Linux and Windows.
5. Run the host end to end with the example plugin (`test-plugin`) in `plugins/`: `list`, `status`, `<server>
   hiveserver start <other>`, `attach`, `shutdown`.
6. Go through the list below for anything the tests don't cover; update this file.

## What depends on the Paper version
| What                                                                                                                                                                                               | Where                                            | If it changes                                              |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------|------------------------------------------------------------|
| Paperclip: `-Dpaperclip.patchonly=true`, layout `versions/<v>/*.jar` + `libraries/**`, main class in the Paperclip jar's `META-INF/main-class`                                                     | `PaperclipCache`                                 | no server jar / wrong main class: the host fails at start  |
| Server options `--nogui --noconsole --universe --plugins --config --bukkit-settings --spigot-settings --commands-settings --paper-settings-directory --port --add-plugin`                          | `ServerInstance.serverArgs`, `PaperhiveMain`     | files land in the wrong folder, or the bridge isn't loaded |
| `Bukkit.getServer().getServer()` (`CraftServer` -> `DedicatedServer`), `isReady()`                                                                                                                 | `ServerInstance.checkReady`                      | servers never reach `RUNNING`                              |
| `createCommandSourceStack()`, `handleConsoleInput(String, CommandSourceStack)`                                                                                                                     | `ServerInstance.dispatchCommand`                 | console commands don't reach the server (logged)           |
| `Bukkit.shutdown()` stops the server and ends in `System.exit`                                                                                                                                     | `ServerInstance.requestShutdown`                 | graceful stop times out and gets forced                    |
| exit status 70 after a crash (`DedicatedServer.onServerExit`)                                                                                                                                      | crash detection (`CRASHED`)                      | crashes look like normal stops                             |
| `com.mojang.eula.agree` system property                                                                                                                                                            | the user's launch flags                          | EULA prompt on every server                                |
| Netty honouring `io.netty.transport.noNative`                                                                                                                                                      | `ServerInstance` static init                     | native transports per instance (see hazards)               |
| Paper API used by the bridge: `--add-plugin` loading, `LifecycleEvents.COMMANDS`, `Commands.register`, `BasicCommand`, `getTPS()`, `getAverageTickTime()`, `getPluginsFolder()`, `ComponentLogger` | `paperhive-bridge`                               | compile errors when bumping `paper-api` (step 3)           |
| Paper's plugin class loader: parent is the server's loader, and it finds classes for child loaders                                                                                                 | `ServerHalfLoader` (parent: the bridge's loader) | server halves can't load / aren't cleaned up at stop       |

The bytecode rewrites (`InstanceTransformer`) target only JDK APIs (`System.exit`, `System.in/out/err`, `java.io`
/`java.nio.file` paths, JUL, shutdown hooks, `FileChannel.lock`, JFR, `Executors`), so they don't depend on the
Paper version, only on Java's.

## Fork or not
Decided: no fork. A fork would mean rebasing Paper's patches for every release and users couldn't run the stock
jar; the trigger was "more than ~12 fragile Paper patches", and there are none: the rewrites are JDK-level, and the
Paper touch points are the table above (one reflective chain, a dozen documented options, the plugin API).
Revisit if a Paper release needs Paper-specific bytecode changes.
