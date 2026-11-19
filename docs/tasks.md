# Paperhive: Tasks

Run many Paper servers inside one JVM, each in its own classloader, controlled by CLI or plugin API.
Approach: **stock Paper jar + per-instance classloader + ASM transforms** (no fork, no Java agent).
Design notes and gotchas: see `architecture.md`.

Legend: `[ ]` todo, `[x]` done. Each milestone has an exit criterion; don't start the next until it holds.

---

## M0: Project skeleton
- [x] Gradle multi-module: `paperhive-api`, `paperhive-host`, later `paperhive-plugin`
- [x] Java toolchain = the JDK your target Paper version requires (21 for 1.21.x)
- [x] Package namespace `org.abdullahcxd.paperhive.*`
- [x] Pin one Paper version for the first release
- [x] Logging: SLF4J API in every module, Log4j2 backend in the host, Bukkit-style console and `logs/latest.log` format
- [x] `PluginLogger`: Bukkit-style `[PluginName]` prefix for hive plugins

**Exit:** `./gradlew build` passes on a clean checkout.

## M1: Paperclip prep and shared cache
- [x] Run `paper.jar` with `-Dpaperclip.patchonly=true` in a shared cache dir
- [x] Locate the patched jar at `versions/<ver>/<brand>-<ver>.jar` (e.g. `paper-1.21.11.jar`)
- [x] Collect every jar under `libraries/`
- [x] Read the real main class from `META-INF/main-class` in the Paperclip jar
- [x] Skip patching if the cache is already populated; re-patch when the Paper jar changes (hash it)
- [x] Retry a failed or partial patch on the next launch (`.complete` marker written only after a successful read)
- [x] Prune cache entries no host uses any more, so old jars don't pile up
- [x] Cache lock: two hosts sharing a cache never patch or prune at once (second one waits, then reuses)
- [x] In-use lock: an entry a running host uses is never pruned by another host
- [x] Unit tests: fake Paperclip jar, cache reuse, re-patch, pruning, in-use, failures, two real host processes
- [x] Host gets `paper.jar` from the downloader (mirror) instead of a CLI path argument; falls back to the cached jar when offline
- [x] One cache location: `.paperhive-cache/` holds downloads (`<software>/<version>/`) and Paperclip output (`paperclip/`)

**Exit:** the second launch skips patching; cache contents are correct.

## M2: Boot ONE instance in your own classloader
- [x] `ApiGateLoader` as parent: sees only the JDK and Paperhive's own classes (`org.abdullahcxd.paperhive.*`: api, host, plugin), so plugins in an instance can use Paper and Paperhive together; host third-party libs (Log4j, SLF4J, Jackson, ASM) stay hidden
- [x] Move `paperhive-api` classes into `org.abdullahcxd.paperhive.api`
- [x] Child-first `InstanceClassLoader` over server jar and libraries (`java.*` and the API stay parent-first)
- [x] Start `main(String[])` by reflection on a dedicated thread with the context classloader set
- [x] Run the host with cwd = instance dir (temporary workaround); cache moved out via `-Dpaperhive.cache`
- [x] Pass Paper's flags where they exist: `--nogui --universe --plugins --config --bukkit-settings --spigot-settings --commands-settings --paper-settings-directory` (checked with `--help`)
- [x] Unit tests: gate visibility, per-instance classes and statics, shared Paperhive classes, resource lookup

**Exit:** you can join the server. No ASM yet. (Verified: booted in the loader, answered a server-list ping, stopped with `stop`.)

## M3: Make one instance stop cleanly
- [x] Transform `System.exit`, `Runtime.exit`, `Runtime.halt` into "stop this instance" (`InstanceTransformer` + `InstanceHooks`; the caller is unwound with `InstanceExit`); method references (`invokedynamic`/`ldc` handles) too
- [x] Redirect shutdown hooks registered by the instance: kept per instance, run on exit, on a forced stop, and when the instance ends on its own; `halt` skips them
- [x] Join all instance threads on stop (thread group + context loader; common-pool workers excluded): 5s grace, then their pools are shut down (`ThreadPoolExecutor`, Netty event loops, `ForkJoinPool`) and threads interrupted; still alive after 10s more = reported as leaked
- [x] Verify the classloader becomes unreachable after stop (`WeakReference` unit tests; `PaperInstanceIT` writes a heap dump if a loader survives)
- [x] Handle the EULA failure path without exiting the JVM (an instance with only daemon threads left ends like a JVM would: hooks run, leftovers stopped)
- [x] Undo JVM-wide state an instance leaves behind: `System.out/err/in`, default uncaught handler, JUL handlers, MBeans, security providers, JFR listeners / periodic events / event classes, file locks (worlds' `session.lock`), `Executors.newSingleThreadExecutor` Cleaner registrations
- [x] Host stop: `Bukkit.shutdown()`, falling back to SIGTERM-like (hooks + interrupts) before Bukkit is up; host Ctrl+C stops the instance properly
- [x] Host owns stdin and forwards console lines to the instance (`--noconsole`; Paper's own reader would block on `System.in` forever)
- [x] `--add-opens java.base/java.lang java.base/java.util.concurrent` (jar manifest, `run` task, tests) so leftover pools can be found from their threads
- [x] Tests: `ServerInstanceTest` (exit/halt/return/serve fake server, locks, pools, restart, loader collected) and opt-in `PaperInstanceIT` against real Paper
- [x] Deregister JDBC drivers an instance registered (`JdbcDrivers`: `DriverManager` only lets the driver's own loader see it, so a generated helper class is defined in the instance loader)
- [x] Plugin code is rewritten too: `defineClass` calls made by instance code (Paper's `PluginClassLoader`, `PaperSimplePluginClassLoader`, ...) go through `InstanceTransformer` first, so a plugin calling `System.exit` ends only its instance (needs `--add-opens java.base/java.lang`)
- [x] An instance's own Log4j is shut down once it has stopped (`InstanceLog4j`), so `logs/latest.log` is closed right away instead of at some later GC; on Windows an open log couldn't be rotated at the next start (`HivePluginLeakIT` deletes it after every stop; found by the `paper-it` workflow on Windows)
- [x] JDBC drivers registered from a plugin's own loader are deregistered too: every loader inside the instance that defines classes is remembered, and the helper is defined in each on stop; server halves' `ServerHalfLoader` now defines its classes itself, so they're rewritten and covered like Paper's plugins (`ServerInstanceTest.pluginExitStopsOnlyTheInstance`: the fake plugin registers its own driver, and the instance loader must still be collected)

**Exit:** start, stop, and restart one instance 10 times with no growth in loaded classes or threads.
(Verified with `PaperInstanceIT`: 10 rounds of real Paper, every loader collected, threads flat at 12, loaded classes 6940 → 7072 (+~14/round of JDK-internal classes), boot ~7s, stop ~7.4s.)

## M4: Per-instance filesystem root (remove the cwd hack)
- [x] `HiveFs.resolve(path)` against the calling instance's root (absolute paths return without any lookup)
- [x] Instead of `Hive*` subclass shims: rewrite the path *argument* of `File`, `FileInputStream`, `FileOutputStream`, `FileReader`, `FileWriter`, `RandomAccessFile`, `PrintWriter`, `PrintStream` constructors in place (objects stay plain JDK ones, so `getClass()`/`equals` and JDK internals that take a `File` keep working)
- [x] Redirect `Path.of`, `Paths.get`, default `FileSystem.getPath`; plus `File.getAbsolute*/getCanonical*` and `Path.toAbsolutePath` for relative objects the JDK makes
- [x] `System.getProperty("user.dir")` is the instance root; child processes (`ProcessBuilder.start`, `Runtime.exec`) start in it
- [x] ASM rewrite in `InstanceTransformer` (static replacements and in-place argument rewrites with `SWAP`; stack shape unchanged, no frame recomputation), incl. method references
- [x] Caller-instance lookup: `StackWalker` + `InstanceClassLoader`, falling back to the thread's context loader (no `InheritableThreadLocal` needed so far)
- [x] Hook the loader used for plugins (`PluginClassLoader`) so plugin code is transformed too (done in M3 via the `defineClass` rewrite)
- [x] Remove the cwd hack: host home = cwd (`.paperhive-cache/`, `instances/<name>/`, host `logs/`); `ServerInstance` creates its root; `-Dpaperhive.cache` dropped
- [x] Tests: `ServerInstanceTest.relativePathsResolveAgainstTheInstanceRoot` (every rewritten API) and `PaperInstanceIT.twoInstancesKeepToTheirOwnRoots` (two real Paper instances at once)
- [x] Relative `Path`s the JDK derives later (`relativize`, `getFileName`) and opens through `Files.*` resolve against the instance root: `FilesMirror` generates a same-signature mirror of every `Files` method taking a `Path`, and instance code's `Files.*` calls go through it (`ServerInstanceTest.relativePathsResolveAgainstTheInstanceRoot`: `derived.txt`); opening one through a `FileSystemProvider` directly is still not covered

**Exit:** two instances with different roots never touch each other's files; host cwd is irrelevant.
(Verified: two real Paper instances run at once in one JVM, each writes world/, logs/, configs, usercache.json etc. only into its own root; the JVM cwd gets nothing but the host's own log. The host run from a fresh home puts everything Paper writes under `instances/main`.)

## M5: Per-instance I/O and logging
- [x] Route `System.out`, `System.err`, `System.in` per instance (`HiveIo`): instance code's reads of them and `System.setOut/setErr/setIn` use the calling instance's own slots, so Paper redirecting `System.out` into its logger no longer takes it from other instances; the JVM-wide streams are routing streams for JDK code (e.g. `printStackTrace()`), picking the instance by thread group / context loader
- [x] Separate console per instance (`InstanceConsole`): its output, split into lines, goes to listeners and is echoed to the host prefixed with `[name]`; stdin is empty per instance, commands go through `dispatchCommand` (attach/detach comes with the CLI, M8)
- [x] Isolate log4j: each instance loads its own Log4j from its own loader, so it has its own `LoggerContext` and config; no selector needed
- [x] Isolate `java.util.logging` (`HiveJul`): JUL is JVM-wide, so Paper's root handler and plugin loggers (registered by name, e.g. both instances' `spark`) crossed between instances; `Logger.getLogger/getGlobal` and `LogManager.getLogger/addLogger/getLoggerNames/reset` now use a per-instance namespace
- [x] Verify `logs/latest.log` goes into each instance's own dir (each instance has its own Log4j, and its relative `logs/` path resolves against its root since M4; checked by `PaperInstanceIT.twoInstancesKeepToTheirOwnRoots`)
- [x] Keep the host's Log4j out of instances (`ApiGateLoader` hides it, `InstanceClassLoaderTest.gateSharesPaperhiveAndJdkOnly`), so each instance uses Paper's own copy and config
- [x] Tests: `ServerInstanceTest.eachConsoleOnlySeesItsOwnOutput` (two fake instances at once: out/err/printStackTrace/threads/pools/JUL/own `setOut`), `PaperInstanceIT.twoInstancesKeepToTheirOwnRoots` (real Paper: startup line and spark line once each, in the right console and log)
- [x] Boot slowdown (~7s -> ~9s) fixed: the rewriter's byte-substring pre-check is now an exact constant-pool check on the referenced `owner.name` (`InstanceTransformer.TARGETS`, `InstanceTransformerTest`); boot is ~5.5s
- [x] JDK-internal loggers (`System.Logger`, platform loggers): records reaching the JVM-wide JUL root from an instance thread are routed to that instance's root (`HiveJul.install`); the host keeps the original root handlers

**Exit:** two instances log to separate files and each console only sees its own output.
(Verified with two real Paper instances at once: each console and each `logs/latest.log` has only its own startup line and exactly one spark line; the host's own log stays unprefixed in the host's `logs/`.)

## M6: Two instances side by side
- [x] Different ports, dirs, configs from `paperhive.yml` (`HiveConfig`: name, port, optional root, default `instances/<name>`; written with one `main` instance if missing; duplicate names/ports rejected). The host starts every instance with `--port`; console lines `<name> <command>` go to one instance, other lines to all
- [x] Netty native transport: forced NIO (`io.netty.transport.noNative=true`, set by `ServerInstance` unless already set; Paper's Netty 4.2.7 checks it for epoll and kqueue). Native would extract and load another copy of the library per instance loader on every restart, for no real gain at this scale
- [x] Verify static state is truly separate: each instance has its own `Bukkit` class and server object with its own port and motd; restarting one leaves the other's server object in place (`PaperInstanceIT.twoInstancesAreJoinableAndRestartIndependently`)
- [x] Stop and start one while the other runs: the other stays `RUNNING` and keeps accepting logins; the stopped one's port closes, it comes back, and its old loader is collected (same test; logins via the test's `MinecraftClient`)
- [x] Native transport on Linux exercised: the `paper-it` workflow's Linux "native" variant runs the real-Paper tests with `-Dio.netty.transport.noNative=false`, and epoll works (boots, two joinable instances, file isolation, `PaperInstanceIT`'s 10 rounds with every loader collected). NIO stays the default (one native library per instance loader, no gain at this scale). macOS (kqueue) not run
- [x] The "leak" seen now and then in `HivePluginLeakIT` (Linux and Windows CI) and `PaperInstanceIT`'s class counts: the CI heap dump showed the stopped loader only softly reachable, through the JDK's one-entry `asTypeSoftCache` of `java.lang.runtime.ObjectMethods.OBJECTS_EQUALS` (every record's `equals`) holding a method type with Minecraft's `DensityFunction`. It's replaced by the next server or cleared under memory pressure, so not a leak (see "Soft references" in docs/architecture.md). The checks now clear every soft reference (by running into an `OutOfMemoryError` on purpose, which the JVM guarantees does that) before calling a loader leaked

**Exit:** both are joinable at once; restarting one doesn't affect the other.

(Verified with real Paper: an offline-mode login is accepted by both at once, again by the other after one stops, and by both after it restarts. Host end to end with a two-instance `paperhive.yml`: both boot on their ports, `beta list` / `alpha list` reach only that instance, `stop` stops both, exit 0.)

## M7: Config and instance lifecycle
- [x] `paperhive.yml` (PascalCase keys): optional `Host` (`Plugins`, `Cache`, `Mirror`, `StopTimeout`, `MemoryWarning`, `RestartDelay`, `MaxCrashes`, `CrashWindow`); `Servers` with `Name`, `Port`, `IP`, `Root`, `Plugins`, `Args`, `Autostart`, `Restart`, `DisabledHivePlugins` (read now, used from M9); servers with `Autostart: false` are created but not started
- [x] Software and version from config (`Software`, `Version`) instead of the pinned `PAPERMC` / `1.21.11`
- [x] Instance states: `CREATED`, `STARTING`, `RUNNING` (Paper's `MinecraftServer.isReady()`, i.e. after its first tick), `STOPPING`, `STOPPED`, `CRASHED`; observable through `ServerInstance.addStateListener` (the host logs every transition)
- [x] Auto-create instance dir with a `server.properties` holding the port (only if there is none); the port is also passed as `--port`
- [x] Crash handling: `CRASHED` when the run ends with a non-zero exit status (Paper exits with 70 after a crash) or main throws before a stop was asked for; `Restart: on-crash` starts it again after 3s, giving up after 3 crashes within 10 minutes (`Host.RestartDelay`, `MaxCrashes`, `CrashWindow`); `stop` cancels a pending restart
- [x] Graceful stop with timeout, then force (interrupt threads, drop loader); now tested with a server that ignores interrupts (`ServerInstanceTest.stopForcesAServerThatWontEnd`)
- [x] Validate `paperhive.yml` with clear errors, all at once: unknown keys, wrong case or spelling (`port` -> "did you mean Port?"), wrong types, bad names, duplicate names/ports/folders, unknown software/version; the host exits with code 1
- [x] `ConfigurationVersion` migration path: one step per version (`HiveConfig.MIGRATIONS`), the original kept as `paperhive.yml.v<N>.bak`; version 0 (M6's lowercase `instances` format) migrates to 1; a newer version is refused
- [x] Host stays up while no server runs (e.g. all `Autostart: false`) until `stop` or Ctrl+C; the console's `<name> start` starts a server that isn't running (stopgap until the M8 commands)

**Exit:** lifecycle transitions are correct and observable.

(Verified: `ServerInstanceTest` checks the exact transition sequences, crash restart limits and forced stops on a fake server; `PaperInstanceIT` checks real Paper's `CREATED -> STARTING -> RUNNING -> STOPPING -> STOPPED` twice across a restart; the packaged host migrated an M6 `paperhive.yml`, logged every transition of two servers and exited 0 on `stop`.)

## M8: CLI
- [x] Host console commands: `hive list | create <n> [port] | set <n> <Key> [value] | delete <n> | start <n> | stop <n> | restart <n> | status <n> | shutdown | help` (`HiveCommands`); `create`/`set`/`delete` edit `paperhive.yml` in place, keeping its comments when the change fits in the text (otherwise it's rewritten, the old one kept as `.bak`), and write nothing if the result is invalid; `set` applies at the server's next start, `delete` only takes a stopped server and keeps its folder
- [x] `hive attach <n>`: console lines go to that instance and only its output shows; `hive detach` leaves (a line, not a key)
- [x] Class-based commands: one `HiveCommand` class per command (`host.command`), resolved by `CommandRegistry`; server state in `ServerManager`
- [x] `hive>` prompt (JLine): line editing, history (`.paperhive-history`), tab completion of commands, server names and arguments; output printed above the prompt; the leading `hive` is optional there; `<server> <command>` and `all <command>` reach Bukkit commands
- [x] Control socket: Unix domain socket `paperhive.sock` in the host's folder; `java -jar paperhive.jar hive <command>` sends one command and prints the reply; `hive attach <n>` streams that server's output and sends typed lines until `hive detach` or end of input; a second host in the same folder is refused, a stale socket file is replaced

**Exit:** operate everything from the CLI without editing files.

(Verified with real Paper and the packaged host: with no autostart server, `hive list`, `hive create beta`, `hive start beta`, `hive status beta`, `hive restart beta`, `hive shutdown` from a separate client all worked, the comment in `paperhive.yml` survived `create`, `hive attach beta` + `list` reached only beta, the host exited 0 and removed the socket; the client says so with exit 1 when no host runs. Then: `hive set beta Port 25610` / `Restart on-crash` before its first start and `Port 25611` while running took effect (Paper bound those ports, the second after `hive restart`), `hive attach beta` from a separate client showed beta's output and ran `list` and `hive status beta`, `hive delete beta` refused while it ran and removed it once stopped; `paperhive.yml` kept its comment through every edit.)

## M9: Plugin API
- [x] `paperhive-api`: `Hive` (`Paperhive.getHive()`), `HiveServer`, `HiveServerState`, `HiveServerListener` (started/stopped/crashed and the steps between), `Registration`; implemented by `HostHive`
- [x] `paperhive-bridge`: Paper plugin in every server (`--add-plugin`, from inside the host jar) that connects it to the host and loads Hive plugins' server halves
- [x] Calls: `getServers`, `getServer`, `getCurrentServer`, `createServer`; per server `start/stop/restart`, `dispatchCommand`, `runOnServer`, state, port, folder
- [x] TPS / tick time / player counts: `HiveServer.getStats()` (`HiveServerStats`, read from the server through its bridge; null when not running); shown by `list` and `status`
- [x] Cross-server messaging: `publish(channel, message)` / `subscribe`, string payloads, in order on one host thread, thread-safe
- [x] Event bus (`Hive.getEventBus()`, Guava-style): `@Subscribe` (priority, ignoreCancelled), `HiveEvent`/`CancellableEvent`, subtypes delivered; `ServerStateChangeEvent`, `ServerStartEvent` (cancellable), `ServerStartedEvent`, `ServerStoppedEvent`, `ServerCrashEvent`
- [x] Listeners and subscriptions are owned by their class loader's server or Hive plugin and dropped when it stops / is disabled
- [x] `paperhive-sdk`: one `compileOnly` dependency for plugin authors (Hive API, plugin API, `HiveBukkitPlugin`, Paper API)
- [x] Publish `paperhive-sdk` (and what it pulls in) to a Maven repository for plugin authors outside this build: every artifact (SDK, API, plugin API, bridge, host jar, Gradle plugin) is published to `https://maven.abdullahcxd.org/releases` by the `release` workflow on a `v*` tag, with POM metadata and sources jars

Hive plugins (host half `PaperhivePlugin`, loaded by Paperhive, not by Paper):
- [x] `PaperhivePlugin` base with `onEnable` / `onServerStart` / `onDisable` and `getLogger()`
- [x] Plugin descriptor `paperhive.yml` in the jar (`name`, `version`, `main`, `description`, `server-main`); `PluginLogger` uses its name (the logger is made lazily: it used to need the descriptor in the constructor)
- [x] `HivePluginManager`: loads jars from the host's `plugins/` (own loader each), enable, disable, find by name; a bad jar is logged and skipped
- [x] Honour `DisabledHivePlugins` per server (no `onServerStart`, no server half), names any case
- [x] `onServerStart` with a filled `ServerContext` (name, `server-ip`, port, the `HiveServer`) when a server is `RUNNING`
- [x] `plugin list | enable <name> | disable <name>` Hive command
- [x] Hive commands from Hive plugins: `PluginContext.getCommands().register(...)` in `onEnable`, removed on disable

Bukkit bridge (Hive plugins use their server like Bukkit plugins; design in `architecture.md`):
- [x] `paperhive-bridge` module built against the Paper API; Paperhive hands it to every instance (not via `plugins/`)
- [x] `ApiGateLoader` excludes `org.abdullahcxd.paperhive.bridge.*` so the bridge loads per instance
- [x] `HiveBukkitPlugin` as the server half, named by `server-main` in `paperhive.yml` (no `plugin.yml`); not a `JavaPlugin`: the bridge loads it with its own `ServerHalfLoader` (own jar first) and gives it the server, listeners, Brigadier commands, scheduler helpers, data folder and a logger
- [x] The bridge loads the server halves of the Hive plugins enabled for that server (`DisabledHivePlugins` skips); a failing one is taken back, the others carry on
- [x] Halves talk through the Hive API (messages, listeners, `HiveServer.runOnServer`); no shared plugin types
- [x] Loader-leak check with real Paper (`HivePluginLeakIT`): a server with the bridge and the example's server half (Bukkit listener, Hive listener and subscription, Brigadier command) restarted 10 times; every old instance loader is collected, the half ran every round, stats read each round. Negative check: without dropping a stopped server's Hive listeners, round 1's loader stays reachable and the test fails

**Exit:** a plugin in instance A can start and stop instance B; a Hive plugin's server half registers a Bukkit command in each server it runs in.

(Verified with real Paper and the packaged host, the example plugin (`test-plugin`, built with only `compileOnly(project(":paperhive-sdk"))`, no `plugin.yml`) in `plugins/`: the bridge in each server loaded the server half itself (about 10 ms; Paper's `/plugins` lists only Paperhive); its `/hiveserver` Brigadier command and `/hs` alias worked in both; a `@Subscribe` on `ServerStartEvent` cancelling `start locked` kept that server stopped; `alpha hiveserver start beta` started beta and `beta hiveserver stop alpha` stopped alpha; the server halves' messages reached the host half; `onServerStart` ran for each server and its `runOnServer` task ran on that server's `Server thread`; the host half's `greet` Hive command worked; on `shutdown` both servers stopped, the plugin was disabled, exit 0. `HivePluginManagerTest` loads the example's real jar.)

## M10: Hardening
- [x] Memory: per-server limits aren't possible (one heap, no per-loader accounting) and retained per-server memory isn't cheap to estimate, so none is shown; documented with how to size `-Xmx` and how to find per-server memory from a heap dump; the host logs the heap limit, `status` shows TPS / tick time (M9)
- [x] OOM/crash policy: keep running (the OOM'd server crashes and frees its loader); `MemoryWatch` warns when the heap is 90% full after a GC, naming the running servers; heap dump on the first OOM into `logs/` unless the launch flags decide; no host watchdog (Paper's per-server one stays; run under a supervisor for JVM death)
- [x] Test on Linux (path handling, process spawn, file locks, the Unix socket, native transport): every unit test passes on Ubuntu in the `build` workflow (incl. `ControlSocketTest` for the Unix socket and the `hive` client), and the real-Paper tests pass there in `paper-it`, with NIO and with epoll (Windows: everything in this file)
- [x] Soft caches: decided to leave `SoftRefLRUPolicyMSPerMB` alone (launch-only flag, JVM-wide effect; soft references clear before an OOM); documented as a launch option, the leak tests use it
- [x] CI: `.github/workflows/build.yml` runs `./gradlew build` on Ubuntu and Windows (Temurin 21), uploads test reports on failure; not run yet (no Git repository / remote here)
- [x] CI job for the real-Paper tests: `.github/workflows/paper-it.yml` downloads the Paper jar from the mirror (SHA-256 checked) and runs `PaperInstanceIT` and `HivePluginLeakIT` on Linux and Windows, on pushes that touch the host, weekly, and by hand
- [x] Paper version upgrade procedure and the list of what depends on the Paper version: `docs/upgrading.md`
- [x] Fork or not: no fork (the rewrites are JDK-level; no Paper patches); reasons in `docs/upgrading.md`

**Exit:** the version-sensitive parts are known and tested on every upgrade; memory and crash behaviour is decided.

## M11: Nice-to-have
- [x] Auto-download Paper builds via the Paperhive mirror (maps multiple upstream sources; SHA256-verified, cached)
- [x] Mirror: a replaced jar is never served stale: the downloader asks for `<file>?sha256=<hash from index.json>`, so new content is a new CDN cache key (checked: the query is cached separately, `MISS` then `HIT`, and serves the right file); publishing still needs no purge
- [x] Shared plugin folders: `SharedPlugins: [shared/plugins, ...]` per server, passed as Paper's own `--add-plugin-dir` (no symlinks or overlay needed): the jars are shared, each plugin's data folder (and Paper's remapped copy) stays in that server's `plugins/`; missing folders are created; settable with `set <name> SharedPlugins [...]`
- [x] Velocity auto-registration: `paperhive-velocity`, a Velocity plugin that asks the host through its control socket (`list --tsv`) every few seconds and registers its `RUNNING` servers with the proxy, unregisters them when they stop (or the host is unreachable), and leaves servers configured in `velocity.toml` alone; the proxy must run on the host's machine (Unix socket)
- [x] Velocity: test against a real proxy: Velocity from the mirror (`velocitymc/4.3.5-45`, boots as 4.3.1, needs Java 25) with the plugin: unreachable host warned, then alpha/beta registered on `RUNNING`, unregistered on `stop`, and all unregistered when the host shut down; `velocity.toml` servers untouched. Not tested: a player connecting through the proxy
- [x] Velocity: modern forwarding set up on the servers: `Host.VelocitySecret` (Velocity's `forwarding.secret`) makes Paperhive write `proxies.velocity` into each server's `config/paper-global.yml` and `online-mode=false` into its `server.properties` before every start, keeping the rest (`VelocityForwardingTest`)
- [x] Per-server JVM metrics: `status` shows live threads and the classes the server's loader defined (Paper and its libraries; plugins' loaders aren't counted)

(Verified: a fresh download through the new jar URL; with real Paper, two servers loading one shared folder kept separate data folders, `status` showed threads/classes, `hive list --tsv` over the socket gave the plugin's format; `ServerSyncTest` covers registering, port changes, configured servers and the host going away.)

---

## M12: Hive utilities
- [x] `Namespaced` keys (`namespace:value`); `key()` on both plugin halves; `publish`/`subscribe` take them
- [x] Typed channels (`HiveChannel<T>`, `MessageCodec<T>`) and sending to one server (`send`)
- [x] Request and reply (`HiveService<Q, R>`, `request`/`respond`): timeouts, no-responder errors, replies on the event thread
- [x] Shared data (`HiveData`): get/set/remove, compare-and-set, counters, watchers
- [x] Server-half helpers (`channel`, `service`, `onMessage`, `handle`, `relay`, `mainThread`) and Bukkit events (`HiveServerStateEvent`, `HiveMessageEvent`)
- [x] Bridge console messages in colour (MiniMessage): connecting, loading and enabling each server half, commands, other servers' states, disconnecting
- [x] The Paperhive logo (ASCII honeycomb) with the version when the host starts
- [x] Docs: namespaced keys, messaging, request and reply, shared data, server-half utilities; the example plugin and `createHivePlugin`'s starter use them

(Verified: `NamespacedTest`, `HostHiveTest` (targeted sends, requests incl. timeout/no responder/failure, typed channels and services, shared data); the real-Paper tests with the updated example plugin.)

---

## Open questions
- Does `PluginClassLoader` need transforming, or is `getDataFolder()` from `--plugins` enough?
- Netty native: force NIO, or share one Netty in the parent loader?
- log4j: per-instance context selector vs. routing appender?
- ~~SLF4J across the gate~~: decided in M9: Hive plugins load host-side (host SLF4J); their server halves log through Paper's `ComponentLogger` (`HiveBukkitPlugin.getLogger()`), so no logger type crosses.
- Calls to `System.exit` etc. made through reflection or `MethodHandles.lookup().find*` aren't rewritten; add if a library needs it. Same for plugins defining classes through Paper's reflection rewriter (`PaperReflection.defineClass`) or `Lookup.defineClass`.
- Child-first may let a library shadow a JDK platform class (`javax.*`, `org.xml.*`, `org.w3c.*`); go parent-first for platform packages if that ever causes `LinkageError`s?
- Name collision: "MultiPaper" is an existing, unrelated project; keep the Paperhive name.
