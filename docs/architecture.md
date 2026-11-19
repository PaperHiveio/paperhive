# Paperhive: Architecture Notes

## Goal
One JVM, many Paper servers. The host jar fetches/uses a stock `paper.jar`, patches it once with
Paperclip, then starts each instance in its own classloader.

## Process model
```
JVM
 |- host loader (paperhive-host, ASM, CLI)
 |    |- one loader per Hive plugin (host half)
 |    |- ApiGateLoader  (parent of all instances: JDK + org.abdullahcxd.paperhive.* only)
 |         |- InstanceClassLoader "hive-lobby"     -> patched paper + libs
 |         |- InstanceClassLoader "hive-survival"  -> patched paper + libs
```
- Paperhive's own classes (api, host, plugin, ...) live in the host loader and are exposed through
  `ApiGateLoader`, so plugins inside an instance can use Paper and Paperhive together. The host's
  third-party libraries (Log4j, SLF4J, Jackson, ASM) are not exposed, so they never clash with Paper's.
- Everything else (server classes, libraries) is loaded child-first per instance, so each instance
  gets its own statics.

## Paperclip output (modern Paper)
Run `java -Dpaperclip.patchonly=true -jar paper.jar` in the cache dir. Result:
- `versions/<ver>/paper-<ver>.jar`: patched server
- `libraries/**.jar`: dependencies
- Real main class: `META-INF/main-class` inside the **Paperclip** jar (`paper.jar`), not the patched jar
- `cache/mojang_<ver>.jar`: vanilla jar Paperclip patched from (not needed at runtime)

Paperhive caches this per jar in `<cache>/<sha256 prefix>/` (`PaperclipCache`):
- `<cache>/.lock`: exclusive while a host patches or prunes, so concurrent hosts never patch twice
- `.complete`: written only after a successful patch and read; missing means re-patch
- `.in-use`: shared-locked by every host running from that entry; entries nobody holds are deleted

## Why not a fork or a Java agent
- Fork: rebase Paper's patches every version; users can't use the stock PaperMC jar.
- Agent: global, needs a launch flag or self-attach; can't be scoped per instance.
- Loader + ASM: scoped per instance, no flags, works on stock jars. Fall back to a fork only if the
  transform list becomes large and fragile.

## Transform list (grows as things break)
| Target                                                     | Why                               | Replacement                                                      |
|------------------------------------------------------------|-----------------------------------|------------------------------------------------------------------|
| `System.exit`, `Runtime.exit/halt`                         | would kill every instance         | signal "stop this instance"                                      |
| `System.in/out/err`                                        | process-global                    | per-instance slots (`HiveIo`) + thread-routing JVM-wide streams  |
| `java.util.logging` `Logger.getLogger`, `LogManager.*`     | one JVM-wide logger tree          | per-instance namespace (`HiveJul`)                               |
| `java.io.File*` constructors                               | relative paths use host cwd       | path argument made absolute against the instance root (`HiveFs`) |
| `Path.of`, `Paths.get`, `FileSystems.getDefault().getPath` | same                              | static helpers                                                   |
| shutdown hooks                                             | run at JVM end, not instance stop | capture and run on instance stop                                 |

## Working directory (M4)
Java cannot change cwd; `user.dir` is cached at startup. So `InstanceTransformer` makes relative paths absolute
against the calling instance's root at the moment they're created (`HiveFs`): path arguments of `File`/stream
constructors are rewritten in place, `Path.of`/`Paths.get`/`FileSystem.getPath` and `getAbsolutePath`/`toAbsolutePath`
go through static helpers, `user.dir` and child-process directories point at the root. Objects stay plain JDK
ones (no `HiveFile extends File` shims, which would change `getClass()`/`equals` and still miss JDK internals that
take a `File`). Absolute paths skip the lookup; relative ones find the instance via `StackWalker` caller class ->
its loader -> `InstanceClassLoader` (fallback: the thread's context loader).

The host's own cwd is the Paperhive home: `.paperhive-cache/`, `instances/<name>/`, and the host's `logs/`.

## Stopping an instance (M3)
A JVM exit is emulated per instance:
- `InstanceClassLoader` defines every class itself and passes it through `InstanceTransformer` (ASM), which rewrites
  `System.exit`/`Runtime.exit`/`halt`, `add/removeShutdownHook`, `FileChannel.lock/tryLock`, JFR registrations and
  `Executors.newSingleThreadExecutor` into `InstanceHooks`, which act on the calling instance only.
- Exit runs the instance's hooks, then unwinds the calling thread (`InstanceExit`). The reaper gives the remaining
  threads 5s, then shuts down their pools and interrupts them; an instance with only daemon threads left ends too.
- `finish()` releases file locks, shuts tracked executors, undoes JVM-wide registrations (JFR, JUL handlers, MBeans,
  `System.out`, ...), closes the loader and drops every reference, so the loader can be collected and the instance restarted.

## Commands (M8)
Two kinds of commands, which are easy to mix up:

|               | Hive commands                                                                  | Bukkit commands                                                                    |
|---------------|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| Runs          | in the host, once                                                              | inside one server                                                                  |
| Typed at      | the host's `hive>` prompt, or `java -jar paperhive.jar hive <command>`         | that server's console (`<server> <command>`, `all <command>`, `attach`) or in game |
| API           | `HiveCommand` (`paperhive-plugin`, `org.abdullahcxd.paperhive.plugin.command`) | Paper's Brigadier commands (`HiveBukkitPlugin.registerCommand`)                    |
| Who adds them | Paperhive (`list`, `start`, `set`, ...) and Hive plugins (host half)           | Paper plugins, and Hive plugins (server half, see the Bukkit bridge)               |

- A Hive command is a class: name, usage, description, aliases, `execute(sender, args)` and optional
  `complete(sender, args)` for tab completion. Paperhive's own live in `host.command`, one class each; a Hive plugin
  registers its own with `context.getContext().getCommands().register(...)` in `onEnable` (see `ExampleHivePlugin`).
  Names and aliases are case-insensitive and unique; a plugin can't take a built-in's name.
- `CommandRegistry.execute` resolves a typed line: an optional leading `hive`, then a command name or alias, then
  `<server> <command>` / `all <command>` for Bukkit commands. The `hive>` prompt (`HiveConsole`, JLine) and the control
  socket (`ControlSocket`) both go through it; the sender says which (`isConsole()`).
- Hive plugins can also create **Bukkit commands**: those belong to a server, so the plugin's server half registers
  them (`registerCommand` in its `onEnable`, see the Bukkit bridge below) and they run inside each server like any
  Paper plugin's command; the example's `/hiveserver` (alias `/hs`) starts and stops other servers from inside one.
- The prompt: line editing, history in `.paperhive-history`, tab completion. Logs and server output are printed
  above the prompt (`LineReader.printAbove`), so `System.out`/`err` are pointed there before logging starts.
  Without a terminal (piped input, a service) it reads plain lines with no prompt; once input ends, `hive` clients
  still control the host.

## Hive API and Hive plugins (M9)
- **One dependency for plugin authors**: `compileOnly(project(":paperhive-sdk"))` (or the Gradle plugin below) brings the Hive API, the Hive plugin
  API, `HiveBukkitPlugin` and the Paper API. Everything in it is provided at runtime (host and servers), so nothing
  is shaded into the plugin.
- **Hive API** (`paperhive-api`): `Paperhive.getHive()` gives the `Hive` (servers, `createServer`, the event bus,
  `publish`/`subscribe` messaging) and `HiveServer` handles (state, port, folder, start/stop/restart,
  `dispatchCommand`, `runOnServer`). One implementation (`HostHive`) for the whole JVM, usable from the host and from
  inside every server (the API classes are shared through `ApiGateLoader`).
- **Events** (`api.event`), in the style of Guava's EventBus with Bukkit's ideas renamed: any object with
  `@Subscribe` methods is a listener (`Hive.getEventBus().register(obj)`, no interface), `@Subscribe(priority =
  Priority.X, ignoreCancelled = ...)` replaces `@EventHandler`, `HiveEvent`/`CancellableEvent` are the base classes,
  subscribers get subtypes too, and plugins may post their own events. Built in: `ServerStateChangeEvent`,
  `ServerStartEvent` (cancellable, before a start through Paperhive; not for crash restarts), `ServerStartedEvent`,
  `ServerStoppedEvent`, `ServerCrashEvent`. `HiveEventBus` finds a class's subscribers once (`ClassValue`), calls them
  through method handles, and caches the handler list per event type until a listener comes or goes. Paperhive posts
  its state events in order on one host thread (`paperhive-events`); listeners use Bukkit only after hopping onto a
  server's main thread (`runOnServer`, or the server half's scheduler).
- **Ownership**: event listeners and message subscriptions remember who made them, from their class loader: a server
  (any loader inside it, e.g. a server half's) or a Hive plugin's loader. They're dropped when that server stops or
  that plugin is disabled, so they can't keep its classes alive.
- **Hive plugins** are jars in the host's `plugins/` folder with a `paperhive.yml` (`name`, `version`, `main`,
  optional `description` and `server-main`). `HivePluginManager` loads each with its own class loader (parent: the
  host's), calls `onEnable` (Hive commands registered through its context are removed again on disable),
  `onServerStart` when a server they're enabled for is `RUNNING`, and `onDisable`. `DisabledHivePlugins` in a
  server's `paperhive.yml` entry turns both halves off for that server; `plugin enable|disable <name>` turns a plugin
  off everywhere.

## Hive utilities (M12)
- **`Namespaced`** (`namespace:value`, Paper's key rules): names channels, services and shared data. Plugins get
  `key(value)` in their namespace (their descriptor name, made valid), the same on both halves.
- **Typed layers over strings**: only text crosses class loaders, so `HiveChannel<T>` and `HiveService<Q, R>` (API
  classes, no host code) encode with a `MessageCodec` on each end. The host implements the string primitives:
  `publish`, `send` (one server, or the host side with `null`), `request`/`respond` and `getData()`.
- **Ownership**: a listener belongs to the server (or Hive plugin) whose loader defined its class; for a class
  shared by everyone (the API's wrappers, JDK lambdas) the nearest caller on the stack that belongs to one decides.
  That's what targeted sends and requests match on, and what's dropped when a server stops.
- **Requests**: the target's newest responder answers on the event thread; its `CompletionStage` result is passed
  back through the event thread too, so the requester's callbacks never run on the responding server's main thread.
  No responder: `NoResponderException`; none in time: `TimeoutException`.
- **Shared data** (`HiveDataStore`): one `ConcurrentHashMap` in the host, atomic per key (`compute`), watchers owned
  and told on the event thread; in memory for the host's lifetime.
- **Server-half side**: `HiveBukkitPlugin` adds `channel`/`service` in its namespace, `onMessage`/`handle` (on the
  main thread), `relay` (messages as Bukkit `HiveMessageEvent`s) and `mainThread()`; the bridge fires
  `HiveServerStateEvent` in every server and logs its traffic with the host in colour (MiniMessage).
- **Console**: the host prints the Paperhive logo (a honeycomb) with its version before anything starts; JLine drops
  the colours on a plain terminal.

## Bukkit bridge (M9)
Goal: a Hive plugin works with the server it runs in like a Bukkit plugin does (server, events, commands, scheduler,
data folder), while staying one plugin for the whole hive, with no `plugin.yml` to write.

**The problem.** Bukkit classes live in each instance's loader, a separate copy per server. Host code can't link
against `org.bukkit.*` at all, and code that does is tied to one server's copy. So a Hive plugin can't be one object
that is both a host plugin and a Bukkit plugin.

**Two halves in one jar.**
```
host loader                         instance "lobby"                    instance "survival"
 |- MyHivePlugin (host half)         |- Paperhive (the bridge, a Paper   |- Paperhive (bridge)
 |    onEnable, Hive commands,       |    plugin, --add-plugin)          |    |- MyServerSide
 |    @Subscribe, onServerStart      |    |- MyServerSide (server half,  |
 |                                   |         own ServerHalfLoader)     |
```
- **Host half**: `PaperhivePlugin`, `main` in `paperhive.yml`, loaded once in the host. Never touches Bukkit.
- **Server half**: `server-main` in `paperhive.yml`, a class extending `HiveBukkitPlugin`. Every server the plugin is
  enabled for loads it. It is **not** a `JavaPlugin`: no `plugin.yml`, no Bukkit plugin lifecycle. It gets
  `onEnable`/`onDisable`, `getServer()`, `registerListener` (Bukkit listeners), `registerCommand` (Paper's Brigadier
  commands: a `BasicCommand` or a full command tree, with aliases), `runTask*` helpers, `getDataFolder()`
  (`plugins/<name>/` of that server), a `ComponentLogger` printing `[<name>]`, `getHive()`/`getHiveServer()`, and
  `getBukkitPlugin()` for Bukkit APIs that insist on a `Plugin`.

**How it's loaded.**
- Only the bridge is a Paper plugin. Paperhive writes it from inside the host jar to
  `.paperhive-cache/paperhive-bridge.jar` and adds it to every server with Paper's own `--add-plugin`, so nothing is
  copied into a server's `plugins/` and no Paper internals are touched.
- When the server enables its plugins (after its worlds), the bridge asks the host which server halves this server
  loads (enabled Hive plugins with a `server-main` it doesn't disable) and loads each directly. Compared with
  Paper's plugin loading, this skips reading `plugin.yml`/dependency resolution and the remapping step (the
  bridge itself is mojang-mapped), and each half gets its own `ServerHalfLoader`: own jar first (its classes don't
  go through Paper's search of every other plugin, shaded libraries stay its own), while Java, Bukkit/Paper,
  Adventure, Brigadier, logging and Paperhive come from the server. The bridge logs how long it took (about 10 ms for
  the example).
- A half that fails to load or enable is logged and its listeners and commands are taken back; the others carry on.
- Commands are queued in `onEnable` and handed to Paper in the bridge's `COMMANDS` lifecycle handler (on startup
  and every reload). Their fallback namespace is the bridge's (`/paperhive:<label>`).
- `ApiGateLoader` shares `org.abdullahcxd.paperhive.*` from the host except `org.abdullahcxd.paperhive.bridge.*`
  (`HiveBukkitPlugin` and the bridge need Bukkit, so they load per server).
- Server halves don't appear in Paper's `/plugins` (only `Paperhive` does), and Bukkit's per-plugin features they
  don't have (`plugin.yml` permissions/dependencies) are replaced by plain Bukkit APIs; `getConfig()` is reimplemented
  on `HiveBukkitPlugin`.
- **Bridge events and utilities**: `BridgeEvents` turns Bukkit events into hive-aware ones (`PlayerJoinHiveEvent`,
  `PlayerLeaveHiveEvent` via `PlayerPresence` marks in shared data, CAS so either quit/join order works) and
  convenience ones (`PlayerMoveBlockEvent`, `PlayerDamageByPlayerEvent`, `PlayerIdleEvent`; hot-path ones built only
  when listened to). `bridge.util` has `Text`, `ItemBuilder`, `Cooldowns`.

**Talking between the halves.** They're loaded by different loaders, so a class from the plugin's own jar is a
different class in each half (and in each server). They talk through the Hive API: events, messages, `HiveServer`.
Host -> a server's Bukkit: `runOnServer(Runnable)`.

**Lifecycle per server.** server starts -> Paper enables the bridge -> the bridge enables the server halves
(`onEnable`) -> commands registered -> server is `RUNNING` (`ServerStartedEvent`, host half's `onServerStart`);
server stops -> server halves' `onDisable` (reverse order) -> their loaders closed -> the server's Hive listeners and
subscriptions dropped -> the instance's loaders dropped.

## Gradle plugin (userdev)
`paperhive-gradle` is a Gradle plugin for Hive plugin projects, like paperweight-userdev is for Paper plugins: no
dependencies, repositories or descriptor to set up by hand. Releases publish it with the SDK (`paperhive-api`,
`-plugin`, `-bridge`, `-sdk`) and the host jar to `https://maven.abdullahcxd.org/releases` (`./gradlew
publishToMavenLocal` for local builds). A plugin project then needs only:
```kotlin
// settings.gradle.kts
pluginManagement { repositories { maven("https://maven.abdullahcxd.org/releases"); gradlePluginPortal() } }
// build.gradle.kts
plugins { id("org.abdullahcxd.paperhive.userdev") version "x.y.z" }
```
It applies `java` with a Java 21 toolchain, adds Maven Central, PaperMC, the Paperhive repository and Maven local (`paperhive {
addRepositories = false }` when `settings.gradle.kts` declares repositories itself), and `compileOnly` on
`paperhive-sdk` of `paperhive { paperhiveVersion }` (default: the plugin's own version). Tasks (group `paperhive`):
- `createHivePlugin [--name=X] [--package=a.b]`: a host half, a server half and `paperhive.yml`; never overwrites.
- `generatePaperhiveDescriptor`: with `paperhive { main = "..." }` set (and optionally `serverMain`, `pluginName`,
  `pluginVersion`, `pluginDescription`), writes `paperhive.yml` into the jar, so `src/main/resources` needs none.
- `runHive`: builds the jar and runs the Paperhive host in `run/` (`paperhive { runDirectory }`; host from the
  `paperhiveHost` configuration) with the console attached. The jar is loaded from where it was built
  (`-Dpaperhive.devPlugins=<jars>`, path-separated, read by the host next to `Host.Plugins`), so nothing is copied
  and it works whatever `Host.Plugins` the run directory's config names.

`paperhive-gradle` is an included build (`pluginManagement { includeBuild(...) }`) so this build applies it too:
`test-plugin` uses it like any plugin project. Inside the Paperhive build the plugin depends on the `:paperhive-sdk`
and `:paperhive-host` projects instead of published artifacts. The root `build`, `publish` and `publishToMavenLocal`
tasks include it; the version comes from the root `gradle.properties` (or `-Pversion`, as releases set it).

## Documentation site
The public docs at `https://paperhive.abdullahcxd.org` are Markdown in `website/docs/`, built with docmd
(`website/docmd.config.json`: navigation, logo, theme). `cd website && npm install && npm run dev` previews them;
`npm run validate` checks every internal link (write links as relative `.md` paths). It's served by a Cloudflare
Worker with static assets only (`website/wrangler.jsonc`, Worker `paperhive-docs`): `npm run deploy` builds and
uploads it, and the `custom_domain` route makes Cloudflare create the DNS record and certificate for
`paperhive.abdullahcxd.org` itself. The `docs` workflow checks links, builds and dry-runs the deploy on every change,
and deploys on pushes to `main`.

## Releasing
Push a tag `v<version>` (e.g. `v0.1.0`). The `release` workflow builds and tests with `-Pversion=<version>`,
publishes every artifact to `https://maven.abdullahcxd.org/releases` (repository secrets `MAVEN_USERNAME` and
`MAVEN_PASSWORD`), and creates a GitHub release with the host jar (`paperhive-<version>.jar`) and the Velocity plugin attached; a version
with a `-` (`0.1.0-beta.1`) is marked a pre-release. Locally: `./gradlew publish -Pversion=... -PabdullahcxdUsername=...
-PabdullahcxdPassword=...`.

## Shared plugins and Velocity (M11)
- **Shared plugin folders**: `SharedPlugins` lists extra folders of plugin jars per server, passed to Paper as
  `--add-plugin-dir`. Paper loads the jars from there but keeps each plugin's data folder (and its remapped copy)
  in the server's own `plugins/`, so servers share jars, not state. Updating a jar in a shared folder reaches each
  server at its next start.
- **Velocity**: `paperhive-velocity` (a Velocity plugin) polls the host's control socket with `list --tsv` (stable
  format: `name<TAB>state<TAB>port`) and registers every `RUNNING` server as `host:port` (`config.properties`:
  `socket`, `host`, `interval`), unregistering it when it leaves `RUNNING`, changes port, or the host is unreachable.
  Servers already in `velocity.toml` are never touched. Same machine only (the socket is local); players still need a
  `try` entry or forced host naming a server.
- **Modern forwarding**: with `Host.VelocitySecret` (the path of Velocity's `forwarding.secret`), `ServerManager.start`
  runs `VelocityForwarding` before every start: `proxies.velocity` (`enabled`, `online-mode`, `secret`) in the server's
  `config/paper-global.yml` and `online-mode=false` in its `server.properties`, keeping the rest of both files and
  writing nothing when they already match. A missing or empty secret stops that start (a proxy couldn't log players
  in). Crash restarts reuse the files as they are.
- **Mirror URLs**: jars are requested as `<file>?sha256=<hash>` (the hash from `index.json`), so a jar replaced under
  the same name is a new URL for the CDN and can't be served stale.

## Memory and crashes (M10)
- **No per-server memory limit.** Every server allocates from the one JVM heap, and the JVM has no way to cap or
  account heap per class loader; the only limit is the host's `-Xmx`. Size it for all servers together (a Paper
  server is typically 1-2 GB with a few players; `status` reports TPS and tick time per server, the host logs the
  heap limit at startup). Per-server *retained* memory would need a heap walk (seconds, stop-the-world), so Paperhive
  doesn't estimate it; a heap dump (`jcmd <pid> GC.heap_dump`) with a dominator view per `InstanceClassLoader` answers
  that when needed.
- **Early warning.** `MemoryWatch` sets each heap pool's collection-usage threshold to `Host.MemoryWarning` (90%): when the heap is still
  that full right after a GC, the host logs which servers are running, so one can be stopped or `-Xmx` raised
  before an `OutOfMemoryError`.
- **OutOfMemoryError policy.** Decided: keep running, don't exit the JVM. An OOM usually hits the thread that
  allocates, most often a server's; that server then crashes (`CRASHED`, with `Restart: on-crash` it comes back) and
  its memory is freed with its loader, so the others can carry on. Exiting on OOM (`-XX:+ExitOnOutOfMemoryError`)
  would stop every server without saving. The host turns on `HeapDumpOnOutOfMemoryError` (into `logs/`) unless the
  launch flags already decide it, so the first OOM leaves evidence.
- **Watchdogs.** Paper's own watchdog (a server thread hung for 60 s) stays per server: its `System.exit`/`halt`
  are rewritten to stop only that server. There is no host-level watchdog; if the JVM itself dies (native crash,
  `kill -9`), run Paperhive under a supervisor (systemd `Restart=on-failure`, a container restart policy) and
  servers with `Autostart: true` come back.
- **Soft references.** A stopped server's classes can stay reachable through JDK-internal soft caches (e.g. method
  handle `asType` caches) until the heap gets tight; the JVM clears soft references before throwing an OOM, so this
  is memory held longer, not a leak. Decided: leave it. `SoftRefLRUPolicyMSPerMB` can't be changed at runtime (a
  launch flag), and lowering it affects every soft cache in the JVM; add `-XX:SoftRefLRUPolicyMSPerMB=0` to the
  launch flags only if restarting servers often on a small heap. The leak tests run with it, to tell lingering from leaking,
  and clear every soft reference before calling a loader leaked. A seen case: `ObjectMethods.OBJECTS_EQUALS` (the JDK
  handle behind every record's `equals`) caches one `asType` adaptation softly; the last server to compare a
  Minecraft record leaves its class (and loader) in it until the next server replaces it or the heap gets tight.

## Late relative paths, JDBC drivers and log files (M3/M4 follow-ups)
- **Log files**: Paper leaves its Log4j to JVM exit, so a stopped instance's appenders would keep `logs/latest.log`
  open until a GC; Windows then can't rotate it at the next start. `InstanceLog4j` shuts down the instance loader's
  Log4j context after the instance stops (only if it ever loaded Log4j).
- **`Files.*`**: a relative `Path` the JDK derives (`getFileName()`, `relativize`) isn't made absolute when it's
  created, so opening it would resolve against the JVM cwd. `FilesMirror` generates `HiveFiles` at startup: one static
  method per `java.nio.file.Files` method taking a `Path` (from `Files` itself, so none is missed on another JDK),
  same name and descriptor, which makes relative `Path` arguments absolute (`HiveFs.file`) and calls the real one.
  `InstanceTransformer` points instance code's `Files.*` calls (and method references) at it. Still not covered: a
  relative `Path` opened through its `FileSystemProvider` directly.
- **JDBC drivers of plugin loaders**: `DriverManager` only lets a driver's own loader (or a child) see or remove it.
  `InstanceHooks.define` remembers every loader inside an instance that defines classes (Paper's plugin loaders,
  server halves' `ServerHalfLoader`), and on stop `JdbcDrivers` defines its helper in each of them, not just the
  instance loader. `ServerHalfLoader` defines its classes itself (instead of `URLClassLoader`'s JDK code) for this,
  which also gets server halves rewritten like Paper's plugins (`System.exit`, files, ...).

## Server software (M13)

Everything that differs per software lives in a `ServerSoftware` subclass (`host/software/<name>/`), registered in
`ServerSoftwares` by its `MCSoftware` id: preparing the jar, default and config arguments, unsupported config keys,
installing Paperhive's own plugin, readiness, graceful stop, console commands, per-server system properties and
`ClassPatch`es. `ServerInstance` keeps what every server gets (loader, root, streams, exit, JDK rewrites) and asks
its software for the rest; `InstanceTransformer` runs the generic rewrites, then the software's matching patches.

- **Paper**: Paperclip output, `--add-plugin` for the bridge, ready when `MinecraftServer.isReady()`, stops via
  `Bukkit.shutdown()`.
- **Velocity**: the jar itself, its main is varargs (`asFixedArity()`), ready once its port accepts, JLine off
  (`terminal.jline=false` for that server only), `VelocityShutdownPatch` makes `awaitProxyShutdown` interruptible so
  the main thread ends at stop. Its plugin reads servers through the Hive API in-process and takes console commands
  through `BridgeHooks.consoleEnabled`.

Build: convention plugins in `build-logic` (`paperhive.java`, `paperhive.library`, `paperhive.publishing`).

## Known hazards
- **Native libraries** (Netty epoll/io_uring, SQLite JNI): a native lib loads into one classloader per
  JVM. Use NIO transport or share Netty from the parent.
- **Logging**: each instance has its own Log4j (own loader). JUL is JVM-wide, so it gets a per-instance namespace (M5); records that still reach the JVM-wide root (JDK-internal loggers) are routed to the instance by thread.
- **Leaks**: unjoined threads, shutdown hooks, `ThreadLocal`s, and static caches in shared classes keep
  the instance loader alive after stop.
- **Isolation limits**: no per-instance heap limit; a JVM crash takes all instances down (see "Memory and crashes").
- **Plugins** use Paper's `PluginClassLoader`, which defines its own classes and bypasses your
  transforms unless hooked.
- **Child processes** started by instance code get the instance root as directory (M4).
- **Version drift**: Paper internals change per release; pin one version per Paperhive release (`docs/upgrading.md`).

## Dev setup
- Java: the JDK Paper requires (21 for 1.21.x).
- Test loop: boot instance, join, stop, restart; check class/thread counts each cycle.
- Useful: `jcmd <pid> Thread.print`, `jcmd <pid> GC.class_histogram`, heap dumps for loader leaks.
